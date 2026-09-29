package cafe.kankipro.vpn

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** یک کانفیگ قابل دانلود از صفحه‌ی اشتراک */
data class ConfigLink(
    val protocol: String,   // مثل amneziawg ، wireguard ، openvpn/udp
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
    val configs: List<ConfigLink>,
)

object SubscriptionApi {
    /** اگر کاربر فقط کد را وارد کند، از این آدرس استفاده می‌شود */
    const val DEFAULT_HOST = "https://g.kankipro.cafe"

    private val codeRegex = Regex("""bub-\d+-[A-Za-z0-9]+""")

    /** لینک کامل یا کد خالی را می‌گیرد و (آدرس پنل، کد) برمی‌گرداند */
    fun normalize(input: String): Pair<String, String>? {
        val s = input.trim()
        val full = Regex("""(https?://[^/\s]+)\S*?/sub/(bub-\d+-[A-Za-z0-9]+)""").find(s)
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

    fun load(base: String, code: String): Account = parse(fetch("$base/sub/$code"), base)

    private fun decode(s: String) = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")

    private fun strip(s: String) = decode(s.replace(Regex("<[^>]+>"), "")).trim()

    private val protocolTitles = setOf("openvpn", "wireguard", "amneziawg")

    fun parse(html: String, base: String): Account {
        val clean = html.replace(Regex("(?is)<script.*?</script>|<style.*?</style>"), "")
        val lines = decode(clean.replace(Regex("<[^>]+>"), "\n"))
            .lines().map { it.trim() }.filter { it.isNotEmpty() }

        fun after(label: String): String {
            val i = lines.indexOfFirst { it.equals(label, ignoreCase = true) }
            return if (i >= 0 && i + 1 < lines.size) lines[i + 1] else "—"
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

        val linkRe = Regex("""(?:https?://[^"'\s<>]+)?/api/config/(\d+)/([a-z]+(?:/[a-z]+)?)\?[^"'\s<>]+""")
        val configs = linkRe.findAll(clean).mapNotNull { m ->
            val raw = decode(m.value)
            val url = if (raw.startsWith("http")) raw else base + raw
            val node = Regex("""[?&]node=([^&]+)""").find(raw)?.groupValues?.get(1) ?: "local"
            val name = headings.lastOrNull { it.first < m.range.first }?.second ?: node
            ConfigLink(protocol = m.groupValues[2], node = node, nodeName = name, url = url)
        }.distinctBy { it.url }.toList()

        return Account(
            username = username,
            status = status,
            dataRemaining = after("Data remaining"),
            used = after("Used traffic"),
            connections = after("Connections"),
            timeRemaining = after("Time remaining"),
            configs = configs,
        )
    }
}

/** اطلاعات قابل نمایش یک کانفیگ (بدون کلیدها) */
data class ConfigInfo(
    val endpoint: String,
    val address: String,
    val dns: String,
    val mtu: String,
    val obfuscation: String?,
) {
    companion object {
        fun parse(text: String): ConfigInfo {
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
    }
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
