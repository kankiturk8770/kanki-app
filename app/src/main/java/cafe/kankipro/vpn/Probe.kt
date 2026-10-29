package cafe.kankipro.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/*
 * پینگ واقعی هر کانفیگ، بیرون از VPN.
 *
 * قبلاً فقط آدرس سرور پینگ (ICMP) می‌شد: برای کانفیگ‌های تانل آن آدرس سرور ایران است و همیشه جواب
 * می‌دهد، حتی وقتی کانفیگ از سرور پاک شده یا سرور خارج خاموش است (پینگ «الکی»). حالا با خود کانفیگ:
 *   AmneziaWG / WireGuard  اولین بسته‌ی دست‌دادن با کلید همین کانفیگ (WgHello)
 *   KANKI MIX               بسته‌ی پینگ هسته با کلید همین کاربر (KmixHello)
 *   Hysteria2               فرمان ping خود هسته‌ی hysteria (اتصال QUIC و ورود با رمز همین کاربر)
 *   Xray (TCP)              اتصال TCP به پورت همان اینباند
 * فقط وقتی سرور واقعاً این کاربر را بشناسد جواب می‌آید.
 *
 * وقتی VPN وصل است، این بسته‌ها از داخل تونل رد نمی‌شوند (پینگ بقیه‌ی سرورها بالا نشان داده نمی‌شود):
 * سوکت‌ها به شبکه‌ی واقعی گوشی (وای‌فای / دیتا) بسته می‌شوند. برنامه صاحب VPN است و اندروید این اجازه
 * را فقط به صاحب VPN می‌دهد؛ اگر نشد، همان مسیر عادی (جواب درست، فقط پینگ کمی بیشتر).
 */
object Probe {
    /** یک بار انتظار برای جواب؛ سرور سالم در کمتر از این جواب می‌دهد */
    private const val WAIT_MS = 1500
    private const val TRIES = 2

    /** شبکه‌ی واقعی گوشی وقتی یک VPN بالاست؛ null یعنی VPN نیست و مسیر عادی همان شبکه‌ی واقعی است */
    fun underlying(ctx: Context): Network? = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val all = cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
        if (all.none { it.second.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }) return null
        all.filter { (_, c) -> !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
            .sortedByDescending { (_, c) -> c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
            .firstOrNull()?.first
    }.getOrNull()

    /** آدرس سرور، با DNS همان شبکه‌ی واقعی (نه DNS داخل VPN)؛ IPv4 اول */
    private fun resolve(net: Network?, host: String): InetAddress? = runCatching {
        val list = (if (net != null) runCatching { net.getAllByName(host) }.getOrNull() else null) ?: InetAddress.getAllByName(host)
        list.firstOrNull { it is Inet4Address } ?: list.first()
    }.getOrNull()

    private fun udp(net: Network?): DatagramSocket = DatagramSocket().also { s ->
        if (net != null) runCatching { net.bindSocket(s) }
    }

    /**
     * چند بار: بسته‌ها را می‌فرستد و تا WAIT_MS منتظر جوابی می‌ماند که ok آن را قبول کند.
     * زمان رفت و برگشت (میلی‌ثانیه) یا null.
     */
    private fun exchange(s: DatagramSocket, to: InetSocketAddress, make: () -> List<ByteArray>, ok: (ByteArray) -> Boolean): Long? {
        val buf = ByteArray(2048)
        repeat(TRIES) {
            val pkts = make()
            val t0 = System.nanoTime()
            for (p in pkts) s.send(DatagramPacket(p, p.size, to))
            val end = t0 + WAIT_MS * 1_000_000L
            while (true) {
                val left = ((end - System.nanoTime()) / 1_000_000L).toInt()
                if (left <= 0) break
                s.soTimeout = left
                val dp = DatagramPacket(buf, buf.size)
                try { s.receive(dp) } catch (e: SocketTimeoutException) { break }
                val rtt = (System.nanoTime() - t0) / 1_000_000L
                if (ok(buf.copyOf(dp.length))) return rtt.coerceAtLeast(1)
            }
        }
        return null
    }

    /** AmneziaWG / WireGuard: دست‌دادن واقعی با کلید همین کانفیگ */
    fun wg(net: Network?, cfg: String): Long? {
        val w = WgHello.of(cfg) ?: return null
        val ip = resolve(net, w.host) ?: return null
        return runCatching {
            udp(net).use { s ->
                var sent: WgHello.Sent? = null
                exchange(s, InetSocketAddress(ip, w.port), { w.hello().also { sent = it }.let { it.junk + it.packet } }) { p ->
                    sent?.let { w.answers(p, it) } == true
                }
            }
        }.getOrNull()
    }

    /** KANKI MIX: پینگ هسته با کلید همین کاربر */
    fun kmix(net: Network?, cfg: String): Long? {
        val u = runCatching { KmixUri.parse(cfg) }.getOrNull() ?: return null
        val ip = resolve(net, u.host) ?: return null
        return runCatching {
            udp(net).use { s ->
                var sent: KmixHello.Sent? = null
                exchange(s, InetSocketAddress(ip, u.port), { listOf(KmixHello.ping(u.key).also { sent = it }.packet) }) { p ->
                    sent?.let { KmixHello.isPong(p, it) } == true
                }
            }
        }.getOrNull()
    }

    /**
     * microtun: خود هسته یک بار با کلید همین کاربر دست می‌دهد و رفت‌وبرگشت آن را می‌سنجد
     * (`libmtun.so ping`؛ دستگاهی روی سرور ساخته نمی‌شود).
     */
    fun mtun(ctx: Context, cfg: String): Long? {
        val link = cfg.lines().firstOrNull { it.trim().startsWith("URI", ignoreCase = true) && it.contains("=") }?.substringAfter("=")?.trim() ?: return null
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libmtun.so")
        if (!bin.exists()) return null
        return runCatching {
            val p = ProcessBuilder(bin.path, "ping", "--url", link).redirectErrorStream(true).start()
            if (!p.waitFor(8, TimeUnit.SECONDS)) { p.destroy(); return null }
            p.inputStream.bufferedReader().readText().trim().lines().lastOrNull()?.trim()?.toLongOrNull()?.coerceAtLeast(1)
        }.getOrNull()
    }

    /** Xray روی TCP: زمان اتصال به پورت خود اینباند (کمترین از دو بار) */
    fun tcp(net: Network?, host: String, port: Int): Long? {
        val ip = resolve(net, host) ?: return null
        return (1..TRIES).mapNotNull {
            runCatching {
                Socket().use { s ->
                    if (net != null) runCatching { net.bindSocket(s) }
                    val t0 = System.nanoTime()
                    s.connect(InetSocketAddress(ip, port), WAIT_MS)
                    ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1)
                }
            }.getOrNull()
        }.minOrNull()
    }

