package cafe.kankipro.vpn

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** یک کانفیگ قابل دانلود از صفحه‌ی اشتراک */
data class ConfigLink(
    val protocol: String,   // مثل amneziawg ، wireguard ، hysteria2
    val node: String,       // مثل local
    val nodeName: String,   // مثل GERMANY-GAMING
    val url: String,
)

/** اطلاعات حساب کاربر که از صفحه‌ی اشتراک خوانده می‌شود */
data class Account(
    val username: String,
    val status: String,
    val dataRemaining: String,
    val used: String,
    val connections: String,
    val timeRemaining: String,
    val expiry: String,
    val configs: List<ConfigLink>,
    /** زمان انقضا (میلی‌ثانیه)؛ null یعنی نامحدود */
    val expiresAt: Long? = null,
    /** کل حجم و حجم مصرفی به گیگابایت (اگر پنل داده باشد) */
    val limitGb: Double? = null,
    val usedGb: Double? = null,
    /** پیامی که ادمین در پنل (ربات فروش › پیام اپ) نوشته؛ ربات صفحه‌ی اول نشانش می‌دهد */
    val notice: AppNotice? = null,
    /** آدرس دامنه‌ی اضطراری که ادمین در پنل گذاشته (https://…)؛ null یعنی پنل قدیمی است */
    val emergency: String? = null,
    /** کلید عمومی امضای پیام‌های ادمین در چت اضطراری (base64) */
    val emergencyKey: String? = null,
    /** DNS کانکی (رفع تحریم)؛ null یعنی این کاربر DNS ندارد */
    val dns: DnsInfo? = null,
    /** لینک‌های Xray این کاربر (vless:// vmess:// trojan:// ss://) */
    val xray: List<XrayLink> = emptyList(),
    /** خروجی واقعی هر سرور (شناسه‌ی نود ← IP، کشور، شهر) که پنل ۳.۵.۷ به بعد می‌دهد؛ خالی یعنی پنل قدیمی */
    val exits: Map<String, ExitInfo> = emptyMap(),
)

/** جایی که ترافیک یک سرور واقعاً از آن به اینترنت می‌رود (برای کانفیگ تانل، سرور خارج؛ نه سرور ایران) */
data class ExitInfo(val ip: String, val iso: String, val city: String, val geo: Geo?)

/** یک سرور DNS کانکی. role: relay (داخل ایران) یا exit (خارج) */
data class DnsServer(
    val id: String,
    val name: String,
    val note: String,
    val role: String,
    val ip: String,
    val host: String,
    /** آدرس Private DNS (DoT) مخصوص همین کاربر */
    val dot: String,
    val doh: String,
    /** آدرس ثبت IP (HTTPS) و نسخه‌ی HTTP آن روی خود IP */
    val register: String,
    val registerHttp: String,
    /** ریلی (داخل ایران): شناسه و اسم سرور خارجی که تانلش به آن می‌رسد */
    val exit: String = "",
    val exitName: String = "",
)

data class DnsInfo(val token: String, val servers: List<DnsServer>)

data class XrayLink(val name: String, val link: String)

/** پیام ادمین. until: زمان پایان به میلی‌ثانیه (۰ یعنی تا وقتی ادمین پاکش کند) */
data class AppNotice(val id: String, val text: String, val until: Long)

object SubscriptionApi {
    /** اگر کاربر فقط کد را وارد کند، از این آدرس استفاده می‌شود (سرور ایران) */
    const val DEFAULT_HOST = "https://sub.kanki.pro:2053"

    /** آدرس قدیمی؛ اگر آدرس اصلی جواب نداد از این خوانده می‌شود */
    const val FALLBACK_HOST = "https://fi.kankipro.cafe:2053"

    /**
     * آدرس‌هایی که به ترتیب امتحان می‌شوند. اگر پنل کاربر یکی از دو آدرس کانکی باشد،
     * اول آدرس اصلی (ایران) و بعد آدرس قدیمی؛ پنل‌های دیگر فقط خودشان.
     */
    fun hosts(base: String): List<String> {
        val b = base.trimEnd('/')
        return if (b == DEFAULT_HOST || b == FALLBACK_HOST) listOf(DEFAULT_HOST, FALLBACK_HOST) else listOf(b)
    }

