package cafe.kankipro.vpn

// اسم‌ها، پرچم‌ها و برچسب‌های مشترک (بدون وابستگی به اندروید)

fun faStatus(s: String) = when (s.lowercase()) {
    "active" -> "فعال"
    "expired" -> "منقضی شده"
    "disabled", "inactive" -> "غیرفعال"
    "limited" -> "حجم تمام شده"
    "frozen", "suspended" -> "متوقف"
    else -> s
}

fun isActive(s: String) = s.equals("Active", true)

val flagTable = listOf(
    listOf("DE", "GER", "GERMANY", "DEUTSCHLAND", "FRANKFURT", "BERLIN", "آلمان") to "🇩🇪",
    listOf("TR", "TUR", "TURKEY", "TURKIYE", "ISTANBUL", "ترکیه") to "🇹🇷",
    listOf("IR", "IRAN", "TEHRAN", "ایران", "تهران") to "🇮🇷",
    listOf("NL", "NETHERLANDS", "HOLLAND", "AMSTERDAM", "هلند") to "🇳🇱",
    listOf("FI", "FINLAND", "HELSINKI", "فنلاند") to "🇫🇮",
    listOf("FR", "FRANCE", "PARIS", "فرانسه") to "🇫🇷",
    listOf("UK", "GB", "ENGLAND", "LONDON", "انگلیس") to "🇬🇧",
    listOf("US", "USA", "AMERICA", "آمریکا") to "🇺🇸",
    listOf("AE", "UAE", "DUBAI", "امارات") to "🇦🇪",
    listOf("RU", "RUSSIA", "MOSCOW", "روسیه") to "🇷🇺",
    listOf("SE", "SWEDEN", "سوئد") to "🇸🇪",
    listOf("CA", "CANADA", "کانادا") to "🇨🇦",
    listOf("PL", "POLAND", "لهستان") to "🇵🇱",
    listOf("AT", "AUSTRIA", "اتریش") to "🇦🇹",
    listOf("AM", "ARMENIA", "ارمنستان") to "🇦🇲",
    listOf("SG", "SINGAPORE", "سنگاپور") to "🇸🇬",
    listOf("JP", "JAPAN", "ژاپن") to "🇯🇵",
    // کشورهای دیگر: برای سرورهایی که بعداً به پنل اضافه می‌شوند (اتصال مستقیم هم از روی همین تشخیص داده می‌شود)
    listOf("ITALY", "ROME", "MILAN", "ایتالیا") to "🇮🇹",
    listOf("SPAIN", "MADRID", "اسپانیا") to "🇪🇸",
    listOf("CH", "SWITZERLAND", "ZURICH", "سوئیس") to "🇨🇭",
    listOf("BELGIUM", "BRUSSELS", "بلژیک") to "🇧🇪",
    listOf("CZ", "CZECH", "PRAGUE", "چک") to "🇨🇿",
    listOf("RO", "ROMANIA", "BUCHAREST", "رومانی") to "🇷🇴",
    listOf("BG", "BULGARIA", "SOFIA", "بلغارستان") to "🇧🇬",
    listOf("UA", "UKRAINE", "KYIV", "KIEV", "اوکراین") to "🇺🇦",
    listOf("KZ", "KAZAKHSTAN", "قزاقستان") to "🇰🇿",
    listOf("GE", "GEORGIA", "TBILISI", "گرجستان") to "🇬🇪",
    listOf("AZ", "AZERBAIJAN", "BAKU", "جمهوری آذربایجان") to "🇦🇿",
    listOf("INDIA", "MUMBAI", "هند") to "🇮🇳",
    listOf("KR", "KOREA", "SEOUL", "کره") to "🇰🇷",
    listOf("HK", "HONGKONG", "HONG KONG", "هنگ کنگ") to "🇭🇰",
    listOf("AU", "AUSTRALIA", "SYDNEY", "استرالیا") to "🇦🇺",
    listOf("BR", "BRAZIL", "برزیل") to "🇧🇷",
    listOf("IE", "IRELAND", "DUBLIN", "ایرلند") to "🇮🇪",
    listOf("NORWAY", "OSLO", "نروژ") to "🇳🇴",
    listOf("DK", "DENMARK", "COPENHAGEN", "دانمارک") to "🇩🇰",
    listOf("LT", "LITHUANIA", "لیتوانی") to "🇱🇹",
    listOf("LV", "LATVIA", "RIGA", "لتونی") to "🇱🇻",
    listOf("EE", "ESTONIA", "TALLINN", "استونی") to "🇪🇪",
    listOf("PT", "PORTUGAL", "LISBON", "پرتغال") to "🇵🇹",
    listOf("GR", "GREECE", "ATHENS", "یونان") to "🇬🇷",
    listOf("HU", "HUNGARY", "BUDAPEST", "مجارستان") to "🇭🇺",
    listOf("RS", "SERBIA", "BELGRADE", "صربستان") to "🇷🇸",
    listOf("SA", "SAUDI", "RIYADH", "عربستان") to "🇸🇦",
    listOf("QA", "QATAR", "DOHA", "قطر") to "🇶🇦",
    listOf("KW", "KUWAIT", "کویت") to "🇰🇼",
    listOf("IQ", "IRAQ", "BAGHDAD", "عراق") to "🇮🇶",
    listOf("THAILAND", "BANGKOK", "تایلند") to "🇹🇭",
    listOf("VN", "VIETNAM", "ویتنام") to "🇻🇳",
    listOf("INDONESIA", "JAKARTA", "اندونزی") to "🇮🇩",
    listOf("MALAYSIA", "مالزی") to "🇲🇾",
    listOf("MD", "MOLDOVA", "مولداوی") to "🇲🇩",
    listOf("LU", "LUXEMBOURG", "لوکزامبورگ") to "🇱🇺",
    listOf("CY", "CYPRUS", "قبرس") to "🇨🇾",
)

/** پرچم را از اسم سرور تشخیص می‌دهد (فقط کلمه‌های کامل) */
fun flagOf(name: String): String {
    val tokens = name.uppercase().split(Regex("[^A-Z\\u0600-\\u06FF]+")).filter { it.isNotBlank() }.toSet()
    return flagTable.firstOrNull { (keys, _) -> keys.any { it in tokens || (it.length > 3 && name.contains(it, ignoreCase = true)) } }?.second ?: "🌐"
}

fun protoTitle(p: String) = when (p) {
    "amneziawg" -> "AmneziaWG"
    "amneziawg-direct" -> "Amnezia Direct"
    "wireguard" -> "WireGuard"
    "hysteria2" -> "Hysteria2"
    "xray" -> "Xray"
    "ikev2" -> "IKEv2"
    "ikev2-direct" -> "IKEv2 Direct"
    "openvpn/udp" -> "OpenVPN UDP"
    "openvpn/tcp" -> "OpenVPN TCP"
    else -> "OpenVPN"
}

fun pingColor(ms: Long) = when {
    ms < 0 -> K.Red
    ms < 90 -> K.Green
    ms < 200 -> K.Gold
    else -> K.Red
}

fun dnsTitle(dns: String) = DnsOptions.list.firstOrNull { it.first == dns }?.second ?: "دلخواه · $dns"

fun protoShort(p: String) = when (p) {
    "amneziawg" -> "Amnezia"
    "amneziawg-direct" -> "Amnezia"
    "wireguard" -> "WireGuard"
    "hysteria2" -> "Hysteria2"
    "xray" -> "Xray"
    "ikev2", "ikev2-direct" -> "IKEv2"
    else -> p
}
