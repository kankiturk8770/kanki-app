package cafe.kankipro.vpn

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * HTTP/1.1 روی سوکت خام (ثبت DNS کانکی). ۱.۴۵.۲: https هم: TLS با SNI و بررسی نام گواهی روی همان سوکت خام،
 * تا protect (بیرون از VPN) پیش از اتصال اجرا شود.
 */
object PlainHttp {
    /** prepare: پیش از اتصال روی سوکت اجرا می‌شود (مثلاً protect تا از VPN بیرون برود) */
    fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?, prepare: ((Socket) -> Unit)? = null, timeoutMs: Int = 15000): Pair<Int, ByteArray> {
        val u = URL(url)
        val tls = u.protocol == "https"
        if (!tls && u.protocol != "http") throw IOException("only http and https")
        val port = if (u.port > 0) u.port else if (tls) 443 else 80
        val path = (u.path.ifEmpty { "/" }) + (u.query?.let { "?$it" } ?: "")
        Socket().use { raw ->
            prepare?.invoke(raw)
            raw.connect(InetSocketAddress(u.host, port), timeoutMs)
            raw.soTimeout = maxOf(timeoutMs, 25000)
            val s: Socket = if (tls) secure(raw, u.host, port) else raw
            val head = StringBuilder()
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            head.append("Host: ").append(u.host).append(if (u.port > 0 && u.port != (if (tls) 443 else 80)) ":${u.port}" else "").append("\r\n")
            headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
            if (body != null) head.append("Content-Length: ").append(body.size).append("\r\n")
            head.append("Connection: close\r\n\r\n")
            val out = s.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.UTF_8))
            if (body != null) out.write(body)
            out.flush()
            val inp = s.getInputStream().buffered()
            val status = line(inp).split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad HTTP answer")
            var length = -1
            var chunked = false
            while (true) {
                val l = line(inp)
                if (l.isEmpty()) break
                val k = l.substringBefore(':').trim().lowercase()
                val v = l.substringAfter(':', "").trim()
                if (k == "content-length") length = v.toIntOrNull() ?: -1
                if (k == "transfer-encoding" && v.lowercase().contains("chunked")) chunked = true
            }
            val data = when {
                chunked -> ByteArrayOutputStream().also { o ->
                    while (true) {
                        val n = line(inp).substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("bad chunk")
                        if (n == 0) break
                        o.write(exact(inp, n))
                        line(inp)
                    }
                }.toByteArray()
                length >= 0 -> exact(inp, length)
                else -> inp.readBytes()
            }
            return status to data
        }
    }

    /** TLS over the connected socket: SNI = the host, the certificate checked by the system's trust and hostname rules */
    private fun secure(raw: Socket, host: String, port: Int): Socket {
        val f = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
        val t = f.createSocket(raw, host, port, true) as javax.net.ssl.SSLSocket
        t.useClientMode = true
        t.startHandshake()
        if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, t.session)) {
            runCatching { t.close() }
            throw IOException("certificate does not match $host")
        }
        return t
    }

    private fun line(i: InputStream): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c < 0) { if (b.size() == 0) throw IOException("connection closed"); break }
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
            if (b.size() > 16384) throw IOException("header too long")
        }
        return String(b.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun exact(i: InputStream, n: Int): ByteArray {
        if (n > 20_000_000) throw IOException("too large")
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = i.read(buf, off, n - off)
            if (r < 0) throw IOException("connection closed")
            off += r
        }
        return buf
    }
}
