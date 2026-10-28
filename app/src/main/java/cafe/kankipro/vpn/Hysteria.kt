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
    /** اتصال فعلی حالت DNS کانکی است (بدون هسته؛ یا با هسته‌ی مستقیم وقتی برنامه‌ها بسته‌اند) */
    @Volatile internal var dnsMode = false
    /** حالت DNS با «فقط برنامه‌های انتخابی» (ترافیک برنامه‌های مجاز از tun2socks رد می‌شود) */
    @Volatile internal var dnsGate = false
    /** وقتی هسته‌ی hysteria خودش (بدون دستور کاربر) از بین برود true می‌شود */
    @Volatile internal var crashed = false
    /** اتصال فعلی KANKI MIX است (هسته‌ی Go داخل همین پروسه) */
    @Volatile internal var kmix = false
    /** حالت DNS بدون دروازه: شمارنده‌های کل گوشی در لحظه‌ی اتصال (سرعت واقعی اینترنت گوشی) */
    @Volatile internal var dnsBase: Pair<Long, Long>? = null

    /** این متن با این موتور بالا می‌آید: Hysteria2، Xray، DNS کانکی یا KANKI MIX */
    fun isHy(text: String) = text.contains("hysteria2://") || XrayCfg.isXray(text) || KankiDns.isDns(text) || KmixUri.isKmix(text) || KcoreCfg.isKcore(text)

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
                // down() از جای دیگر (قطع کاربر یا تلاش تازه): فوراً تمام، نه ۲۵ ثانیه انتظار
                IDLE -> throw IllegalStateException("cancelled")
            }
            Thread.sleep(150)
        }
        down(ctx)
        throw IllegalStateException("hysteria2: timeout (server did not answer)")
    }

    fun down(ctx: Context) {
        // سرویسی که هنوز onStartCommand نگرفته، با pending خالی خودش کنار می‌رود (VPN بی‌صاحب بالا نمی‌آید)
        pending = null
        service?.shutdown()
        service = null
        state = IDLE
    }

    /**
     * (دریافت، ارسال) به بایت از شروع اتصال.
     * حالت DNS بدون دروازه: فقط DNS از VPN رد می‌شود، پس ترافیک واقعی برنامه‌ها مستقیم است؛ سرعت و مصرف
     * از شمارنده‌های کل گوشی (TrafficStats) از لحظه‌ی اتصال حساب می‌شود (قبلاً فقط چند کیلوبایت DNS بود).
     */
    fun stats(): Pair<Long, Long>? = when {
        state != UP -> null
        kmix -> KmixCore.stats()
        dnsMode && !dnsGate -> deviceStats()?.let { now ->
            val b = dnsBase ?: now.also { dnsBase = it }
            (now.first - b.first).coerceAtLeast(0) to (now.second - b.second).coerceAtLeast(0)
        }
        !TProxyService.loaded -> null
        else -> runCatching {
            val s = TProxyService.TProxyGetStats()   // [tx_packets, tx_bytes, rx_packets, rx_bytes]
            s[1] to s[3]
        }.getOrNull()
    }

    /** دریافت و ارسال کل گوشی (همه‌ی شبکه‌ها)؛ null اگر گوشی پشتیبانی نکند */
    fun deviceStats(): Pair<Long, Long>? {
        val rx = android.net.TrafficStats.getTotalRxBytes()
        val tx = android.net.TrafficStats.getTotalTxBytes()
        return if (rx == android.net.TrafficStats.UNSUPPORTED.toLong() || tx == android.net.TrafficStats.UNSUPPORTED.toLong()) null else rx to tx
    }
}