    /** همان آدرس روی میزبان‌های جایگزین (برای لینک کانفیگ‌ها وقتی میزبان اصلی در دسترس نیست) */
    fun alternates(url: String): List<String> {
        val h = listOf(DEFAULT_HOST, FALLBACK_HOST).firstOrNull { url.startsWith("$it/") } ?: return listOf(url)
        val path = url.removePrefix(h)
        return listOf(url) + listOf(DEFAULT_HOST, FALLBACK_HOST).filter { it != h }.map { it + path }
    }

    /** مثل fetch، ولی اگر میزبان جواب نداد، میزبان جایگزین امتحان می‌شود */
    fun fetchAny(url: String): String {
        var last: Exception? = null
        for (u in alternates(url)) {
            try { return fetch(u) } catch (e: Exception) { last = e }
        }
        throw last ?: IOException("no host")
    }

    /** کد اشتراک: kanki-<شماره>-<رمز> (کدهای قدیمی با پیشوند دیگر هم قبول می‌شوند) */
    private val codeRegex = Regex("""\b[A-Za-z]{2,12}-\d+-[A-Za-z0-9]{4,}""")

    /** لینک کامل یا کد خالی را می‌گیرد و (آدرس پنل، کد) برمی‌گرداند */
    fun normalize(input: String): Pair<String, String>? {
        val s = input.trim()
        val full = Regex("""(https://[^/\s]+)\S*?/sub/([A-Za-z]{2,12}-\d+-[A-Za-z0-9]{4,})""").find(s)
        if (full != null) return full.groupValues[1] to full.groupValues[2]
        val code = codeRegex.find(s) ?: return null
        return DEFAULT_HOST to code.value
    }

    fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "KankiVPN-Android")
        try {
            val code = c.responseCode
            if (code != 200) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** دریافت فایل به‌صورت خام (برای گواهی‌ها) */
    fun fetchBytes(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    fun load(base: String, code: String): Account = parse(fetch("$base/sub/$code"), base)

    private fun decode(s: String) = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")

    private fun strip(s: String) = decode(s.replace(Regex("<[^>]+>"), "")).trim()

    private val protocolTitles = setOf("openvpn", "wireguard", "amneziawg", "hysteria2")

    /** پیام ادمین از JSON داخل صفحه‌ی اشتراک (<script id="data">)؛ پنل‌های قدیمی آن را ندارند */
    /** JSON داخل <script id="data"> صفحه‌ی اشتراک؛ null اگر نباشد */
    private fun dataJson(html: String): org.json.JSONObject? {
        val raw = Regex("""(?is)<script[^>]*id="data"[^>]*>(.*?)</script>""").find(html)?.groupValues?.get(1) ?: return null
        return runCatching { org.json.JSONObject(raw) }.getOrNull()
    }

    private fun notice(html: String): AppNotice? {
        val raw = Regex("""(?is)<script[^>]*id="data"[^>]*>(.*?)</script>""").find(html)?.groupValues?.get(1) ?: return null
        val m = runCatching { org.json.JSONObject(raw).optJSONObject("app_msg") }.getOrNull() ?: return null
        val text = m.optString("text").trim()
        return if (text.isEmpty()) null else AppNotice(m.optString("id"), text, m.optLong("until") * 1000)
    }

    fun parse(html: String, base: String): Account {
        val clean = html.replace(Regex("(?is)<script.*?</script>|<style.*?</style>"), "")
        val lines = decode(clean.replace(Regex("<[^>]+>"), "\n"))
            .lines().map { it.trim() }.filter { it.isNotEmpty() }

        fun after(label: String, skip: Int = 1): String {
            val i = lines.indexOfFirst { it.equals(label, ignoreCase = true) }
            return if (i >= 0 && i + skip < lines.size) lines[i + skip] else "—"
        }

        val username = Regex("(?is)<h1[^>]*>(.*?)</h1>").find(clean)
            ?.groupValues?.get(1)?.let { strip(it) } ?: ""
        val status = lines.firstOrNull {
            it in setOf("Active", "Expired", "Disabled", "Limited", "Frozen", "Inactive", "Suspended")
        } ?: "—"

        // عنوان‌های h3 با موقعیتشان، برای پیدا کردن اسم سرور هر کانفیگ
        val headings = Regex("(?is)<h3[^>]*>(.*?)</h3>").findAll(clean)
            .map { it.range.first to strip(it.groupValues[1]) }
            .filter { it.second.lowercase() !in protocolTitles }
            .toList()

        val linkRe = Regex("""(?:https?://[^"'\s<>]+)?/api/config/(\d+)/([a-z0-9]+(?:/[a-z0-9]+)?)\?[^"'\s<>]+""")
        val configs = linkRe.findAll(clean).mapNotNull { m ->
            val raw = decode(m.value)
            // config links carry private keys: only fetch them from the panel we logged in to
            val url = if (raw.startsWith("http")) raw else base + raw
            if (!url.startsWith("$base/")) return@mapNotNull null
            val node = Regex("""[?&]node=([^&]+)""").find(raw)?.groupValues?.get(1) ?: "local"
            val name = headings.lastOrNull { it.first < m.range.first }?.second ?: node
            // Hysteria2 در صفحه‌ی اشتراک فقط دکمه‌ی QR دارد؛ همان آدرس بدون /qr متن لینک hysteria2:// را می‌دهد
            if (m.groupValues[2].startsWith("hysteria2"))
                ConfigLink(protocol = "hysteria2", node = node, nodeName = name, url = url.replace(Regex("/hysteria2/[a-z0-9]+\\?"), "/hysteria2?"))
            // KANKI MIX هم مثل Hysteria2 فقط QR دارد؛ همان آدرس بدون /qr متن لینک kanki:// را می‌دهد
            else if (m.groupValues[2].startsWith("kankimix"))
                ConfigLink(protocol = "kankimix", node = node, nodeName = name, url = url.replace(Regex("/kankimix/[a-z0-9]+\\?"), "/kankimix?"))
            else ConfigLink(protocol = m.groupValues[2], node = node, nodeName = name, url = url)
        }.distinctBy { it.url }.toList()

        // اطلاعات واقعی که پنل در data-* صفحه گذاشته (مرورگر با جاوااسکریپت از همین‌ها حساب می‌کند)
        fun attr(name: String) = Regex("""$name="([^"]*)"""").find(html)?.groupValues?.get(1)?.trim().orEmpty()
        val expiresAt = PanelTime.parse(attr("data-expires"))
        val limitGb = attr("data-traffic-limit").toDoubleOrNull()
        val usedGb = attr("data-traffic-used").toDoubleOrNull()

        return Account(
            username = username,
            status = status,
            dataRemaining = if (limitGb != null && usedGb != null && limitGb > 0)
                String.format(java.util.Locale.US, "%.2f GB", (limitGb - usedGb).coerceAtLeast(0.0)) else after("Data remaining"),
            used = if (usedGb != null) String.format(java.util.Locale.US, "%.2f GB", usedGb) else after("Used traffic"),
            connections = after("Connections"),
            timeRemaining = if (expiresAt != null) PanelTime.remaining(expiresAt) else "نامحدود",
            expiry = if (expiresAt != null) PanelTime.jalali(expiresAt) else "—",
            configs = configs,
            expiresAt = expiresAt,
            limitGb = limitGb,
            usedGb = usedGb,
            notice = notice(html),
            emergency = dataJson(html)?.optString("emergency")?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            emergencyKey = dataJson(html)?.optString("emergency_key")?.takeIf { it.isNotBlank() },
            dns = dataJson(html)?.optJSONObject("dns")?.let { parseDns(it) },
            xray = dataJson(html)?.optJSONArray("xray")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }
                    .map { XrayLink(it.optString("name"), it.optString("link").trim()) }
                    .filter { XrayCfg.supported(it.link) }
            } ?: emptyList(),
            exits = dataJson(html)?.optJSONArray("nodes")?.let { parseExits(it) } ?: emptyMap(),
        )
    }

    private fun parseExits(a: org.json.JSONArray): Map<String, ExitInfo> =
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.mapNotNull { n ->
            val id = n.optString("id")
            val cc = n.optString("exit_cc").trim().uppercase()
            if (id.isBlank() || cc.length != 2) return@mapNotNull null
            val geo = if (n.has("exit_lat") && n.has("exit_lon")) Geo(n.optDouble("exit_lat"), n.optDouble("exit_lon"))
                .takeIf { !it.lat.isNaN() && !it.lon.isNaN() } else null
            id to ExitInfo(n.optString("exit_ip"), cc, n.optString("exit_city"), geo)
        }.toMap()

    private fun parseDns(o: org.json.JSONObject): DnsInfo? {
        val arr = o.optJSONArray("servers") ?: return null
        val list = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }.map { x ->
            DnsServer(
                id = x.optString("id"), name = x.optString("name"), note = x.optString("note"), role = x.optString("role"),
                ip = x.optString("ip"), host = x.optString("host"), dot = x.optString("dot"), doh = x.optString("doh"),
                register = x.optString("register"), registerHttp = x.optString("register_http"),
                exit = x.optString("exit"), exitName = x.optString("exit_name"),
            )
        }.filter { it.ip.isNotBlank() || it.dot.isNotBlank() }
        return if (list.isEmpty()) null else DnsInfo(o.optString("token"), list)
    }
}

