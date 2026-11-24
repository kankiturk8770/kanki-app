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
    /** اتصال فعلی WireGuard/AmneziaWG گیمینگ است (هسته‌ی libawg.so)؛ دریافت و ارسال از گزارش‌های خود هسته */
    @Volatile internal var awg = false
    @Volatile internal var coreRx = 0L
    @Volatile internal var coreTx = 0L
    /** حالت DNS بدون دروازه: شمارنده‌های کل گوشی در لحظه‌ی اتصال (سرعت واقعی اینترنت گوشی) */
    @Volatile internal var dnsBase: Pair<Long, Long>? = null

    /** این متن با این موتور بالا می‌آید: Hysteria2، Xray، DNS کانکی یا KANKI MIX */
    fun isHy(text: String) = text.contains("hysteria2://") || XrayCfg.isXray(text) || KankiDns.isDns(text) || KmixUri.isKmix(text) || isAwgGated(text)

    /**
     * WireGuard / AmneziaWG در گیمینگ (خط GameOnly): موتور خود کتابخانه‌ی امنزیا کارت VPN را خودش می‌سازد و دروازه نمی‌تواند جلویش
     * بنشیند؛ پس در گیمینگ همین سرویس با هسته‌ی جدای libawg.so (awg-core، همان amneziawg-go کتابخانه) وصل می‌شود.
     */
    fun isAwgGated(text: String) = text.contains("[Peer]", ignoreCase = true) && !KmixUri.isKmix(text) &&
        text.lines().any { (it.trim().startsWith("GameOnly", ignoreCase = true) || it.trim().startsWith(AWG_OWN, ignoreCase = true)) && it.contains("=") }

    /** این خط یعنی WireGuard/AmneziaWG بی دروازه هم با هسته‌ی خود کانکی (libawg.so) بالا بیاید (وقتی موتور کتابخانه‌ی امنزیا رد کرد) */
    const val AWG_OWN = "KankiCore"

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
        awg -> coreRx to coreTx
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
        if (KankiDns.isDns(text)) return startDns(lines, my)
        if (KmixUri.isKmix(text)) return startKmix(v("URI") ?: error("kanki mix: link missing"), lines, my)
        if (HyEngine.isAwgGated(text)) return startAwg(text, my)
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
        val auth = SocksAuth.random()
        val socksPort = auth.freePort()

        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val hyCfg = buildString {
            append("server: ").append(q(server)).append('\n')
            append("auth: ").append(q(uri.auth)).append('\n')
            append("tls:\n  sni: ").append(q(uri.sni)).append("\n  insecure: ").append(uri.insecure).append('\n')
            uri.obfsPassword?.let { append("obfs:\n  type: salamander\n  salamander:\n    password: ").append(q(it)).append('\n') }
            append("fastOpen: true\n")
            append("socks5:\n  listen: ").append(q("${auth.host}:$socksPort"))
                .append("\n  username: ").append(q(auth.user)).append("\n  password: ").append(q(auth.pass))
                .append("\n  disableUDP: false\n")
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
            ready = runCatching { Socket().use { s -> s.connect(InetSocketAddress(auth.host, socksPort), 300) }; true }.getOrDefault(false)
            if (ready) break
            Thread.sleep(250)
        }
        if (!ready) error("hysteria2: server did not answer. " + lastLog())
        tunUp("Kanki · Hysteria2", ip, socksPort, auth, mtu, dns, excluded, included(lines), blockOthers(lines), my)
    }

    /** «بقیه‌ی برنامه‌ها اینترنت نداشته باشند» (فقط با IncludedApplications معنا دارد) */
    private fun blockOthers(lines: List<String>): Boolean =
        lines.any { it.startsWith("BlockOthers", ignoreCase = true) && it.substringAfter("=", "").trim() == "1" }

    /** «فقط بازی» گیمینگ: برنامه‌هایی که اینترنت دارند؛ null یعنی این حالت خاموش است یا اندروید ۹ و قدیمی‌تر */
    private fun gameOnly(lines: List<String>): List<String>? =
        (lines.firstOrNull { it.startsWith("GameOnly", ignoreCase = true) && it.contains("=") }?.substringAfter("=") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() && AppGate.supported }

    /** دروازه‌ی «فقط بازی» روی کارت شبکه‌ی fd: بقیه‌ی برنامه‌ها بسته، با جواب فوری «اینترنت نیست» */
    private fun gameGate(fd: ParcelFileDescriptor, apps: List<String>): AppGate =
        AppGate(this, fd, AppGate.uidsOf(this, apps) + android.os.Process.myUid(), reject = true, dnsServers = tunDns)

    /** برنامه‌هایی که فقط آن‌ها از VPN رد می‌شوند (حالت «فقط بازی‌ها»)؛ خالی = همه */
    private fun included(lines: List<String>): List<String> =
        (lines.firstOrNull { it.startsWith("IncludedApplications", ignoreCase = true) && it.contains("=") }?.substringAfter("=") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * کارت شبکه‌ی VPN و tun2socks به پراکسی SOCKS محلی هسته (مشترک بین Hysteria2 و Xray).
     * block: فقط برنامه‌های included اینترنت دارند و بقیه هیچ (با AppGate، اندروید ۱۰ به بالا).
     */
    private fun tunUp(session: String, ip: InetAddress, socksPort: Int, auth: SocksAuth, mtu: Int, dns: List<String>, excluded: List<String>, included: List<String>, block: Boolean, my: Int) {
        val gateMode = block && included.isNotEmpty() && AppGate.supported
        val b = Builder()
            .setSession(session)
            .setMtu(mtu)
            .addAddress("198.18.0.1", 32)
        serverRoutes(b, ip)
        tunDns = dns
        dns.forEach { d -> runCatching { b.addDnsServer(d) } }
        // اندروید اجازه نمی‌دهد هر دو فهرست با هم باشند: «فقط این برنامه‌ها» بر «به‌جز این‌ها» مقدم است.
        // در حالت دروازه همه‌ی برنامه‌ها داخل VPN می‌مانند تا AppGate بقیه را ببندد.
        if (!gateMode) {
            if (included.isNotEmpty()) included.forEach { pkg -> runCatching { b.addAllowedApplication(pkg) } }
            else excluded.forEach { pkg -> runCatching { b.addDisallowedApplication(pkg) } }
        }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)

        val hevFile = writeHev(mtu, "198.18.0.1", socksPort, auth)

        // بررسی لغو و بالا آوردن VPN با هم (زیر یک قفل با shutdown) انجام می‌شود؛
        // پس اگر زمان تمام شده یا کاربر قطع کرده، VPN دیگر بی‌صدا وصل نمی‌شود
        synchronized(this) {
            if (cancelled(my)) error("cancelled")
            val fd = b.establish() ?: error("VPN permission is missing")
            tun = fd
            if (gateMode) {
                // خود برنامه (پینگ و بررسی سلامت تونل) هم مجاز است
                val g = AppGate(this, fd, AppGate.uidsOf(this, included) + android.os.Process.myUid(), dnsServers = dns)
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
    private fun writeHev(mtu: Int, ipv4: String, socksPort: Int, auth: SocksAuth): File {
        val hevCfg = "tunnel:\n  mtu: $mtu\n  ipv4: $ipv4\nsocks5:\n  port: $socksPort\n  address: ${auth.host}\n  udp: 'udp'\n" +
            "  username: '${auth.user}'\n  password: '${auth.pass}'\n" +
            "misc:\n  task-stack-size: 81920\n"
        return File(filesDir, "hev.yaml").apply { writeText(hevCfg) }
    }

    @Volatile private var gate: AppGate? = null

    /** سرورهای DNS آخرین کارت شبکه‌ی ساخته‌شده (دروازه فقط DNS به همین‌ها را بی‌پرسش رد می‌کند) */
    @Volatile private var tunDns: List<String> = emptyList()

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
            val auth = SocksAuth.random()
            val socksPort = auth.freePort()
            val cfgFile = File(filesDir, "xray-direct.json").apply { writeText(XrayCfg.directConfig(socksPort, auth)) }
            launchCore(listOf(bin.path, "run", "-c", cfgFile.path), "xray", auth.host, socksPort, my, 10_000)
            val fd = synchronized(this) {
                if (cancelled(my)) error("dns: cancelled")
                KankiDns.buildGateTun(this, TUN_MTU).also { tun = it }
            }
            val f = KankiDns.Forwarder(this, fd, targets)
            dnsFwd = f
            f.reregister()
            val hevFile = writeHev(TUN_MTU, KankiDns.GATE_IP, socksPort, auth)
            synchronized(this) {
                if (cancelled(my)) error("dns: cancelled")
                val g = AppGate(this, fd, AppGate.uidsOf(this, included), f, dnsServers = listOf(KankiDns.FAKE))
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
    private fun launchCore(cmd: List<String>, tag: String, socksHost: String, socksPort: Int, my: Int, waitMs: Long): Process {
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
            val ready = runCatching { Socket().use { s -> s.connect(InetSocketAddress(socksHost, socksPort), 300) }; true }.getOrDefault(false)
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
        val auth = SocksAuth.random()
        val socksPort = auth.freePort()
        val cfgFile = File(filesDir, "xray.json").apply { writeText(XrayCfg.config(link, ip.hostAddress ?: host, socksPort, auth)) }
        launchCore(listOf(bin.path, "run", "-c", cfgFile.path), "xray", auth.host, socksPort, my, 10_000)
        tunUp("Kanki · Xray", ip, socksPort, auth, mtu, dns, excluded, included(lines), blockOthers(lines), my)
    }

    // ---------------------------------------------------------------- WireGuard / AmneziaWG در گیمینگ

    /**
     * WireGuard یا AmneziaWG با دروازه‌ی بازی‌ها: کارت VPN همین سرویس، دروازه جلوی آن (فقط بازی‌های انتخابی، سرویس‌های گوگل و DNS)،
     * و هسته‌ی libawg.so پشت دروازه. تنظیم دستگاه (UAPI) را خود کتابخانه‌ی امنزیا از کانفیگ می‌سازد (همان که موتور خودش می‌سازد)؛
     * فایلش فقط تا وقتی هسته بخواندش می‌ماند. آدرس سرور بیرون از مسیرهای VPN است تا سوکت هسته دور خودش نچرخد.
     */
    private fun startAwg(text: String, my: Int) {
        val lines = text.lines().map { it.trim() }
        // گیمینگ: دروازه‌ی بازی‌ها؛ بی خط GameOnly (موتور کتابخانه رد کرده بود): همان هسته، بی دروازه
        val games = gameOnly(lines)
        val bin = File(applicationInfo.nativeLibraryDir, "libawg.so")
        if (!bin.exists()) error("wireguard: the gaming core is missing in this build")
        // خط‌های خود کانکی (GameOnly و …) را کتابخانه نمی‌شناسد
        val clean = text.lines().filterNot { l -> val t = l.trim(); t.startsWith("GameOnly", true) || t.startsWith(HyEngine.AWG_OWN, true) || t.startsWith("IncludedApplications", true) || t.startsWith("ExcludedApplications", true) }.joinToString("\n")
        val cfg = org.amnezia.awg.config.Config.parse(clean.byteInputStream())
        val peer = cfg.peers.firstOrNull() ?: error("wireguard: the config has no server")
        val ep = peer.endpoint.orElse(null) ?: error("wireguard: the config has no server address")
        val addrs = InetAddress.getAllByName(ep.host)
        val ip = addrs.firstOrNull { it is Inet4Address } ?: addrs.first()
        val mtu = cfg.`interface`.mtu.orElse(1280).coerceIn(1280, 1500)
        val dns = cfg.`interface`.dnsServers.mapNotNull { it.hostAddress }.filter { !it.contains(':') }.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        // سرور با آی‌پی (هسته‌ی جدا DNS نمی‌پرسد)
        val withIp = clean.lines().joinToString("\n") { l ->
            if (l.trim().startsWith("Endpoint", true) && l.contains("=")) "Endpoint = " + (if (ip is Inet4Address) ip.hostAddress else "[${ip.hostAddress}]") + ":" + ep.port else l
        }
        val c2 = org.amnezia.awg.config.Config.parse(withIp.byteInputStream())
        val uapi = c2.`interface`.toAwgUserspaceString() + c2.peers.joinToString("") { it.toAwgUserspaceString(true, this) }
        // a file of this attempt only: the core of the previous one may still be exiting and its monitor removes its own file
        File(filesDir, ".").listFiles()?.filter { (it.name.startsWith("awg-") && (it.name.endsWith(".uapi") || it.name.endsWith(".sock"))) }?.forEach { runCatching { it.delete() } }
        val uapiFile = File(filesDir, "awg-$my.uapi")
        uapiFile.writeText(uapi)
        uapiFile.setReadable(false, false); uapiFile.setReadable(true, true)
        val sock = File(filesDir, "awg-$my.sock")
        HyEngine.coreRx = 0
        HyEngine.coreTx = 0
        val ready = java.util.concurrent.LinkedBlockingQueue<String>()
        val pb = ProcessBuilder(bin.path, "client", "--uapi", uapiFile.path, "--sock", sock.path, "--mtu", mtu.toString())
            .directory(filesDir).redirectErrorStream(true)
        val p = synchronized(this) {
            if (cancelled(my)) { uapiFile.delete(); error("wireguard: cancelled") }
            killStaleCores()
            pb.start().also { proc = it }
        }
        Thread({
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    when {
                        line == "READY" -> ready.put("1")
                        line.startsWith("STAT ") -> runCatching {
                            val o = org.json.JSONObject(line.removePrefix("STAT "))
                            HyEngine.coreRx = o.optLong("rx")
                            HyEngine.coreTx = o.optLong("tx")
                        }
                        line == "WAIT" -> {}
                        else -> synchronized(log) {
                            android.util.Log.i("KankiAwg", line.take(200))
                            log.append(line).append('\n')
                            if (log.length > 6000) log.delete(0, log.length - 4000)
                        }
                    }
                }
            }
        }, "kanki-awg-log").start()
        Thread({
            runCatching { p.waitFor() }
            uapiFile.delete()
            ready.put("")
            if (!cancelled(my) && HyEngine.state == HyEngine.UP) {
                HyEngine.crashed = true
                HyEngine.lastError = "wireguard core exited. " + lastLog()
                shutdown()
                HyEngine.state = HyEngine.IDLE
            }
        }, "kanki-awg-mon").start()

        val b = Builder().setSession("Kanki · WireGuard").setMtu(mtu)
        val excluded = lines.firstOrNull { it.startsWith("ExcludedApplications", true) && it.contains("=") }?.substringAfter("=")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (games == null) excluded.forEach { pkg -> runCatching { b.addDisallowedApplication(pkg) } }
        // دروازه فقط IPv4 را می‌شناسد: IPv6 بی آدرس (برنامه‌ها فوری IPv4 می‌گیرند)، مسیر ::/0 در serverRoutes تا بیرون نرود
        cfg.`interface`.addresses.filter { it.address is Inet4Address }.forEach { a -> b.addAddress(a.address, a.mask) }
        serverRoutes(b, ip)
        tunDns = dns
        dns.forEach { d -> runCatching { b.addDnsServer(d) } }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        val fd = synchronized(this) {
            if (cancelled(my)) error("wireguard: cancelled")
            (b.establish() ?: error("VPN permission is missing")).also { tun = it }
        }
        sendTun(sock, gated(fd, games, my), my)
        val r = ready.poll(15, java.util.concurrent.TimeUnit.SECONDS)
        if (r.isNullOrEmpty()) error("wireguard: " + (lastLog().ifBlank { "the core did not start" }))
        synchronized(this) {
            if (cancelled(my)) error("wireguard: cancelled")
            HyEngine.awg = true
            HyEngine.state = HyEngine.UP
        }
    }

    /** «فقط بازی»: دروازه‌ی تازه روی کارت fd و سر دیگرش برای هسته؛ وگرنه خود کارت */
    private fun gated(fd: ParcelFileDescriptor, games: List<String>?, my: Int): ParcelFileDescriptor {
        if (games == null) return fd
        return synchronized(this) {
            if (cancelled(my)) error("cancelled")
            gameGate(fd, games).also { gate = it; it.start() }.inner
        }
    }

    /** کارت VPN را از راه سوکت یونیکس (SCM_RIGHTS) به هسته می‌دهد؛ هسته سوکت را کمی بعد از ADDR باز می‌کند */
    private fun sendTun(sock: File, fd: ParcelFileDescriptor, my: Int) {
        val end = System.currentTimeMillis() + 5_000
        var last: Exception? = null
        while (System.currentTimeMillis() < end && !cancelled(my)) {
            try {
                android.net.LocalSocket().use { s ->
                    s.connect(android.net.LocalSocketAddress(sock.path, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
                    s.setFileDescriptorsForSend(arrayOf(fd.fileDescriptor))
                    s.outputStream.write(1)
                    s.outputStream.flush()
                }
                return
            } catch (e: Exception) {
                last = e
                Thread.sleep(100)
            }
        }
        error("core did not take the VPN (${last?.message ?: "cancelled"})")
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
                if (cmd.contains("libhysteria.so") || cmd.contains("libxray.so") || cmd.contains("libkmix.so") || cmd.contains("libawg.so")) android.os.Process.killProcess(pid)
            }
        }
    }

    private fun lastLog(): String = synchronized(log) {
        log.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(3).joinToString(" | ").take(400)
    }

    /**
     * همه‌ی IPv4 و IPv6 داخل تونل، به‌جز آی‌پی خود سرور (تا سوکت هسته دور خودش نچرخد).
     * ۱.۳۸ (بررسی امنیتی): قبلاً اگر سرور فقط IPv6 داشت، هیچ مسیر IPv6 نبود و IPv6 برنامه‌ها بیرون از تونل می‌رفت.
     */
    private fun serverRoutes(b: Builder, ip: InetAddress, more: List<InetAddress> = emptyList()) {
        if (ip is Inet4Address) {
            routesExcludingAll((listOf(ip) + more.filterIsInstance<Inet4Address>()).map { it.address }).forEach { (net, len) -> b.addRoute(net, len) }
            runCatching { b.addRoute("::", 0) }
        } else {
            b.addRoute("0.0.0.0", 0)
            routesExcluding(ip.address).forEach { (net, len) -> runCatching { b.addRoute(net, len) } }
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
        val games = gameOnly(lines)

        val addrs = InetAddress.getAllByName(u.host)
        val ip = addrs.firstOrNull { it is Inet4Address } ?: addrs.first()
        val server = if (ip is Inet4Address) "${ip.hostAddress}:${u.pickPort()}" else "[${ip.hostAddress}]:${u.pickPort()}"
        // مسیرهای دیگر همین سرور (ورودی تانل ایران): آی‌پی‌شان هم بیرون از VPN، تا سوکت پیش‌فرض هسته دور خودش نچرخد
        val alt = u.alt.mapNotNull { a ->
            runCatching {
                val h = a.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
                val x = InetAddress.getAllByName(h).firstOrNull { it is Inet4Address } ?: return@runCatching null
                x to a.substringAfterLast(":").toInt()
            }.getOrNull()
        }.filter { it.first != ip }

        val b = Builder()
            .setSession("Kanki · KANKI MIX")
            .setMtu(KMIX_MTU)
            .addAddress("10.77.0.2", 32)
        serverRoutes(b, ip, alt.map { it.first })
        tunDns = dns
        dns.forEach { d -> runCatching { b.addDnsServer(d) } }
        // «فقط بازی»: همه‌ی برنامه‌ها (حتی مستثنی‌ها) داخل VPN می‌مانند تا دروازه بقیه را ببندد
        if (games == null) {
            if (included.isNotEmpty()) included.forEach { pkg -> runCatching { b.addAllowedApplication(pkg) } }
            else excluded.forEach { pkg -> runCatching { b.addDisallowedApplication(pkg) } }
        }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)

        val bin = KmixCore.binary(this)
        if (!bin.exists()) error("kanki mix: core is missing in this build")
        val fd = synchronized(this) {
            if (cancelled(my)) error("kanki mix: cancelled")
            killStaleCores()
            (b.establish() ?: error("VPN permission is missing")).also { tun = it }
        }
        // «فقط بازی»: هسته به‌جای کارت شبکه سر دیگر دروازه را می‌گیرد
        val coreFd = if (games != null) synchronized(this) {
            if (cancelled(my)) error("kanki mix: cancelled")
            gameGate(fd, games).also { gate = it; it.start() }.inner
        } else fd
        // تا ~۲۲ ثانیه: هسته بالا می‌آید و سرور کلید این کاربر را می‌سنجد (کلید اشتباه یا سرور خاموش = خطای روشن)
        val altSpec = alt.joinToString(",") { "${it.first.hostAddress}:${it.second}" }
        KmixCore.start(bin, coreFd, server, u.key, u.fecData, u.fecParity, gaming, u.portSpec(), altSpec) { why ->
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
        // در حالت گیمینگ وای‌فای و دیتا با هم: هسته بسته را روی بهترین و فقط ترمیم لازم را روی دیگری می‌فرستد
        if (gaming) kmixPaths(my)
    }

    /** درخواست‌های شبکه (وای‌فای، دیتا) که تا قطع باز می‌مانند */
    private val kmNets = java.util.concurrent.CopyOnWriteArrayList<android.net.ConnectivityManager.NetworkCallback>()

    /**
     * هر شبکه‌ی گوشی یک مسیر KANKI MIX: سوکت UDP خودش، بیرون از VPN (protect) و بسته به همان شبکه. دیتای موبایل حتی وقتی
     * وای‌فای وصل است بالا می‌ماند (requestNetwork). اینترنت خود گوشی هیچ‌وقت بسته نمی‌شود.
     */
    private fun kmixPaths(my: Int) {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        for ((name, transport) in listOf("wifi" to android.net.NetworkCapabilities.TRANSPORT_WIFI, "cell" to android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
            val req = android.net.NetworkRequest.Builder()
                .addTransportType(transport)
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                @Volatile var cur: android.net.Network? = null
                override fun onAvailable(network: android.net.Network) {
                    if (cancelled(my) || cur == network) return
                    cur = network
                    Thread({ runCatching { kmixPath(name, network) } }, "kanki-kmix-$name").start()
                }
                override fun onLost(network: android.net.Network) {
                    if (cur != network) return
                    cur = null
                    Thread({ runCatching { KmixCore.dropPath(name) } }, "kanki-kmix-$name").start()
                }
            }
            runCatching { cm.requestNetwork(req, cb); kmNets.add(cb) }
        }
    }

    private fun kmixPath(name: String, network: android.net.Network) {
        val ds = java.net.DatagramSocket(null as java.net.SocketAddress?)
        try {
            ds.bind(InetSocketAddress(0))
            protect(ds)
            network.bindSocket(ds)
            ParcelFileDescriptor.fromDatagramSocket(ds).use { pfd -> KmixCore.addPath(name, pfd) }
        } finally {
            ds.close()
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
        HyEngine.awg = false
        kmNets.forEach { cb -> runCatching { (getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(cb) } }
        kmNets.clear()
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

/** همه‌ی آدرس‌های یک خانواده به‌جز یکی (۳۲ مسیر برای IPv4، ۱۲۸ برای IPv6 که روی‌هم همه‌چیز را می‌پوشانند) */
/** همه‌ی IPv4 به‌جز چند نشانی (سرور و ورودی‌های تانلش)؛ با یک نشانی همان routesExcluding */
internal fun routesExcludingAll(ips: List<ByteArray>): List<Pair<String, Int>> {
    val xs = ips.filter { it.size == 4 }.map { java.math.BigInteger(1, it).toLong() }.distinct()
    if (xs.size <= 1) return routesExcluding(ips.first())
    val out = mutableListOf<Pair<String, Int>>()
    fun walk(net: Long, len: Int) {
        val size = 1L shl (32 - len)
        val inside = xs.filter { it >= net && it < net + size }
        if (inside.isEmpty()) {
            val raw = ByteArray(4) { i -> (net shr (24 - 8 * i)).toByte() }
            out += InetAddress.getByAddress(raw).hostAddress!! to len
            return
        }
        if (len == 32) return
        walk(net, len + 1)
        walk(net + size / 2, len + 1)
    }
    walk(0L, 0)
    return out
}

internal fun routesExcluding(ip: ByteArray): List<Pair<String, Int>> {
    val bits = ip.size * 8
    val x = java.math.BigInteger(1, ip)
    val all = java.math.BigInteger.ONE.shiftLeft(bits).subtract(java.math.BigInteger.ONE)
    return (1..bits).map { len ->
        val mask = all.shiftRight(bits - len).shiftLeft(bits - len)
        val net = x.xor(java.math.BigInteger.ONE.shiftLeft(bits - len)).and(mask)
        val raw = net.toByteArray().let { a -> ByteArray(ip.size) { i -> a.getOrElse(a.size - ip.size + i) { 0 } } }
        InetAddress.getByAddress(raw).hostAddress!! to len
    }
}
