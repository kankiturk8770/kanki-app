package cafe.kankipro.vpn

import android.content.Context
import java.net.URLDecoder

/*
 * microtun: VPN سبک بازی (جای MIX+)
 * ----------------------------------------------
 * هسته‌ی libmtun.so (پوشه‌ی mtun-core) مثل KANKI MIX خود کارت VPN را می‌گیرد؛ بدون tun2socks. هر بسته ۲۸ بایت
 * سربار دارد و بسته‌های کوچک بازی (تا ۳۰۰ بایت) دو بار فرستاده می‌شوند تا گم شدن یک بسته لگ نیاورد؛ دانلود و
 * ویدیو یک بار می‌روند، پس حجم اضافه نمی‌خورد. لینک mtun://کلید@سرور:پورت?obfs=…&ip=… از اشتراک می‌آید
 * (فیلد mtun هر سرور، پنل ۳.۱۸ به بعد).
 */
object MtunCfg {
    const val PROTO = "mtun"

    fun isProto(p: String) = p == PROTO

    fun title(p: String) = "microtun"

    fun supported(link: String) = link.trim().startsWith("mtun://")

    /** متنی که برنامه نگه می‌دارد (مثل Xray، شبیه کانفیگ وایرگارد تا DNS و برنامه‌های مستثنی رویش اعمال شوند) */
    fun wrap(link: String): String {
        val (h, p) = endpoint(link)
        return "[Interface]\n[Mtun]\nEndpoint = $h:$p\nURI = ${link.trim()}\n"
    }

    fun isMtun(text: String) = text.lines().any { it.trim().equals("[Mtun]", ignoreCase = true) }

    fun query(link: String): Map<String, String> =
        link.substringAfter("?", "").substringBefore("#").split("&").filter { it.contains("=") }.associate {
            val p = it.split("=", limit = 2)
            p[0] to runCatching { URLDecoder.decode(p[1], "UTF-8") }.getOrDefault(p[1])
        }

    /** MTU کارت VPN (از لینک پنل؛ پیش‌فرض ۱۴۲۰) */
    fun mtu(link: String): Int = query(link)["mtu"]?.toIntOrNull()?.coerceIn(1280, 1500) ?: 1420

    /** (میزبان، پورت) سرور */
    fun endpoint(link: String): Pair<String, Int> {
        val hp = link.substringAfter("://").substringBefore("?").substringBefore("#").substringAfterLast("@").trimEnd('/')
        return if (hp.startsWith("[")) {
            val i = hp.indexOf(']')
            hp.substring(1, i) to (hp.substring(i + 2).toIntOrNull() ?: error("microtun: bad port"))
        } else hp.substringBeforeLast(":") to (hp.substringAfterLast(":").toIntOrNull() ?: error("microtun: bad port"))
    }

    /** همان لینک با آی‌پی به‌جای اسم سرور (هسته به همان آی‌پی وصل شود که از VPN بیرون گذاشته شده) */
    fun withHost(link: String, ip: String): String {
        val (h, p) = endpoint(link)
        val host = if (ip.contains(':')) "[$ip]" else ip
        val old = if (h.contains(':')) "[$h]:$p" else "$h:$p"
        val head = link.substringBefore("?")
        return head.substringBeforeLast(old) + "$host:$p" + link.removePrefix(head)
    }

    /**
     * اسم ثابت این نصب برای سرور: هر گوشی یک «دستگاه» جدای همان کاربر است (آدرس خودش را می‌گیرد) و وصل
     * شدن دوباره همان دستگاه حساب می‌شود. سقف دستگاه‌ها همان «تعداد اتصال» کاربر در پنل است.
     */
    fun deviceName(ctx: Context): String {
        val p = ctx.getSharedPreferences("kanki_mtun", Context.MODE_PRIVATE)
        p.getString("device", null)?.let { return it }
        val b = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val id = b.joinToString("") { "%02x".format(it) }
        p.edit().putString("device", id).apply()
        return id
    }
}

