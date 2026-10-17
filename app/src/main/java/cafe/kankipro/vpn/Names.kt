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
    "kankimix" -> "KANKI MIX"
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
    "kankimix" -> "KANKI MIX"
    "amneziawg" -> "Amnezia"
    "amneziawg-direct" -> "Amnezia"
    "wireguard" -> "WireGuard"
    "hysteria2" -> "Hysteria2"
    "xray" -> "Xray"
    "ikev2", "ikev2-direct" -> "IKEv2"
    else -> p
}

/** اسم کشور از روی کد ISO (فارسی، یا انگلیسی وقتی زبان انگلیسی است)؛ null اگر ناشناخته باشد */
fun countryName(iso: String?): String? {
    val f = flagEmoji(iso) ?: return null
    val keys = flagTable.firstOrNull { it.second == f }?.first ?: return null
    return if (LangState.en) {
        keys.firstOrNull { k -> k.length > 3 && k.all { it in 'A'..'Z' } }?.lowercase()?.replaceFirstChar { it.uppercase() }
    } else {
        keys.lastOrNull { k -> k.any { it in '؀'..'ۿ' } }
    }
}

/** شهر مرکز داده‌ی معمول هر کشور (همان نقطه‌ای که روی نقشه نشان داده می‌شود) */
fun cityName(iso: String?): String? {
    val fa = when (iso?.uppercase()) {
        "DE" -> "فرانکفورت" to "Frankfurt"; "TR" -> "استانبول" to "Istanbul"; "NL" -> "آمستردام" to "Amsterdam"
        "FI" -> "هلسینکی" to "Helsinki"; "FR" -> "پاریس" to "Paris"; "GB" -> "لندن" to "London"
        "US" -> "واشنگتن" to "Washington"; "AE" -> "دبی" to "Dubai"; "RU" -> "مسکو" to "Moscow"
        "SE" -> "استکهلم" to "Stockholm"; "CA" -> "تورنتو" to "Toronto"; "PL" -> "ورشو" to "Warsaw"
        "AT" -> "وین" to "Vienna"; "AM" -> "ایروان" to "Yerevan"; "SG" -> "سنگاپور" to "Singapore"
        "JP" -> "توکیو" to "Tokyo"; "IR" -> "تهران" to "Tehran"
        else -> return null
    }
    return if (LangState.en) fa.second else fa.first
}

/** رقم‌های لاتین را در حالت فارسی به رقم فارسی تبدیل می‌کند (برای عددهای ساده مثل روز و گیگ) */
fun faDigits(s: String): String =
    if (LangState.en) s else buildString { s.forEach { c -> append(if (c in '0'..'9') ('۰' + (c - '0')) else if (c == '.') '٫' else c) } }

/** اسم فارسی چند شهر رایج مرکز داده (شهری که پنل یا ipinfo برای IP خروجی برمی‌گرداند) */
private val cityFa = mapOf(
    "frankfurt am main" to "فرانکفورت", "frankfurt" to "فرانکفورت", "berlin" to "برلین", "nuremberg" to "نورنبرگ",
    "falkenstein" to "فالکن‌اشتاین", "munich" to "مونیخ", "dusseldorf" to "دوسلدورف", "hamburg" to "هامبورگ",
    "istanbul" to "استانبول", "ankara" to "آنکارا", "izmir" to "ازمیر", "bursa" to "بورسا", "antalya" to "آنتالیا",
    "amsterdam" to "آمستردام", "rotterdam" to "روتردام", "helsinki" to "هلسینکی", "paris" to "پاریس",
    "london" to "لندن", "manchester" to "منچستر", "stockholm" to "استکهلم", "warsaw" to "ورشو", "vienna" to "وین",
    "yerevan" to "ایروان", "moscow" to "مسکو", "dubai" to "دبی", "toronto" to "تورنتو", "singapore" to "سنگاپور",
    "tokyo" to "توکیو", "milan" to "میلان", "madrid" to "مادرید", "zurich" to "زوریخ", "tbilisi" to "تفلیس",
    "baku" to "باکو", "tehran" to "تهران",
)

/** شهر خروجی برای نمایش: شهری که از IP خروجی آمده (فارسی‌شده اگر شناخته‌شده است)، وگرنه شهر معمول آن کشور */
fun exitCityName(iso: String?, exitCity: String?): String? {
    val c = exitCity?.trim().orEmpty()
    if (c.isEmpty()) return cityName(iso)
    if (LangState.en) return c
    return cityFa[c.lowercase()] ?: c
}
