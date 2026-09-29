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