/*
 * KPING: هسته‌ی بازی خود کانکی (پنل ۳.۳۹ به بعد؛ نه microtun)
 * ----------------------------------------------
 * فقط برای چند تستر که ادمین در پنل انتخاب می‌کند و فقط داخل جواب رمزشده‌ی اشتراک (فیلد kping، لینک kping://).
 * هسته‌ی جدای libkping.so (پوشه‌ی kping-core): UDP خام بی فرستادن دوباره؛ هر بسته روی هر مسیر (وای‌فای و دیتا با هم)
 * یک نسخه، یا روی یک مسیر دو نسخه با چند میلی‌ثانیه فاصله؛ اولین نسخه‌ای که رسید برنده؛ پریتی سبک. سرور ایران (رله) از
 * هر بسته یک نسخه را با تانل Game-FEC به سرور خارج می‌برد.
 * فقط PUBG Global (com.tencent.ig) و سرویس‌های گوگل که ورود بازی با آن‌هاست اینترنت دارند؛ بقیه‌ی برنامه‌ها کاملاً بسته‌اند
 * (دروازه‌ی AppGate، اندروید ۱۰ به بالا). شبکه‌های خود گوشی بسته نمی‌شوند: سوکت‌های هسته روی هر شبکه جدا ساخته می‌شوند.
 */
object KpingCfg {
    const val PROTO = "kping"
    const val TITLE = "KPING"

    /** فقط پابجی گلوبال (خواسته‌ی صاحب اپ؛ نسخه‌های کره، هند، ویتنام و تایوان نه) */
    const val GAME = "com.tencent.ig"

    fun supported(link: String) = link.trim().startsWith("kping://")

    fun installed(ctx: Context): Boolean = runCatching { ctx.packageManager.getApplicationInfo(GAME, 0); true }.getOrDefault(false)

    /** متنی که برنامه نگه می‌دارد (مثل microtun) */
    fun wrap(link: String): String {
        val (h, p) = MtunCfg.endpoint(link)
        return "[Interface]\n[Kping]\nEndpoint = $h:$p\nURI = ${link.trim()}\n"
    }

    fun isKping(text: String) = text.lines().any { it.trim().equals("[Kping]", ignoreCase = true) }

    /** آدرس گوشی داخل KPING (از لینک پنل) */
    fun ip(link: String): String = MtunCfg.query(link)["ip"] ?: "10.81.0.2"

    fun mtu(link: String): Int = MtunCfg.query(link)["mtu"]?.toIntOrNull()?.coerceIn(1280, 1500) ?: 1332

    // ---------------------------------------------------------------- تنظیمات (صفحه‌ی تنظیمات KPING)

    /** کدام شبکه‌ها: هر دو (پیش‌فرض)، فقط وای‌فای، فقط دیتا */
    enum class Paths { BOTH, WIFI, CELL }

    data class Prefs(
        val auto: Boolean = true,
        val paths: Paths = Paths.BOTH,
        /** یک مسیر: فاصله‌ی نسخه‌ی دوم (میلی‌ثانیه، ۰ = بی نسخه‌ی دوم) */
        val dupMs: Int = 10,
        /** یک بسته‌ی ترمیم برای هر چند بسته (۰ = خاموش) */
        val fec: Int = 2,
        /** نسخه‌ی دیرتر روی هر مسیر حتی وقتی دو مسیر هست */
        val every: Boolean = false,
        /** وقتی مسابقه در جریان است و بازی لحظه‌ای ساکت است، رادیوی اینترنت موبایل بیدار بماند (یک بسته‌ی خیلی کوچک) */
        val radio: Boolean = true,
        /** پنل کوچک پینگ و افت روی صفحه‌ی بازی (اجازه‌ی «نمایش روی برنامه‌های دیگر» لازم است) */
        val hud: Boolean = false,
    ) {
        fun json(): String = org.json.JSONObject().put("auto", auto).put("dup", dupMs).put("fec", fec).put("every", every).put("radio", radio).toString()
    }

    fun prefs(ctx: Context): Prefs {
        val p = ctx.getSharedPreferences("kanki_kping", Context.MODE_PRIVATE)
        return Prefs(
            auto = p.getBoolean("auto", true),
            paths = runCatching { Paths.valueOf(p.getString("paths", "BOTH")!!) }.getOrDefault(Paths.BOTH),
            dupMs = p.getInt("dup", 10).coerceIn(0, 50),
            fec = p.getInt("fec", 2).let { if (it == 1) 2 else it.coerceIn(0, 8) },
            every = p.getBoolean("every", false),
            radio = p.getBoolean("radio", true),
            hud = p.getBoolean("hud", false),
        )
    }

