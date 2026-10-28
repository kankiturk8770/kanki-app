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
    FILTER("filter", "ضد فیلتر", "یک لمس: بهترین راه عبور از فیلتر · لمس دوباره: انتخاب دستی"),
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
        "kgame" to listOf("kgame"),
        "khq" to listOf("khq"),
        "kftcp" to listOf("kftcp"),
        "hysteria2" to listOf("hysteria2"),
        "amnezia" to listOf("amneziawg-direct", "amneziawg"),
        "wireguard" to listOf("wireguard"),
    )

    fun familyOf(p: String): String? = GAME_FAMILIES.firstOrNull { p in it.second }?.first

    fun familyProtos(f: String): List<String> = GAME_FAMILIES.firstOrNull { it.first == f }?.second ?: emptyList()


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

