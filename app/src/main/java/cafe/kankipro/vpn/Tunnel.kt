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