    fun save(ctx: Context, v: Prefs) {
        ctx.getSharedPreferences("kanki_kping", Context.MODE_PRIVATE).edit()
            .putBoolean("auto", v.auto).putString("paths", v.paths.name).putInt("dup", v.dupMs).putInt("fec", v.fec).putBoolean("every", v.every)
            .putBoolean("radio", v.radio).putBoolean("hud", v.hud).apply()
        // وصل است: هسته همین حالا تنظیم تازه را می‌گیرد؛ شبکه‌ها با اتصال بعدی
        HyEngine.service?.kpingSet(v)
        KpHud.sync(ctx)
    }
}

/** آمار زنده‌ی KPING از گزارش هر ثانیه‌ی هسته (STAT) */
data class KpPath(
    val name: String, val alive: Boolean, val rtt: Double, val jit: Double, val loss: Double, val upFirst: Long, val upTotal: Long, val rescued: Long,
    /** v3: افت بالا (پیش از نسخه‌ی دوم و ترمیم)، مسیر الان خاموش است، چند قطعی کوتاه دیده */
    val upLoss: Double = 0.0, val dark: Boolean = false, val outages: Long = 0,
)

data class KpStat(
    val up: Boolean = false,
    val ping: Double = 0.0,
    val jit: Double = 0.0,
    val paths: List<KpPath> = emptyList(),
    val got: Long = 0,
    val fixed: Long = 0,
    val rescued: Long = 0,
    val dupMs: Int = 0,
    val fec: Int = 0,
    val every: Boolean = false,
    val auto: Boolean = true,
    val rx: Long = 0,
    val tx: Long = 0,
    /** v3 (پنل و هسته‌ی تازه): ترمیم پنجره‌ای، تشخیص نوع بسته، درگیری، افت بالا/پایین، پینگ تا سرور خارج */
    val v3: Boolean = false,
    val fight: Boolean = false,
    val mainPps: Double = 0.0,
    val classes: Map<String, Long> = emptyMap(),
    val upLoss: Double = 0.0,
    val downLoss: Double = 0.0,
    val repair: Double = 0.0,
    val copyMs: Int = 0,
    val stale: Long = 0,
    val tunRtt: Double = 0.0,
    val tunLoss: Double = 0.0,
    val total: Double = 0.0,
) {
    companion object {
        fun parse(s: String): KpStat? = runCatching {
            val o = org.json.JSONObject(s)
            val ps = o.optJSONArray("paths")
            val st = o.optJSONObject("settings") ?: org.json.JSONObject()
            KpStat(
                up = o.optBoolean("up"), ping = o.optDouble("ping", 0.0), jit = o.optDouble("jit", 0.0),
                paths = if (ps == null) emptyList() else (0 until ps.length()).mapNotNull { i -> ps.optJSONObject(i) }.map {
                    KpPath(it.optString("name"), it.optBoolean("alive"), it.optDouble("rtt", 0.0), it.optDouble("jit", 0.0), it.optDouble("loss", 0.0),
                        it.optLong("up_first"), it.optLong("up_total"), it.optLong("rescued"),
                        it.optDouble("up_loss", 0.0), it.optBoolean("dark"), it.optLong("outages"))
                },
                got = o.optLong("got"), fixed = o.optLong("fixed"), rescued = o.optLong("rescued"),
                dupMs = st.optInt("dup"), fec = st.optInt("fec"), every = st.optBoolean("every"), auto = st.optBoolean("auto", true),
                rx = o.optLong("rx"), tx = o.optLong("tx"),
                v3 = o.optBoolean("v3"), fight = o.optBoolean("fight"), mainPps = o.optDouble("main_pps", 0.0),
                classes = o.optJSONObject("classes")?.let { c -> c.keys().asSequence().associateWith { k -> c.optLong(k) } } ?: emptyMap(),
                upLoss = o.optDouble("up_loss", 0.0), downLoss = o.optDouble("down_loss", 0.0), repair = o.optDouble("repair", 0.0),
                copyMs = o.optInt("copy_ms"), stale = o.optLong("stale"),
                tunRtt = o.optDouble("tun_rtt", 0.0), tunLoss = o.optDouble("tun_loss", 0.0), total = o.optDouble("total", 0.0),
            )
        }.getOrNull()
    }
}
