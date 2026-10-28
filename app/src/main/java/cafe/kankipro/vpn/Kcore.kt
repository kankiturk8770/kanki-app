package cafe.kankipro.vpn

import java.io.File
import java.net.URLDecoder

/*
 * سه پروتکل خود کانکی کنار KANKI MIX: Game-FEC، HQ-QUIC و FakeTCP
 * ------------------------------------------------------------------
 * هر سه با یک هسته اجرا می‌شوند: libkcore.so (برنامه‌ی kanki-core از ریپوی پنل، release تگ kanki-core).
 * مثل Xray: هسته یک پراکسی SOCKS5 روی 127.0.0.1 باز می‌کند و hev-socks5-tunnel همه‌ی ترافیک گوشی را به آن
 * می‌دهد. لینک‌ها kcore://کاربر:کلید@سرور:پورت?type=game|hq|faketcp&door=… از اشتراک می‌آیند (فیلد kcore هر سرور).
 *
 *  - Game-FEC (kgame): بسته‌های بازی دیتاگرام جدا می‌روند و بسته‌های کوچک دو یا سه بار (پابجی)، سایت‌ها روی KCP + FEC.
 *  - HQ-QUIC (khq): QUIC زیر ماسک با BBR؛ برای اینترنت پرلاس.
 *  - FakeTCP (kftcp): UDP داخل TCP ساختگی؛ بسته‌ی خام لازم دارد، پس فقط روی گوشی روت‌شده (هسته با su اجرا می‌شود).
 */
object KcoreCfg {
    /** پروتکل‌ها به ترتیب نمایش: شناسه در اپ و پنل ← نوع هسته (type لینک) */
    val PROTOS = listOf("kgame" to "game", "khq" to "hq", "kftcp" to "faketcp")

    fun isProto(p: String) = PROTOS.any { it.first == p }

    fun title(p: String) = when (p) {
        "kgame" -> "Game-FEC"
        "khq" -> "HQ-QUIC"
        "kftcp" -> "FakeTCP"
        else -> p
    }

    fun supported(link: String) = link.trim().startsWith("kcore://")

    /** متنی که برنامه نگه می‌دارد (مثل Xray، شبیه کانفیگ وایرگارد تا DNS و MTU و برنامه‌های مستثنی رویش اعمال شوند) */
    fun wrap(link: String): String {
        val (h, p) = endpoint(link)
        return "[Interface]\n[Kcore]\nEndpoint = $h:$p\nType = ${kind(link)}\nURI = ${link.trim()}\n"
    }

    fun isKcore(text: String) = text.lines().any { it.trim().equals("[Kcore]", ignoreCase = true) }

    /** نوع هسته: game، hq یا faketcp */
    fun kind(link: String): String = query(link)["type"] ?: "game"

    private fun query(link: String): Map<String, String> =
        link.substringAfter("?", "").substringBefore("#").split("&").filter { it.contains("=") }.associate {
            val p = it.split("=", limit = 2)
            p[0] to runCatching { URLDecoder.decode(p[1], "UTF-8") }.getOrDefault(p[1])
        }

    /** (میزبان، پورت) سرور */
    fun endpoint(link: String): Pair<String, Int> {
        val hp = link.substringAfter("://").substringBefore("?").substringBefore("#").substringAfterLast("@").trimEnd('/')
        return if (hp.startsWith("[")) {
            val i = hp.indexOf(']')
            hp.substring(1, i) to (hp.substring(i + 2).toIntOrNull() ?: error("kcore: bad port"))
        } else hp.substringBeforeLast(":") to (hp.substringAfterLast(":").toIntOrNull() ?: error("kcore: bad port"))
    }

    /** همان لینک با آی‌پی به‌جای اسم سرور (هسته به همان آی‌پی وصل شود که از VPN بیرون گذاشته شده) */
    fun withHost(link: String, ip: String): String {
        val (h, p) = endpoint(link)
        val host = if (ip.contains(':')) "[$ip]" else ip
        val old = if (h.contains(':')) "[$h]:$p" else "$h:$p"
        val head = link.substringBefore("?")
        return head.substringBeforeLast(old) + "$host:$p" + link.removePrefix(head)
    }

    /** تعداد نسخه‌ی هر بسته‌ی بازی را عوض می‌کند (۱ تا ۳) */
    fun withCopies(link: String, copies: Int): String {
        val c = copies.coerceIn(1, 3)
        val base = link.substringBefore("#")
        val frag = link.substringAfter("#", "").let { if (it.isEmpty()) "" else "#$it" }
        val q = base.substringAfter("?", "").split("&").filter { it.isNotEmpty() && !it.startsWith("copies=") } + "copies=$c"
        return base.substringBefore("?") + "?" + q.joinToString("&") + frag
    }

    /** گوشی روت دارد؟ (FakeTCP بسته‌ی خام TCP می‌سازد و بدون روت ممکن نیست) */
    val rooted: Boolean by lazy {
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/data/adb/magisk", "/system/app/Superuser.apk")
            .any { runCatching { File(it).exists() }.getOrDefault(false) }
    }

    /** این پروتکل روی این گوشی کار می‌کند؟ */
    fun usable(p: String) = p != "kftcp" || rooted
}
