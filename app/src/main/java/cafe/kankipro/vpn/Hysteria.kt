package cafe.kankipro.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/*
 * اتصال Hysteria2
 * ----------------
 * ۱) هسته‌ی رسمی Hysteria (فایل libhysteria.so که در واقع برنامه‌ی hysteria است) در حالت کلاینت اجرا می‌شود
 *    و یک پراکسی SOCKS5 روی 127.0.0.1 باز می‌کند.
 * ۲) کتابخانه‌ی hev-socks5-tunnel (tun2socks) همه‌ی ترافیک گوشی را از کارت شبکه‌ی VPN به همان پراکسی می‌فرستد.
 * ۳) آی‌پی خود سرور از مسیرهای VPN بیرون گذاشته می‌شود تا ترافیک هسته‌ی Hysteria دور خودش نچرخد.
 * هر دو فایل هنگام ساخت روی گیت‌هاب دانلود/ساخته می‌شوند (فایل build.yml).
 */

/** اتصال به کتابخانه‌ی hev-socks5-tunnel (اسم‌ها باید دقیقاً همین باشند) */
object TProxyService {
    val loaded: Boolean = try {
        System.loadLibrary("hev-socks5-tunnel"); true
    } catch (t: Throwable) {
        false
    }

    external fun TProxyStartService(configPath: String, fd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean
    external fun TProxyGetStats(): LongArray
}

/** لینک hysteria2://رمز@دامنه:پورت/?sni=..&insecure=1&obfs=salamander&obfs-password=..#اسم */
data class HyUri(
    val auth: String,
    val host: String,
    val port: Int,
    val sni: String,
    val insecure: Boolean,
    val obfsPassword: String?,
) {
    companion object {
        fun parse(text: String): HyUri {
            val s = text.trim().replaceFirst("hy2://", "hysteria2://")
            require(s.startsWith("hysteria2://")) { "not a hysteria2 link" }
            val u = java.net.URI(s)
            val host = u.host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotBlank() } ?: error("hysteria2: no server address")
            val q = (u.rawQuery ?: "").split("&").filter { it.contains("=") }.associate {
                val p = it.split("=", limit = 2)
                p[0] to URLDecoder.decode(p[1], "UTF-8")
            }
            return HyUri(
                auth = URLDecoder.decode(u.rawUserInfo ?: "", "UTF-8"),
                host = host,
                port = if (u.port > 0) u.port else 443,
                sni = q["sni"]?.takeIf { it.isNotBlank() } ?: host,
                insecure = q["insecure"] == "1" || q["insecure"] == "true",
                obfsPassword = if (q["obfs"] == "salamander") q["obfs-password"] else null,
            )
        }

        /**
         * متنی که برنامه برای Hysteria2 نگه می‌دارد، شبیه کانفیگ وایرگارد ساخته می‌شود
         * تا تنظیمات DNS و MTU و برنامه‌های مستثنی با همان توابع قبلی روی آن اعمال شوند.
         */
        fun wrap(uri: String): String {
            val u = uri.trim()
            val h = parse(u)
            return "[Interface]\n[Hysteria2]\nEndpoint = ${h.host}:${h.port}\nURI = $u\n"
        }
    }
}

/** بالا و پایین آوردن اتصال Hysteria2 (از VpnEngine صدا زده می‌شود) */
object HyEngine {
    const val IDLE = 0
    const val STARTING = 1
    const val UP = 2
    const val FAILED = 3

    @Volatile internal var state = IDLE
    @Volatile internal var pending: String? = null
    @Volatile internal var lastError: String? = null
    @Volatile internal var service: KankiHyService? = null
    /** وقتی هسته‌ی hysteria خودش (بدون دستور کاربر) از بین برود true می‌شود */
    @Volatile internal var crashed = false

    /** این متن با این موتور (هسته + tun2socks) بالا می‌آید: Hysteria2 یا Xray */
    fun isHy(text: String) = text.contains("hysteria2://") || XrayCfg.isXray(text)

    fun isUp() = state == UP

