package cafe.kankipro.vpn

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** یک پیام چت اضطراری. verified: پیام ادمین با امضای درست (برای عکس بعد از دانلود معلوم می‌شود) */
data class EMsg(
    val id: Long,
    val admin: Boolean,
    val kind: String,   // text | config | image
    val body: String,
    val ts: Long,
    val sig: String,
    val img: Boolean,
    val verified: Boolean,
)

class EmergencyException(val code: Int, msg: String) : IOException(msg)

/**
 * چت اضطراری: وقتی اینترنت بین‌الملل قطع است، اپ با سرور کوچکی که ادمین روی یک سرور داخل ایران گذاشته
 * حرف می‌زند (آدرسش را پنل اصلی در صفحه‌ی اشتراک می‌دهد و این‌جا ذخیره می‌شود).
 *
 * امنیت:
 * - کاربر با هش کد اشتراکش شناخته می‌شود، خود کد هیچ‌وقت فرستاده نمی‌شود.
 * - هر پیام ادمین امضا دارد (ECDSA P-256). کلید عمومی را پنل اصلی وقتی اینترنت وصل بود داده؛
 *   پیامی که امضایش درست نباشد «تأیید نشده» نشان داده می‌شود و کانفیگش قابل کپی نیست.
 */
object Emergency {
    private const val ID_PREFIX = "kanki-emergency-v1:"
    private const val SIGN_PREFIX = "kanki-emergency-v1"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("kanki", Context.MODE_PRIVATE)

    /** آدرس سرور اضطراری، فقط همانی که پنل در صفحه‌ی اشتراک داده؛ null یعنی هنوز نگرفته‌ایم */
    fun base(ctx: Context): String? =
        prefs(ctx).getString("emergency_url", null)?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

    /** کلید عمومی امضای ادمین (SubjectPublicKeyInfo، base64)؛ null یعنی هنوز از پنل نگرفته‌ایم */
    fun key(ctx: Context): String? = prefs(ctx).getString("emergency_key", null)?.takeIf { it.isNotBlank() }

    /** آدرس و کلیدی که پنل در صفحه‌ی اشتراک داده را نگه می‌دارد (مقدار خالی چیزی را پاک نمی‌کند) */
    fun remember(ctx: Context, url: String?, key: String?) {
        val e = prefs(ctx).edit()
        if (!url.isNullOrBlank() && (url.startsWith("https://") || url.startsWith("http://"))) e.putString("emergency_url", url.trimEnd('/'))
        if (!key.isNullOrBlank()) e.putString("emergency_key", key.trim())
        e.apply()
    }

    /** کدی که کاربرِ واردنشده برای چت اضطراری زده است */
    fun savedCode(ctx: Context): String? = prefs(ctx).getString("emergency_code", null)
    fun saveCode(ctx: Context, code: String) = prefs(ctx).edit().putString("emergency_code", code).apply()

    fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** شناسه‌ی کاربر برای سرور اضطراری (پنل همین را حساب می‌کند) */
    fun userId(code: String): String = sha256Hex((ID_PREFIX + code).toByteArray(Charsets.UTF_8))

    /** متنی که ادمین امضا کرده (سرور دقیقاً همین را می‌سازد) */
    fun signedText(id: Long, user: String, ts: Long, kind: String, payload: String) =
        "$SIGN_PREFIX\n$id\n$user\n$ts\n$kind\n$payload"