    private val timeRe = Regex(""""time":\s*"([0-9.]+)(ns|µs|us|ms|s)"""")

    /**
     * Hysteria2: فرمان ping خود هسته. «connected to server» یعنی QUIC و رمز این کاربر قبول شد؛ بعد یک
     * اتصال TCP به 127.0.0.1:1 همان سرور (که فوراً رد می‌شود) از داخل همان اتصال: زمانش یک رفت و برگشت
     * تا سرور است. وقتی VPN بالاست، سوکت هسته از راه fdControlUnixSocket به برنامه داده می‌شود و به
     * شبکه‌ی واقعی گوشی بسته می‌شود.
     */
    fun hysteria(ctx: Context, net: Network?, cfg: String): Long? {
        val uriText = cfg.lines().firstOrNull { it.trim().startsWith("URI", ignoreCase = true) && it.contains("=") }?.substringAfter("=")?.trim()
            ?: cfg.trim()
        val u = runCatching { HyUri.parse(uriText) }.getOrNull() ?: return null
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libhysteria.so")
        if (!bin.exists()) return null
        val ip = resolve(net, u.host) ?: return null
        val server = if (ip is Inet4Address) "${ip.hostAddress}:${u.port}" else "[${ip.hostAddress}]:${u.port}"
        val tag = "kanki-hyfd-" + java.lang.Long.toHexString(System.nanoTime())
        val fd = if (net != null) FdGate(tag, net).also { it.start() } else null
        val cfgFile = File(ctx.cacheDir, "$tag.yaml")
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return try {
            cfgFile.writeText(buildString {
                append("server: ").append(q(server)).append('\n')
                append("auth: ").append(q(u.auth)).append('\n')
                append("tls:\n  sni: ").append(q(u.sni)).append("\n  insecure: ").append(u.insecure).append('\n')
                u.obfsPassword?.let { append("obfs:\n  type: salamander\n  salamander:\n    password: ").append(q(it)).append('\n') }
                if (fd != null) append("quic:\n  sockopts:\n    fdControlUnixSocket: ").append(q("@$tag")).append('\n')
            })
            val pb = ProcessBuilder(bin.path, "ping", "-c", cfgFile.path, "127.0.0.1:1").redirectErrorStream(true)
            pb.environment()["HOME"] = ctx.filesDir.path
            pb.environment()["HYSTERIA_DISABLE_UPDATE_CHECK"] = "1"
            val p = pb.start()
            val out = StringBuilder()
            val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { synchronized(out) { out.append(it).append('\n') } } } }
            reader.start()
            if (!p.waitFor(6, TimeUnit.SECONDS)) p.destroy()
            reader.join(500)
            val text = synchronized(out) { out.toString() }
            if (!text.contains("connected to server")) return null
            val m = timeRe.find(text.substringAfter("connected to server")) ?: return null
            val v = m.groupValues[1].toDoubleOrNull() ?: return null
            val ms = when (m.groupValues[2]) { "s" -> v * 1000; "ms" -> v; "ns" -> v / 1_000_000; else -> v / 1000 }
            ms.toLong().coerceAtLeast(1)
        } catch (e: Exception) {
            null
        } finally {
            fd?.stop()
            cfgFile.delete()
        }
    }

    /**
     * سوکت یونیکس (فضای انتزاعی) که هسته‌ی hysteria سوکت UDP خودش را با SCM_RIGHTS به آن می‌دهد؛
     * این‌جا به شبکه‌ی واقعی گوشی بسته می‌شود و یک بایت جواب می‌رود تا هسته ادامه دهد.
     */
    private class FdGate(private val name: String, private val net: Network) {
        @Volatile private var server: android.net.LocalServerSocket? = null

        fun start() {
            val srv = runCatching { android.net.LocalServerSocket(name) }.getOrNull() ?: return
            server = srv
            Thread({
                while (server != null) {
                    val c = runCatching { srv.accept() }.getOrNull() ?: break
                    runCatching {
                        c.use { sock ->
                            sock.inputStream.read()
                            sock.ancillaryFileDescriptors?.forEach { f ->
                                runCatching { net.bindSocket(f) }
                                runCatching { android.system.Os.close(f) }
                            }
                            sock.outputStream.write(1)
                            sock.outputStream.flush()
                        }
                    }
                }
            }, "kanki-hyfd").apply { isDaemon = true }.start()
        }

        fun stop() {
            val s = server ?: return
            server = null
            // close() روی اندروید accept در حال انتظار را بیدار نمی‌کند: یک اتصال خالی بیدارش می‌کند
            runCatching { android.net.LocalSocket().use { it.connect(android.net.LocalSocketAddress(name)) } }
            runCatching { s.close() }
        }
    }
}