    /** تا وقتی اتصال برقرار شود یا خطا بدهد صبر می‌کند (روی رشته‌ی پس‌زمینه صدا زده شود) */
    fun up(ctx: Context, text: String) {
        // روی رشته‌ی اصلی صبر کردن باعث قفل شدن می‌شود (سرویس هم روی همان رشته شروع می‌شود)
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "hysteria2: called on main thread" }
        down(ctx)
        pending = text
        lastError = null
        state = STARTING
        val app = ctx.applicationContext
        try {
            app.startService(Intent(app, KankiHyService::class.java).setAction(KankiHyService.START))
        } catch (e: Exception) {
            state = IDLE
            throw IllegalStateException("hysteria2: open the app and connect from there (${e.javaClass.simpleName})")
        }
        val end = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < end) {
            when (state) {
                UP -> return
                FAILED -> { val e = lastError; down(ctx); throw IllegalStateException(e ?: "hysteria2 failed") }
            }
            Thread.sleep(150)
        }
        down(ctx)
        throw IllegalStateException("hysteria2: timeout (server did not answer)")
    }

    fun down(ctx: Context) {
        service?.shutdown()
        service = null
        state = IDLE
    }

    /** (دریافت، ارسال) به بایت */
    fun stats(): Pair<Long, Long>? = if (state != UP || !TProxyService.loaded) null else runCatching {
        val s = TProxyService.TProxyGetStats()   // [tx_packets, tx_bytes, rx_packets, rx_bytes]
        s[1] to s[3]
    }.getOrNull()
}

class KankiHyService : VpnService() {
    companion object {
        const val START = "cafe.kankipro.vpn.HY_START"
    }

    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var proc: Process? = null
    /**
     * شماره‌ی تلاش فعلی برای اتصال. با هر شروع تازه و هر shutdown یکی زیاد می‌شود؛
     * پس تلاشی که شماره‌اش عوض شده یعنی لغو شده (کاربر قطع کرد، زمان تمام شد یا تلاش تازه‌تری شروع شد)
     * و دیگر نباید VPN را بالا بیاورد.
     */
    @Volatile private var gen = 0
    private val log = StringBuilder()