class KankiHyService : VpnService() {
    companion object {
        const val START = "cafe.kankipro.vpn.HY_START"
        /** MTU پیش‌فرض کارت شبکه برای tun2socks (فقط داخل گوشی است؛ بزرگ‌تر یعنی بسته‌ی کمتر و سرعت بیشتر) */
        const val TUN_MTU = 8500
        /** MTU کارت شبکه‌ی KANKI MIX؛ باید با tunMTU در kmix-core/mobile/mobile.go یکی باشد */
        const val KMIX_MTU = 1280
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
            val text = HyEngine.pending
            if (text == null) { runCatching { stopSelf() }; return START_NOT_STICKY }
            HyEngine.crashed = false
            HyEngine.service = this
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
        if (KcoreCfg.isKcore(text)) return startKcore(v("URI") ?: error("kcore: link missing"), lines, my)
        if (KankiDns.isDns(text)) return startDns(lines, my)
        if (KmixUri.isKmix(text)) return startKmix(v("URI") ?: error("kanki mix: link missing"), lines, my)
        val uri = HyUri.parse(v("URI") ?: error("hysteria2: link missing"))
        val dns = (v("DNS") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        val mtu = v("MTU")?.toIntOrNull()?.coerceIn(1280, 9000) ?: TUN_MTU
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
        tunUp("Kanki · Hysteria2", ip, socksPort, mtu, dns, excluded, included(lines), blockOthers(lines), my)
    }

    /** «بقیه‌ی برنامه‌ها اینترنت نداشته باشند» (فقط با IncludedApplications معنا دارد) */
    private fun blockOthers(lines: List<String>): Boolean =
        lines.any { it.startsWith("BlockOthers", ignoreCase = true) && it.substringAfter("=", "").trim() == "1" }

    /** برنامه‌هایی که فقط آن‌ها از VPN رد می‌شوند (حالت «فقط بازی‌ها»)؛ خالی = همه */
    private fun included(lines: List<String>): List<String> =
        (lines.firstOrNull { it.startsWith("IncludedApplications", ignoreCase = true) && it.contains("=") }?.substringAfter("=") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * کارت شبکه‌ی VPN و tun2socks به پراکسی SOCKS محلی هسته (مشترک بین Hysteria2 و Xray).
     * block: فقط برنامه‌های included اینترنت دارند و بقیه هیچ (با AppGate، اندروید ۱۰ به بالا).
     */
    private fun tunUp(session: String, ip: InetAddress, socksPort: Int, mtu: Int, dns: List<String>, excluded: List<String>, included: List<String>, block: Boolean, my: Int) {
        val gateMode = block && included.isNotEmpty() && AppGate.supported
        val b = Builder()
            .setSession(session)
            .setMtu(mtu)
            .addAddress("198.18.0.1", 32)
        if (ip is Inet4Address) routesExcluding(ip.address).forEach { (net, len) -> b.addRoute(net, len) }
        else b.addRoute("0.0.0.0", 0)
        // IPv6 بدون آدرس: هیچ برنامه‌ای از راه IPv6 بیرون از تونل نمی‌رود و فوری به IPv4 برمی‌گردد
        if (ip is Inet4Address) runCatching { b.addRoute("::", 0) }
        dns.forEach { d -> runCatching { b.addDnsServer(d) } }
        // اندروید اجازه نمی‌دهد هر دو فهرست با هم باشند: «فقط این برنامه‌ها» بر «به‌جز این‌ها» مقدم است.
        // در حالت دروازه همه‌ی برنامه‌ها داخل VPN می‌مانند تا AppGate بقیه را ببندد.
        if (!gateMode) {
            if (included.isNotEmpty()) included.forEach { pkg -> runCatching { b.addAllowedApplication(pkg) } }
            else excluded.forEach { pkg -> runCatching { b.addDisallowedApplication(pkg) } }
        }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)

        val hevFile = writeHev(mtu, "198.18.0.1", socksPort)

        // بررسی لغو و بالا آوردن VPN با هم (زیر یک قفل با shutdown) انجام می‌شود؛
        // پس اگر زمان تمام شده یا کاربر قطع کرده، VPN دیگر بی‌صدا وصل نمی‌شود
        synchronized(this) {
            if (cancelled(my)) error("cancelled")
            val fd = b.establish() ?: error("VPN permission is missing")
            tun = fd
            if (gateMode) {
                // خود برنامه (پینگ و بررسی سلامت تونل) هم مجاز است
                val g = AppGate(this, fd, AppGate.uidsOf(this, included) + android.os.Process.myUid())
                gate = g
                if (!TProxyService.TProxyStartService(hevFile.path, g.innerFd)) error("tun2socks did not start")
                g.start()
            } else {
                if (!TProxyService.TProxyStartService(hevFile.path, fd.fd)) error("tun2socks did not start")
            }
            HyEngine.state = HyEngine.UP
        }
    }

    /** تنظیمات hev-socks5-tunnel: MTU بزرگ کارت داخلی (سربار کمتر، سرعت بیشتر) */
    private fun writeHev(mtu: Int, ipv4: String, socksPort: Int): File {
        val hevCfg = "tunnel:\n  mtu: $mtu\n  ipv4: $ipv4\nsocks5:\n  port: $socksPort\n  address: 127.0.0.1\n  udp: 'udp'\n" +
            "misc:\n  task-stack-size: 81920\n"
        return File(filesDir, "hev.yaml").apply { writeText(hevCfg) }
    }

    @Volatile private var gate: AppGate? = null

    @Volatile private var dnsFwd: KankiDns.Forwarder? = null

    /**
     * حالت DNS کانکی.
     * عادی: VPN کوچکی که فقط DNS را به سرور کانکی می‌فرستد (بدون هسته).
     * با «فقط برنامه‌های انتخابی» (BlockOthers): همه‌ی برنامه‌ها وارد VPN می‌شوند؛ برنامه‌های انتخابی
     * DNS را از کانکی می‌گیرند و بقیه‌ی ترافیکشان از tun2socks و هسته‌ی Xray (خروجی مستقیم freedom)
     * مستقیم به اینترنت می‌رود؛ برنامه‌های دیگر تا خاموش شدن DNS اینترنت ندارند.
     */
    private fun startDns(lines: List<String>, my: Int) {
        val excluded = (lines.firstOrNull { it.startsWith("ExcludedApplications", ignoreCase = true) && it.contains("=") }?.substringAfter("=") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val included = included(lines)
        val targets = lines.filter { it.startsWith("Server", ignoreCase = true) && it.contains("=") }.mapNotNull {
            val p = it.substringAfter("=").trim().split(Regex("\\s+"))
            runCatching { KankiDns.Target(InetAddress.getByName(p[0]), p.getOrElse(1) { "-" }) }.getOrNull()
        }
        if (targets.isEmpty()) error("dns: no DNS server in the subscription")
        val bin = File(applicationInfo.nativeLibraryDir, "libxray.so")
        val gateMode = blockOthers(lines) && included.isNotEmpty() && AppGate.supported && TProxyService.loaded && bin.exists()
        KankiDns.rx.set(0)
        KankiDns.tx.set(0)
        HyEngine.dnsBase = HyEngine.deviceStats()
        if (gateMode) {
            // هسته‌ی Xray فقط به‌عنوان خروجی مستقیم (بدون سرور): SOCKS محلی ← اینترنت خود گوشی
            val socksPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val cfgFile = File(filesDir, "xray-direct.json").apply { writeText(XrayCfg.directConfig(socksPort)) }
            launchCore(listOf(bin.path, "run", "-c", cfgFile.path), "xray", socksPort, my, 10_000)
            val fd = synchronized(this) {
                if (cancelled(my)) error("dns: cancelled")
                KankiDns.buildGateTun(this, TUN_MTU).also { tun = it }
            }
            val f = KankiDns.Forwarder(this, fd, targets)
            dnsFwd = f
            f.reregister()
            val hevFile = writeHev(TUN_MTU, KankiDns.GATE_IP, socksPort)
            synchronized(this) {
                if (cancelled(my)) error("dns: cancelled")
                val g = AppGate(this, fd, AppGate.uidsOf(this, included), f)
                gate = g
                if (!TProxyService.TProxyStartService(hevFile.path, g.innerFd)) error("tun2socks did not start")
                g.start()
            }
            keepRegistered(f, my)
            synchronized(this) {
                if (cancelled(my)) error("dns: cancelled")
                HyEngine.dnsMode = true
                HyEngine.dnsGate = true
                HyEngine.state = HyEngine.UP
            }
            return
        }
        // اندروید ۹ و قدیمی‌تر صاحب اتصال‌ها را نمی‌گوید: فقط برنامه‌های انتخابی DNS کانکی را می‌گیرند
        val fd = synchronized(this) {
            if (cancelled(my)) error("dns: cancelled")
            KankiDns.buildTun(this, excluded, included).also { tun = it }
        }
        val f = KankiDns.Forwarder(this, fd, targets)
        dnsFwd = f
        f.reregister()
        Thread({ f.run() }, "kanki-dns").start()
        keepRegistered(f, my)
        synchronized(this) {
            if (cancelled(my)) error("dns: cancelled")
            HyEngine.dnsMode = true
            HyEngine.state = HyEngine.UP
        }
    }

    /** the address stays allowed for hours on the server; register again every 20 minutes anyway */
    private fun keepRegistered(f: KankiDns.Forwarder, my: Int) {
        Thread({
            while (!cancelled(my) && dnsFwd === f) {
                runCatching { Thread.sleep(20 * 60_000L) }.onFailure { return@Thread }
                if (!cancelled(my) && dnsFwd === f) f.reregister()
            }
        }, "kanki-dns-reg").start()
    }

    /**
     * یک هسته (Xray) را اجرا می‌کند، خروجی‌اش را برای گزارش خطا نگه می‌دارد، اگر بعداً مرد تونل را می‌بندد
     * و صبر می‌کند تا پراکسی SOCKS محلی‌اش باز شود.
     */
    private fun launchCore(cmd: List<String>, tag: String, socksPort: Int, my: Int, waitMs: Long): Process {
        val pb = ProcessBuilder(cmd).directory(filesDir).redirectErrorStream(true)
        pb.environment()["HOME"] = filesDir.path
        pb.environment()["XRAY_LOCATION_ASSET"] = filesDir.path
        val p = synchronized(this) {
            if (cancelled(my)) error("$tag: cancelled")
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
        }, "kanki-$tag-log").start()
        Thread({
            runCatching { p.waitFor() }
            if (!cancelled(my) && HyEngine.state == HyEngine.UP) {
                HyEngine.crashed = true
                HyEngine.lastError = "$tag core exited. " + lastLog()
                shutdown()
                HyEngine.state = HyEngine.IDLE
            }
        }, "kanki-$tag-mon").start()
        val end = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < end) {
            if (cancelled(my)) error("$tag: cancelled")
            if (!p.isAlive) error("$tag: " + lastLog())
            val ready = runCatching { Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", socksPort), 300) }; true }.getOrDefault(false)
            if (ready) return p
            Thread.sleep(200)
        }
        error("$tag: the core did not start. " + lastLog())
    }

    /** Xray-core: مثل Hysteria2، فقط با هسته و کانفیگ دیگر */
    private fun startXray(link: String, lines: List<String>, my: Int) {
        fun v(k: String) = lines.firstOrNull { it.startsWith(k, ignoreCase = true) && it.contains("=") }?.substringAfter("=")?.trim()
        val dns = (v("DNS") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        val mtu = v("MTU")?.toIntOrNull()?.coerceIn(1280, 9000) ?: TUN_MTU
        val excluded = (v("ExcludedApplications") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (!TProxyService.loaded) error("xray: tun2socks library is missing in this build")
        val bin = File(applicationInfo.nativeLibraryDir, "libxray.so")
        if (!bin.exists()) error("xray: core is missing in this build")

        val (host, _) = XrayCfg.endpoint(link)
        val addrs = InetAddress.getAllByName(host)
        val ip = addrs.firstOrNull { it is Inet4Address } ?: addrs.first()
        val socksPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val cfgFile = File(filesDir, "xray.json").apply { writeText(XrayCfg.config(link, ip.hostAddress ?: host, socksPort)) }
        launchCore(listOf(bin.path, "run", "-c", cfgFile.path), "xray", socksPort, my, 10_000)
        tunUp("Kanki · Xray", ip, socksPort, mtu, dns, excluded, included(lines), blockOthers(lines), my)
    }

    /**
     * Game-FEC، HQ-QUIC و FakeTCP: هسته‌ی libkcore.so یک پراکسی SOCKS5 باز می‌کند (مثل Xray) و hev-socks5-tunnel
     * ترافیک گوشی را به آن می‌دهد. هسته به آی‌پی‌ای وصل می‌شود که از مسیرهای VPN بیرون گذاشته شده.
     * FakeTCP بسته‌ی خام TCP می‌سازد: هسته با su (روت) اجرا می‌شود.
     */
    private fun startKcore(link: String, lines: List<String>, my: Int) {
        fun v(k: String) = lines.firstOrNull { it.startsWith(k, ignoreCase = true) && it.contains("=") }?.substringAfter("=")?.trim()
        val dns = (v("DNS") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        val mtu = v("MTU")?.toIntOrNull()?.coerceIn(1280, 9000) ?: TUN_MTU
        val excluded = (v("ExcludedApplications") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (!TProxyService.loaded) error("kcore: tun2socks library is missing in this build")
        val bin = File(applicationInfo.nativeLibraryDir, "libkcore.so")
        if (!bin.exists()) error("kcore: core is missing in this build")

        val kind = KcoreCfg.kind(link)
        val (host, _) = KcoreCfg.endpoint(link)
        val addrs = InetAddress.getAllByName(host)
        val ip = addrs.firstOrNull { it is Inet4Address } ?: addrs.first()
        var url = KcoreCfg.withHost(link, ip.hostAddress ?: host)
        // حالت گیمینگ: هر بسته‌ی بازی سه بار (پنل پیش‌فرض را می‌دهد؛ Copies در کانفیگ آن را عوض می‌کند)
        v("Copies")?.toIntOrNull()?.let { url = KcoreCfg.withCopies(url, it) }
        val socksPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val args = listOf(bin.path, "client", "--url", url, "--socks", "127.0.0.1:$socksPort")
        val cmd = if (kind == "faketcp") listOf("su", "-c", args.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }) else args
        val name = KcoreCfg.title(KcoreCfg.PROTOS.firstOrNull { it.second == kind }?.first ?: "kgame")
        launchCore(cmd, "kcore", socksPort, my, 15_000)
        tunUp("Kanki · $name", ip, socksPort, mtu, dns, excluded, included(lines), blockOthers(lines), my)
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
                if (cmd.contains("libhysteria.so") || cmd.contains("libxray.so") || cmd.contains("libkmix.so") || cmd.contains("libkcore.so")) android.os.Process.killProcess(pid)
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

    /**
     * KANKI MIX: کارت شبکه‌ی VPN مستقیم به هسته‌ی Go (برنامه‌ی جدای libkmix.so) داده می‌شود؛ بدون tun2socks.
     * آی‌پی سرور از مسیرهای VPN بیرون است تا سوکت‌های خود هسته دور خودشان نچرخند.
     * MTU ثابت ۱۲۸۰ است (باید با tunMTU هسته یکی باشد): بسته‌ی رمزشده هیچ‌وقت روی موبایل تکه نمی‌شود.
     */
    private fun startKmix(link: String, lines: List<String>, my: Int) {
        fun v(k: String) = lines.firstOrNull { it.startsWith(k, ignoreCase = true) && it.contains("=") }?.substringAfter("=")?.trim()
        val u = KmixUri.parse(link)
        val dns = (v("DNS") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        val excluded = (v("ExcludedApplications") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val included = included(lines)
        val gaming = v("Gaming")?.let { it == "1" } ?: u.gaming

        val addrs = InetAddress.getAllByName(u.host)
        val ip = addrs.firstOrNull { it is Inet4Address } ?: addrs.first()
        val server = if (ip is Inet4Address) "${ip.hostAddress}:${u.pickPort()}" else "[${ip.hostAddress}]:${u.pickPort()}"

        val b = Builder()
            .setSession("Kanki · KANKI MIX")
            .setMtu(KMIX_MTU)
            .addAddress("10.77.0.2", 32)
        if (ip is Inet4Address) routesExcluding(ip.address).forEach { (net, len) -> b.addRoute(net, len) }
        else b.addRoute("0.0.0.0", 0)
        if (ip is Inet4Address) runCatching { b.addRoute("::", 0) }
        dns.forEach { d -> runCatching { b.addDnsServer(d) } }
        if (included.isNotEmpty()) included.forEach { pkg -> runCatching { b.addAllowedApplication(pkg) } }
        else excluded.forEach { pkg -> runCatching { b.addDisallowedApplication(pkg) } }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)

        val bin = KmixCore.binary(this)
        if (!bin.exists()) error("kanki mix: core is missing in this build")
        val fd = synchronized(this) {
            if (cancelled(my)) error("kanki mix: cancelled")
            killStaleCores()
            (b.establish() ?: error("VPN permission is missing")).also { tun = it }
        }
        // تا ~۲۲ ثانیه: هسته بالا می‌آید و سرور کلید این کاربر را می‌سنجد (کلید اشتباه یا سرور خاموش = خطای روشن)
        KmixCore.start(bin, fd, server, u.key, u.fecData, u.fecParity, gaming) { why ->
            // هسته بعد از اتصال خودش از بین رفت (مثلاً اندروید پروسه‌اش را کشت): تونل بسته و وضعیت گزارش می‌شود
            if (!cancelled(my) && HyEngine.state == HyEngine.UP) {
                HyEngine.crashed = true
                HyEngine.lastError = why
                shutdown()
                HyEngine.state = HyEngine.IDLE
            }
        }
        synchronized(this) {
            if (cancelled(my)) { KmixCore.stop(); error("kanki mix: cancelled") }
            HyEngine.kmix = true
            HyEngine.state = HyEngine.UP
        }
    }

    @Synchronized
    fun shutdown() {
        gen++   // هر تلاش در حال اجرا لغو می‌شود
        // هسته‌ی KANKI MIX حتی وقتی هنوز منتظر جواب سرور است (kmix هنوز false) بسته می‌شود، تا آن تلاش فوراً تمام شود
        // روی رشته‌ی اصلی (onRevoke / onDestroy) نه: stop تا چند ثانیه منتظر جواب هسته می‌ماند
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) KmixCore.stopAsync()
        else KmixCore.stop()
        HyEngine.kmix = false
        dnsFwd?.stop()
        dnsFwd = null
        HyEngine.dnsMode = false
        HyEngine.dnsGate = false
        if (TProxyService.loaded) runCatching { TProxyService.TProxyStopService() }
        gate?.stop()
        gate = null
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
