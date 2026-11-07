package cafe.kankipro.vpn

/** نگه‌داشتن مسیر NAT برای اتصال و پینگ پایدار */
fun applyKeepalive(cfg: String, sec: Int = 15): String {
    if (cfg.lines().any { it.trim().startsWith("PersistentKeepalive", ignoreCase = true) }) return cfg
    val lines = cfg.lines().toMutableList()
    val i = lines.indexOfFirst { it.trim().equals("[Peer]", ignoreCase = true) }
    if (i >= 0) lines.add(i + 1, "PersistentKeepalive = $sec")
    return lines.joinToString("\n")
}

/** تونل‌زنی انتخابی: این برنامه‌ها از VPN رد نمی‌شوند */
fun applyExcluded(cfg: String, pkgs: Set<String>): String {
    if (pkgs.isEmpty()) return cfg
    val lines = cfg.lines().filterNot { it.trim().startsWith("ExcludedApplications", ignoreCase = true) }.toMutableList()
    val i = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
    if (i >= 0) lines.add(i + 1, "ExcludedApplications = " + pkgs.sorted().joinToString(", "))
    return lines.joinToString("\n")
}

/**
 * «فقط بازی اینترنت داشته باشد» (گیمینگ، فقط KANKI MIX و microtun): فقط این برنامه‌ها و پیش‌نیازهای ورود بازی
 * (سرویس‌های گوگل و پلی‌استور) اینترنت دارند؛ DNS همیشه رد می‌شود و بقیه‌ی برنامه‌ها تا قطع VPN اینترنت ندارند.
 */
fun applyGameOnly(cfg: String, games: Set<String>): String {
    if (games.isEmpty()) return cfg
    val lines = cfg.lines().filterNot { it.trim().startsWith("GameOnly", ignoreCase = true) }.toMutableList()
    val i = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
    if (i >= 0) lines.add(i + 1, "GameOnly = " + (games + GAME_NEEDS).sorted().joinToString(", "))
    return lines.joinToString("\n")
}

/** بدون این‌ها ورود به بازی (حساب گوگل / پلی) و خرید داخل بازی کار نمی‌کند */
val GAME_NEEDS = setOf("com.google.android.gms", "com.google.android.gsf", "com.android.vending")

/**
 * بستن کامل IPv6 در کانفیگ وایرگارد/امنزیا: آدرس، مسیر (AllowedIPs) و DNS ای‌پی‌وی‌شش حذف می‌شوند.
 * در ایران IPv6 کار نمی‌کند و برنامه‌ها پیش از برگشتن به IPv4 معطل می‌مانند (پینگ بالا و کندی).
 */
fun stripIpv6(cfg: String): String {
    fun keepV4(v: String): String =
        v.split(",").map { it.trim() }.filter { it.isNotEmpty() && !it.contains(":") }.joinToString(", ")
    return cfg.lines().mapNotNull { line ->
        val t = line.trim()
        if (!t.contains("=")) return@mapNotNull line
        val key = t.substringBefore("=").trim()
        val v = keepV4(t.substringAfter("="))
        when (key.lowercase()) {
            "address" -> if (v.isEmpty()) line else "$key = $v"
            "dns" -> if (v.isEmpty()) null else "$key = $v"
            "allowedips" -> "$key = " + v.ifEmpty { "0.0.0.0/0" }
            else -> line
        }
    }.joinToString("\n")
}