    fun verify(pub: String?, text: String, sig: String): Boolean {
        if (pub.isNullOrBlank() || sig.isBlank()) return false
        return runCatching {
            val k = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(pub, Base64.DEFAULT)))
            val s = Signature.getInstance("SHA256withECDSA")
            s.initVerify(k)
            s.update(text.toByteArray(Charsets.UTF_8))
            s.verify(Base64.decode(sig, Base64.DEFAULT))
        }.getOrDefault(false)
    }

    /** کد خطای «آدرس سرور اضطراری هنوز از پنل نیامده» */
    const val NO_ADDRESS = -1

    /**
     * یک درخواست به سرور اضطراری. HTTPS با HttpURLConnection؛ HTTP ساده (وقتی ادمین گواهی نگذاشته)
     * با یک سوکت خام، چون اندروید HTTP بی‌رمز را فقط برای دامنه‌های ثابتِ فایل تنظیمات اجازه می‌دهد
     * و دامنه‌ی چت را حالا ادمین در پنل تعیین می‌کند. امنیت پیام‌ها به امضای ادمین است، نه به TLS.
     */
    private fun call(ctx: Context, method: String, path: String, code: String, body: ByteArray? = null, type: String? = null): ByteArray {
        val b = base(ctx) ?: throw EmergencyException(NO_ADDRESS, "no emergency address yet")
        val headers = linkedMapOf("User-Agent" to "KankiVPN-Android", "x-kanki-id" to userId(code))
        if (type != null) headers["Content-Type"] = type
        val (status, bytes) = if (b.startsWith("http://")) PlainHttp.request(method, b + path, headers, body)
        else {
            val c = (URL(b + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 25000
                requestMethod = method
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
                val st = c.responseCode
                st to ((if (st in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0))
            } finally {
                c.disconnect()
            }
        }
        if (status !in 200..299) {
            val msg = runCatching { JSONObject(String(bytes, Charsets.UTF_8)).optString("error") }.getOrNull().orEmpty()
            throw EmergencyException(status, msg.ifBlank { "HTTP $status" })
        }
        return bytes
    }

    private fun post(ctx: Context, path: String, code: String, body: ByteArray, type: String): JSONObject =
        JSONObject(String(call(ctx, "POST", path, code, body, type), Charsets.UTF_8))

    /** پیام‌های بعد از after؛ (نام کاربری، پیام‌ها) */
    fun messages(ctx: Context, code: String, after: Long): Pair<String, List<EMsg>> {
        val o = JSONObject(String(call(ctx, "GET", "/api/e/messages?after=$after", code), Charsets.UTF_8))
        val user = o.optString("user")
        val arr = o.optJSONArray("messages") ?: JSONArray()
        val pub = key(ctx)
        val list = (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            val id = m.optLong("id")
            val admin = m.optBoolean("admin")
            val kind = m.optString("kind")
            val body = m.optString("body")
            val ts = m.optLong("ts")
            val sig = m.optString("sig")
            val img = m.optBoolean("img")
            // متن و کانفیگ همین‌جا بررسی می‌شود؛ عکس بعد از دانلود
            val ok = admin && !img && verify(pub, signedText(id, user, ts, kind, body), sig)
            EMsg(id, admin, kind, body, ts, sig, img, ok)
        }
        return user to list
    }

    fun send(ctx: Context, code: String, text: String) {
        post(ctx, "/api/e/send", code, JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8), "application/json")
    }

    fun sendImage(ctx: Context, code: String, jpeg: ByteArray) {
        post(ctx, "/api/e/image", code, jpeg, "image/jpeg")
    }

    fun image(ctx: Context, code: String, id: Long): ByteArray = call(ctx, "GET", "/api/e/image/$id", code)

    /** امضای عکس ادمین: روی هش فایل */
    fun verifyImage(ctx: Context, m: EMsg, user: String, bytes: ByteArray): Boolean =
        m.admin && verify(key(ctx), signedText(m.id, user, m.ts, m.kind, sha256Hex(bytes)), m.sig)
}

/** HTTP/1.1 بی‌رمز روی سوکت خام (فقط برای سرور اضطراری که ادمین بدون گواهی گذاشته) */
object PlainHttp {
    /** prepare: پیش از اتصال روی سوکت اجرا می‌شود (مثلاً protect تا از VPN بیرون برود) */
    fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?, prepare: ((Socket) -> Unit)? = null, timeoutMs: Int = 15000): Pair<Int, ByteArray> {
        val u = URL(url)
        val port = if (u.port > 0) u.port else 80
        val path = (u.path.ifEmpty { "/" }) + (u.query?.let { "?$it" } ?: "")
        Socket().use { s ->
            prepare?.invoke(s)
            s.connect(InetSocketAddress(u.host, port), timeoutMs)
            s.soTimeout = maxOf(timeoutMs, 25000)
            val head = StringBuilder()
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            head.append("Host: ").append(u.host).append(if (u.port > 0 && u.port != 80) ":${u.port}" else "").append("\r\n")
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