/** اطلاعات قابل نمایش یک کانفیگ (بدون کلیدها) */
data class ConfigInfo(
    val endpoint: String,
    val address: String,
    val dns: String,
    val mtu: String,
    val obfuscation: String?,
    val transport: String = "UDP",
    val cipher: String? = null,
) {
    val host: String get() = endpoint.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
    val port: Int get() = endpoint.substringAfterLast(":", "").toIntOrNull() ?: 0

    companion object {
        fun parse(text: String): ConfigInfo {
            if (text.lines().any { it.trim().startsWith("remote ") }) return parseOvpn(text)
            if (KmixUri.isKmix(text)) {
                val u = runCatching { KmixUri.parse(text) }.getOrNull()
                return ConfigInfo(
                    endpoint = u?.let { "${it.host}:${it.port}" } ?: "—", address = "—", dns = "—", mtu = "1280",
                    obfuscation = u?.let { "ChaCha20 · KCP + FEC ${it.fecData}+${it.fecParity}" + if (it.ports != null) " · ${it.ports.first}-${it.ports.last}" else "" },
                    transport = "UDP", cipher = "KANKI MIX",
                )
            }
            if (XrayCfg.isXray(text)) {
                val link = text.lines().firstOrNull { it.trim().startsWith("URI", ignoreCase = true) }?.substringAfter("=")?.trim().orEmpty()
                val (h, p) = runCatching { XrayCfg.endpoint(link) }.getOrDefault("—" to 0)
                val detail = XrayCfg.detail(link)
                // mKCP روی UDP است؛ بقیه‌ی ترنسپورت‌های Xray روی TCP
                return ConfigInfo(endpoint = "$h:$p", address = "—", dns = "—", mtu = "خودکار", obfuscation = detail.ifBlank { null }, transport = if (detail.contains("KCP")) "UDP" else "TCP", cipher = XrayCfg.protoName(link))
            }
            val m = mutableMapOf<String, String>()
            text.lines().forEach { line ->
                val p = line.split("=", limit = 2)
                if (p.size == 2) m.putIfAbsent(p[0].trim().lowercase(), p[1].trim())
            }
            val jc = m["jc"]
            return ConfigInfo(
                endpoint = m["endpoint"] ?: "—",
                address = m["address"] ?: "—",
                dns = m["dns"] ?: "—",
                mtu = m["mtu"] ?: "خودکار",
                obfuscation = if (jc != null) "فعال · Jc $jc" else null,
            )
        }

        /** خواندن اطلاعات فایل OpenVPN */
        fun parseOvpn(text: String): ConfigInfo {
            fun first(key: String) = text.lines().map { it.trim() }.firstOrNull { it.startsWith("$key ") }
                ?.split(Regex("\\s+"))?.drop(1)
            val remote = first("remote")
            val proto = first("proto")?.firstOrNull() ?: "udp"
            val host = remote?.getOrNull(0) ?: "—"
            val port = remote?.getOrNull(1) ?: "1194"
            val tcp = proto.startsWith("tcp") || remote?.getOrNull(2)?.startsWith("tcp") == true
            val cipher = first("data-ciphers")?.firstOrNull() ?: first("cipher")?.firstOrNull()
            return ConfigInfo(
                endpoint = "$host:$port",
                address = "خودکار (از سرور)",
                dns = "از سرور",
                mtu = first("tun-mtu")?.firstOrNull() ?: "خودکار",
                obfuscation = null,
                transport = if (tcp) "TCP" else "UDP",
                cipher = cipher,
            )
        }
    }
}