    private fun cancelled(my: Int) = gen != my

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == START) {
            val my = synchronized(this) { ++gen }
            HyEngine.crashed = false
            HyEngine.service = this
            val text = HyEngine.pending
            Thread({ doStart(text, my) }, "kanki-hy2").start()
        } else if (intent?.action == VpnService.SERVICE_INTERFACE) {
            // اندروید خودش («همیشه‌روشن») این سرویس را بالا آورده
            AlwaysOn.start(this)
        }
        return START_NOT_STICKY
    }

    private fun doStart(text: String?, my: Int) {
        try {
            start(text ?: error("hysteria2: no config"), my)
        } catch (t: Throwable) {
            // تلاش لغو‌شده را کسی که لغوش کرده (shutdown) جمع کرده؛ خطا گزارش نمی‌شود
            if (cancelled(my)) return
            HyEngine.lastError = t.message ?: t.javaClass.simpleName
            HyEngine.state = HyEngine.FAILED
            shutdown()
        }
    }

    private fun start(text: String, my: Int) {
        val lines = text.lines().map { it.trim() }
        fun v(k: String) = lines.firstOrNull { it.startsWith(k, ignoreCase = true) && it.contains("=") }?.substringAfter("=")?.trim()
        if (XrayCfg.isXray(text)) return startXray(v("URI") ?: error("xray: link missing"), lines, my)
        val uri = HyUri.parse(v("URI") ?: error("hysteria2: link missing"))
        val dns = (v("DNS") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        val mtu = v("MTU")?.toIntOrNull()?.coerceIn(1280, 9000) ?: 1400
        val excluded = (v("ExcludedApplications") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }

        if (!TProxyService.loaded) error("hysteria2: tun2socks library is missing in this build")
        val bin = File(applicationInfo.nativeLibraryDir, "libhysteria.so")
        if (!bin.exists()) error("hysteria2: core is missing in this build")

        // آی‌پی سرور همین‌جا (قبل از بالا آمدن VPN) پیدا می‌شود؛ هسته فقط با آی‌پی کار می‌کند
        val addrs = InetAddress.getAllByName(uri.host)
        val ip = addrs.firstOrNull { it is Inet4Address } ?: addrs.first()
        val server = if (ip is Inet4Address) "${ip.hostAddress}:${uri.port}" else "[${ip.hostAddress}]:${uri.port}"
        val socksPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val hyCfg = buildString {
            append("server: ").append(q(server)).append('\n')
            append("auth: ").append(q(uri.auth)).append('\n')
            append("tls:\n  sni: ").append(q(uri.sni)).append("\n  insecure: ").append(uri.insecure).append('\n')
            uri.obfsPassword?.let { append("obfs:\n  type: salamander\n  salamander:\n    password: ").append(q(it)).append('\n') }
            append("fastOpen: true\n")
            append("socks5:\n  listen: ").append(q("127.0.0.1:$socksPort")).append("\n  disableUDP: false\n")
        }
        val hyFile = File(filesDir, "hy2.yaml").apply { writeText(hyCfg) }

        val pb = ProcessBuilder(listOf(bin.path, "client", "-c", hyFile.path))
            .directory(filesDir).redirectErrorStream(true)
        pb.environment()["HOME"] = filesDir.path
        pb.environment()["HYSTERIA_DISABLE_UPDATE_CHECK"] = "1"
        // پیدا کردن آی‌پی سرور (بالا) ممکن است طول بکشد؛ اگر در این فاصله لغو شده، هسته‌ای اجرا نمی‌شود
        val p = synchronized(this) {
            if (cancelled(my)) error("hysteria2: cancelled")
            killStaleCores()
            pb.start().also { proc = it }
        }
        Thread({
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(log) {
                        log.append(line).append('\n')
                        if (log.length > 6000) log.delete(0, log.length - 4000)
                    }
                }
            }
        }, "kanki-hy2-log").start()

        // اگر هسته بعد از اتصال از بین رفت (مثلاً اندروید پروسه را کشت)، تونل بسته و وضعیت گزارش می‌شود
        Thread({
            runCatching { p.waitFor() }
            if (!cancelled(my) && HyEngine.state == HyEngine.UP) {
                HyEngine.crashed = true
                HyEngine.lastError = "hysteria core exited. " + lastLog()
                shutdown()
                HyEngine.state = HyEngine.IDLE
            }
        }, "kanki-hy2-mon").start()

        // صبر تا هسته به سرور وصل شود و پراکسی محلی را باز کند
        val end = System.currentTimeMillis() + 15_000
        var ready = false
        while (System.currentTimeMillis() < end) {
            if (cancelled(my)) error("hysteria2: cancelled")
            if (!p.isAlive) error("hysteria2: " + lastLog())
            ready = runCatching { Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", socksPort), 300) }; true }.getOrDefault(false)
            if (ready) break
            Thread.sleep(250)
        }
        if (!ready) error("hysteria2: server did not answer. " + lastLog())
        tunUp("Kanki · Hysteria2", ip, socksPort, mtu, dns, excluded, included(lines), my)
    }

    /** برنامه‌هایی که فقط آن‌ها از VPN رد می‌شوند (حالت «فقط بازی‌ها»)؛ خالی = همه */
    private fun included(lines: List<String>): List<String> =
        (lines.firstOrNull { it.startsWith("IncludedApplications", ignoreCase = true) && it.contains("=") }?.substringAfter("=") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** کارت شبکه‌ی VPN و tun2socks به پراکسی SOCKS محلی هسته (مشترک بین Hysteria2 و Xray) */
    private fun tunUp(session: String, ip: InetAddress, socksPort: Int, mtu: Int, dns: List<String>, excluded: List<String>, included: List<String>, my: Int) {
        val b = Builder()
            .setSession(session)
            .setMtu(mtu)
            .addAddress("198.18.0.1", 32)
        if (ip is Inet4Address) routesExcluding(ip.address).forEach { (net, len) -> b.addRoute(net, len) }
        else b.addRoute("0.0.0.0", 0)
        dns.forEach { d -> runCatching { b.addDnsServer(d) } }
        // اندروید اجازه نمی‌دهد هر دو فهرست با هم باشند: «فقط این برنامه‌ها» بر «به‌جز این‌ها» مقدم است
        if (included.isNotEmpty()) included.forEach { pkg -> runCatching { b.addAllowedApplication(pkg) } }
        else excluded.forEach { pkg -> runCatching { b.addDisallowedApplication(pkg) } }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)

        val hevCfg = "tunnel:\n  mtu: $mtu\n  ipv4: 198.18.0.1\nsocks5:\n  port: $socksPort\n  address: 127.0.0.1\n  udp: 'udp'\nmisc:\n  task-stack-size: 81920\n"
        val hevFile = File(filesDir, "hev.yaml").apply { writeText(hevCfg) }

        // بررسی لغو و بالا آوردن VPN با هم (زیر یک قفل با shutdown) انجام می‌شود؛
        // پس اگر زمان تمام شده یا کاربر قطع کرده، VPN دیگر بی‌صدا وصل نمی‌شود
        synchronized(this) {
            if (cancelled(my)) error("cancelled")
            val fd = b.establish() ?: error("VPN permission is missing")
            tun = fd
            if (!TProxyService.TProxyStartService(hevFile.path, fd.fd)) error("tun2socks did not start")
            HyEngine.state = HyEngine.UP
        }
    }

    /** Xray-core: مثل Hysteria2، فقط با هسته و کانفیگ دیگر */
    private fun startXray(link: String, lines: List<String>, my: Int) {
        fun v(k: String) = lines.firstOrNull { it.startsWith(k, ignoreCase = true) && it.contains("=") }?.substringAfter("=")?.trim()
        val dns = (v("DNS") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        val mtu = v("MTU")?.toIntOrNull()?.coerceIn(1280, 9000) ?: 1400
        val excluded = (v("ExcludedApplications") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (!TProxyService.loaded) error("xray: tun2socks library is missing in this build")
        val bin = File(applicationInfo.nativeLibraryDir, "libxray.so")
        if (!bin.exists()) error("xray: core is missing in this build")

        val (host, _) = XrayCfg.endpoint(link)
        val addrs = InetAddress.getAllByName(host)
        val ip = addrs.firstOrNull { it is Inet4Address } ?: addrs.first()
        val socksPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val cfgFile = File(filesDir, "xray.json").apply { writeText(XrayCfg.config(link, ip.hostAddress ?: host, socksPort)) }

        val pb = ProcessBuilder(listOf(bin.path, "run", "-c", cfgFile.path)).directory(filesDir).redirectErrorStream(true)
        pb.environment()["HOME"] = filesDir.path
        pb.environment()["XRAY_LOCATION_ASSET"] = filesDir.path
        val p = synchronized(this) {
            if (cancelled(my)) error("xray: cancelled")
            killStaleCores()
            pb.start().also { proc = it }
        }
        Thread({
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(log) {
                        log.append(line).append('\n')
                        if (log.length > 6000) log.delete(0, log.length - 4000)
                    }
                }
            }
        }, "kanki-xray-log").start()
        Thread({
            runCatching { p.waitFor() }
            if (!cancelled(my) && HyEngine.state == HyEngine.UP) {
                HyEngine.crashed = true
                HyEngine.lastError = "xray core exited. " + lastLog()
                shutdown()
                HyEngine.state = HyEngine.IDLE
            }
        }, "kanki-xray-mon").start()

        val end = System.currentTimeMillis() + 10_000
        var ready = false
        while (System.currentTimeMillis() < end) {
            if (cancelled(my)) error("xray: cancelled")
            if (!p.isAlive) error("xray: " + lastLog())
            ready = runCatching { Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", socksPort), 300) }; true }.getOrDefault(false)
            if (ready) break
            Thread.sleep(200)
        }
        if (!ready) error("xray: the core did not start. " + lastLog())
        tunUp("Kanki · Xray", ip, socksPort, mtu, dns, excluded, included(lines), my)
    }

    /**
     * اگر برنامه ناگهانی بسته شده باشد، هسته‌ی hysteria قبلی می‌تواند یتیم بماند و بی‌صدا به سرور
     * بسته بفرستد (و باتری بخورد). قبل از اجرای هسته‌ی جدید، هر هسته‌ی قدیمیِ همین برنامه کشته می‌شود.
     */
    private fun killStaleCores() {
        val me = android.os.Process.myPid()
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            if (pid == me) return@forEach
            runCatching {
                val cmd = File(d, "cmdline").readText()
                if (cmd.contains("libhysteria.so") || cmd.contains("libxray.so")) android.os.Process.killProcess(pid)
            }
        }
    }

    private fun lastLog(): String = synchronized(log) {
        log.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(3).joinToString(" | ").take(400)
    }

    /** همه‌ی ترافیک IPv4 به‌جز آی‌پی خود سرور (۳۲ مسیر که روی‌هم همه‌چیز را می‌پوشانند) */
    private fun routesExcluding(ip: ByteArray): List<Pair<String, Int>> {
        val x = ((ip[0].toLong() and 0xff) shl 24) or ((ip[1].toLong() and 0xff) shl 16) or
            ((ip[2].toLong() and 0xff) shl 8) or (ip[3].toLong() and 0xff)
        return (1..32).map { len ->
            val mask = (0xffffffffL shl (32 - len)) and 0xffffffffL
            val net = (x xor (1L shl (32 - len))) and mask
            "${(net shr 24) and 0xff}.${(net shr 16) and 0xff}.${(net shr 8) and 0xff}.${net and 0xff}" to len
        }
    }

    @Synchronized
    fun shutdown() {
        gen++   // هر تلاش در حال اجرا لغو می‌شود
        if (TProxyService.loaded) runCatching { TProxyService.TProxyStopService() }
        runCatching { tun?.close() }
        tun = null
        runCatching { proc?.destroy() }
        proc = null
        if (HyEngine.service === this) HyEngine.service = null
        runCatching { stopSelf() }
    }

    override fun onRevoke() {
        shutdown()
        HyEngine.state = HyEngine.IDLE
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
}
