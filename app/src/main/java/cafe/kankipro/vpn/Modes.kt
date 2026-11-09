package cafe.kankipro.vpn

import android.content.Context

/*
 * حالت‌های اتصال (چهار دکمه‌ی صفحه‌ی اول)
 * -----------------------------------------
 *   هوشمند    پینگ همه‌ی کانفیگ‌ها (Xray، AmneziaWG تانل/مستقیم، WireGuard، Hysteria2) را می‌گیرد و به
 *              کمترین پینگ وصل می‌شود؛ اگر واقعاً وصل نشد، بعدی
 *   گیمینگ     کاربر پروتکل (AmneziaWG تانل/مستقیم، WireGuard، Hysteria2) و کشور را انتخاب می‌کند؛
 *              «انتخاب هوشمند سرور» کم‌پینگ‌ترین کشور و پروتکل را برمی‌دارد
 *   ضد فیلتر   لمس اول: اول راه‌های مستقیم (اینباندهای Xray، AmneziaWG مستقیم)، اگر جواب ندادند تانل؛
 *              لمس دوباره (وقتی همین حالت انتخاب است): تب سرورها برای انتخاب دستی
 *   DNS        رفع تحریم با DNS کانکی (اول ریلی‌های ایران، بعد خروجی‌های مستقیم)
 * سرور و پروتکلی که کاربر انتخاب کرده قفل است: برنامه خودش آن را عوض نمی‌کند
 * (فقط «هوشمند» و «انتخاب هوشمند سرور» اجازه‌ی امتحان گزینه‌ی بعدی را دارند).
 */
enum class KMode(val key: String, val title: String, val sub: String) {
    SMART("smart", "هوشمند", "پینگ همه را می‌گیرد و به بهترین وصل می‌شود"),
    GAME("game", "گیمینگ", "کمترین پینگ برای بازی · پروتکل و کشور با شما"),
    FILTER("filter", "ضد فیلتر", "یک لمس: بهترین راه اتصال · لمس دوباره: انتخاب دستی"),
    DNS("dns", "DNS", "رفع تحریم بازی‌ها و برنامه‌ها با DNS کانکی");

    companion object {
        /** کلیدهای نسخه‌های قبل (auto، web، strict) هم به حالت تازه‌ی هم‌معنا برمی‌گردند */
        fun of(k: String?) = when (k) {
            "auto", "web" -> SMART
            "strict" -> FILTER
            else -> entries.firstOrNull { it.key == k } ?: SMART
        }
    }
}

object Modes {
    /**
     * گیمینگ: چهار خانواده‌ی پروتکل UDP، به ترتیب نمایش (KANKI MIX، پروتکل خود کانکی، اول).
     * Amnezia هر دو راه را دارد: مستقیم و تانل.
     */
    val GAME_FAMILIES = listOf(
        "kankimix" to listOf("kankimix"),
        // KPING فقط برای تسترهایی که پنل به آن‌ها داده (بقیه این ردیف را نمی‌بینند)
        "kping" to listOf("kping"),
        "mtun" to listOf("mtun"),
        "hysteria2" to listOf("hysteria2"),
        "amnezia" to listOf("amneziawg-direct", "amneziawg"),
        "wireguard" to listOf("wireguard"),
    )

    fun familyOf(p: String): String? = GAME_FAMILIES.firstOrNull { p in it.second }?.first

    fun familyProtos(f: String): List<String> = GAME_FAMILIES.firstOrNull { it.first == f }?.second ?: emptyList()

    /**
     * مسیر گیمینگ (فقط برای «سریع‌ترین سرور»):
     *   auto    هر دو راه سنجیده می‌شود و بهترین امتیاز برنده است
     *   direct  مستقیم تا سرور خارج (اینترنتی که مسیر خوبی دارد)
     *   tunnel  از سرور ایران و تانل ضد افت (Game-FEC) تا سرور خارج
     */
    val GAME_ROUTES = listOf("auto", "direct", "tunnel")