/** اعمال MTU دلخواه روی کانفیگ وایرگارد/امنزیا */
fun applyMtu(cfg: String, mtu: String): String {
    val v = mtu.trim().toIntOrNull() ?: return cfg
    val lines = cfg.lines().toMutableList()
    val i = lines.indexOfFirst { it.trim().startsWith("MTU", ignoreCase = true) && it.contains("=") }
    if (i >= 0) lines[i] = "MTU = $v" else {
        val j = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        if (j >= 0) lines.add(j + 1, "MTU = $v")
    }
    return lines.joinToString("\n")
}

/** گزینه‌های DNS و اعمال آن روی متن کانفیگ */
object DnsOptions {
    val list = listOf(
        "" to "پیش‌فرض سرور",
        "1.1.1.1, 1.0.0.1" to "Cloudflare",
        "8.8.8.8, 8.8.4.4" to "Google",
        "94.140.14.14, 94.140.15.15" to "AdGuard · ضد تبلیغ",
        "9.9.9.9, 149.112.112.112" to "Quad9 · امن",
    )

    fun apply(cfg: String, dns: String): String {
        if (dns.isBlank()) return cfg
        val lines = cfg.lines().toMutableList()
        val i = lines.indexOfFirst { it.trim().startsWith("DNS", ignoreCase = true) && it.contains("=") }
        if (i >= 0) {
            lines[i] = "DNS = $dns"
        } else {
            val j = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
            if (j >= 0) lines.add(j + 1, "DNS = $dns")
        }
        return lines.joinToString("\n")
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0)
    b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.2f MB", b / 1048576.0)
    b >= 1L shl 10 -> String.format(java.util.Locale.US, "%.1f KB", b / 1024.0)
    else -> "$b B"
}

/** «99.62 GB» را به بایت تبدیل می‌کند؛ اگر نشد null */
fun parseSize(s: String): Double? {
    val m = Regex("""([\d.]+)\s*(TB|GB|MB|KB|B)""", RegexOption.IGNORE_CASE).find(s) ?: return null
    val v = m.groupValues[1].toDoubleOrNull() ?: return null
    return v * when (m.groupValues[2].uppercase()) {
        "TB" -> 1099511627776.0; "GB" -> 1073741824.0; "MB" -> 1048576.0; "KB" -> 1024.0; else -> 1.0
    }
}


/** تبدیل تاریخ انقضای پنل به «X روز و Y ساعت» و تاریخ شمسی */
object PanelTime {
    /** زمان ISO (مثل 2026-12-13T05:21:37Z) → میلی‌ثانیه؛ تاریخ خالی یا قدیمی یعنی نامحدود */
    fun parse(v: String): Long? {
        if (v.isBlank()) return null
        val ms = runCatching { java.time.Instant.parse(v).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(v).toInstant().toEpochMilli() }.getOrNull()
            ?: return null
        return if (ms < 946684800000L) null else ms // قبل از سال ۲۰۰۰ = بدون انقضا
    }

    fun remaining(at: Long, now: Long = System.currentTimeMillis()): String {
        val diff = at - now
        if (diff <= 0) return "منقضی شده"
        val hours = diff / 3_600_000
        val d = hours / 24
        val h = hours % 24
        return when {
            d > 0 && h > 0 -> "$d روز و $h ساعت"
            d > 0 -> "$d روز"
            h > 0 -> "$h ساعت"
            else -> "${(diff / 60_000).coerceAtLeast(1)} دقیقه"
        }
    }

    private val months = listOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")

    fun jalali(at: Long): String {
        val z = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
        val (jy, jm, jd) = toJalali(z.year, z.monthValue, z.dayOfMonth)
        return "$jd ${months[jm - 1]} $jy، " + String.format(java.util.Locale.US, "%02d:%02d", z.hour, z.minute)
    }

    private fun toJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 + gd + gdm[gm - 1]
        var jy = -1595 + 33 * (days / 12053); days %= 12053
        jy += 4 * (days / 1461); days %= 1461
        if (days > 365) { jy += (days - 1) / 365; days = (days - 1) % 365 }
        return if (days < 186) Triple(jy, 1 + days / 31, 1 + days % 31)
        else Triple(jy, 7 + (days - 186) / 30, 1 + (days - 186) % 30)
    }
}