    /**
     * امتیاز یک کانفیگ برای بازی (کمتر = بهتر): میانگین پینگ + لرزش × ۳ + درصد افت × ۲۰.
     * لرزش میانگین فرق رفت‌وبرگشت بسته‌های پشت‌سرهم است. یک مسیر ۵۰ میلی‌ثانیه‌ی پرنوسان از یک مسیر
     * ۶۰ میلی‌ثانیه‌ی صاف عقب می‌افتد. null وقتی هیچ جوابی نیامد.
     */
    /** کیفیت یک کانفیگ برای بازی: درصد افت، لرزش (ms)، امتیاز و تعداد بسته‌های سنجش */
    data class GameQ(val loss: Int, val jitter: Long, val score: Double, val n: Int)

    /** با کمتر از این تعداد بسته درصد افت گول‌زننده است (یک بسته‌ی گم‌شده از ۴ تا یعنی «۲۵٪») و نشان داده نمی‌شود */
    const val LOSS_MIN_SAMPLES = 10

    fun gameQ(series: List<Long?>): GameQ? {
        val score = gameScore(series) ?: return null
        val ok = series.filterNotNull()
        val jitter = if (ok.size < 2) 0L else ok.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average().toLong()
        val loss = (series.count { it == null } * 100 + series.size / 2) / series.size
        return GameQ(loss, jitter, score, series.size)
    }

    /**
     * برچسب پایداری برای بازی: ۰ پایدار، ۱ متوسط، ۲ ضعیف (لرزش ۳۰ms مثل هشدار مسیر پنل).
     * سنجش کوتاه (Hysteria2 و microtun، هر بار یک دست‌دادن کامل): یک بی‌جواب «متوسط»، نصف یا بیشتر «ضعیف».
     */
    fun stability(q: GameQ): Int = when {
        q.n < LOSS_MIN_SAMPLES -> when {
            q.loss >= 50 || q.jitter >= 30 -> 2
            q.loss == 0 && q.jitter <= 12 -> 0
            else -> 1
        }
        // یک بسته‌ی گم‌شده در ۲۰ تا (۵٪) می‌تواند شانسی باشد: «متوسط»؛ از ۱۰٪ «ضعیف»
        q.loss >= 10 || q.jitter >= 30 -> 2
        q.loss == 0 && q.jitter <= 12 -> 0
        else -> 1
    }

    fun gameScore(series: List<Long?>): Double? {
        val ok = series.filterNotNull()
        if (ok.isEmpty()) return null
        val avg = ok.average()
        val jitter = if (ok.size < 2) 0.0 else ok.zipWithNext { a, b -> kotlin.math.abs(a - b).toDouble() }.average()
        val loss = series.count { it == null } * 100.0 / series.size
        return avg + jitter * 3 + loss * 20
    }


    /** اسم شبکه‌ی فعلی برای حافظه‌ی اپراتور: «wifi» یا کد اپراتور موبایل (مثل 43235 ایرانسل) */
    fun networkKey(ctx: Context): String = runCatching {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.allNetworks?.mapNotNull { cm.getNetworkCapabilities(it) }
            ?.firstOrNull { !it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        when {
            caps == null -> "none"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            else -> {
                val tm = ctx.getSystemService(android.telephony.TelephonyManager::class.java)
                "mob-" + (tm?.networkOperator?.takeIf { it.isNotBlank() } ?: tm?.networkOperatorName ?: "x")
            }
        }
    }.getOrDefault("x")

    /** اسم قابل‌فهم اپراتور برای نمایش */
    fun networkName(ctx: Context): String {
        val k = networkKey(ctx)
        if (k == "wifi") return "Wi-Fi"
        val code = k.removePrefix("mob-")
        return when {
            code.startsWith("43235") -> "ایرانسل"
            code.startsWith("43211") -> "همراه اول"
            code.startsWith("43220") -> "رایتل"
            code.startsWith("43214") -> "تالیا"
            k == "none" || k == "x" -> "—"
            else -> runCatching { ctx.getSystemService(android.telephony.TelephonyManager::class.java)?.networkOperatorName }.getOrNull()?.ifBlank { null } ?: "موبایل"
        }
    }
}

