package cafe.kankipro.vpn

import java.io.IOException

/**
 * What a user may paste into the login box, and the panel addresses of the build (1.45.1).
 *
 * Every shape a Kanki panel hands out is read here, after invisible characters (RTL marks, zero-width spaces,
 * a BOM, line breaks from a chat message) are dropped and Persian/Arabic digits become ASCII:
 *  - `kanki-<id>-<code>` (the bot's activation code), alone or inside any text
 *  - the bare code (only the digits of `<code>`); a panel from 3.67.2 finds its account from the header
 *  - a full link: `/sub/kanki-<id>-<code>`, `/sub/<code>`, `/sub/h/<hash>`, `/sub/me?sub=…`, `/api/config/…?sub=…`,
 *    on the panel or on an Iran relay (`https://<relay>:2053/sub/…`), with or without `https://`, and a doubled
 *    `/sub/sub/` collapses to one.
 */
object PanelLink {
    /** the bot's activation code (older prefixes too) */
    val FULL_CODE = Regex("""[A-Za-z]{2,12}-\d{1,12}-[A-Za-z0-9]{4,64}""")
    private val FULL_CODE_ONLY = Regex("""^[A-Za-z]{2,12}-\d{1,12}-[A-Za-z0-9]{4,64}$""")
    /** the code part alone (panel 3.69 makes 39 digits, 128 bits and more; older accounts have 19 or other lengths) */
    private val BARE_ONLY = Regex("""^[0-9]{10,40}$""")
    private val HASH_ONLY = Regex("""^[0-9a-f]{40}$""")
    private val HOST = Regex("""^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?)*$""")
    /** a link anywhere in the text, with or without the scheme */
    private val LINK = Regex("""(?i)(?<![@\w.-])(?:https?://)?(?:[a-z0-9-]+\.)+[a-z0-9-]+(?::\d{1,5})?/(?:sub|api/config)/[^\s"'<>]*""")
    private const val MAX_INPUT = 4096

    /** RTL marks, isolates, zero-width characters, the BOM and every control character */
    private fun invisible(c: Char): Boolean =
        c.isISOControl() || c in '​'..'‏' || c in '‪'..'‮' || c in '⁦'..'⁩' || c == '﻿' || c == '­'

    /** text as the user meant it: invisible characters out, Persian/Arabic digits as ASCII */
    fun clean(s: String): String {
        val b = StringBuilder(s.length)
        for (ch in s.take(MAX_INPUT)) {
            when {
                ch == '\n' || ch == '\r' || ch == '\t' -> b.append(' ')
                invisible(ch) -> Unit
                ch in '۰'..'۹' -> b.append('0' + (ch - '۰'))
                ch in '٠'..'٩' -> b.append('0' + (ch - '٠'))
                else -> b.append(ch)
            }
        }
        return b.toString().trim()
    }

    /**
     * `https://host[:port]` of one entry, whatever follows it (`/admin`, `/panel`, `/sub/me`, a trailing `/`) and with
     * the scheme added when missing (`http://` becomes `https://`: the client never speaks plain HTTP). Null for
     * anything that is not a host: a user part (`u@host`), another scheme, a bad port or characters.
     */
    fun origin(entry: String): String? {
        var s = clean(entry).trim('"', '\'', '<', '>', ' ')
        if (s.isEmpty()) return null
        val scheme = s.indexOf("://")
        if (scheme >= 0) {
            val p = s.substring(0, scheme).lowercase()
            if (p != "https" && p != "http") return null
            s = s.substring(scheme + 3)
        }
        val end = s.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }
        val auth = if (end >= 0) s.substring(0, end) else s
        if (auth.isEmpty() || '@' in auth || ' ' in auth) return null
        val colon = auth.lastIndexOf(':')
        val host = (if (colon >= 0) auth.substring(0, colon) else auth).lowercase().trimEnd('.')
        val port = if (colon >= 0) auth.substring(colon + 1).toIntOrNull() ?: return null else 443
        if (port !in 1..65535 || host.length > 253 || !HOST.matches(host)) return null
        return if (port == 443) "https://$host" else "https://$host:$port"
    }

    /** the build's KANKI_PANEL_HOSTS: entries split by commas, semicolons or white space, each made an origin */
    fun hosts(spec: String): List<String> =
        spec.split(',', ';', ' ', '\n', '\r', '\t').mapNotNull { origin(it) }.distinct()

    /** what the login box got */
    sealed class Input {
        /** `kanki-<id>-<code>`; origin null = the build's own hosts */
        data class Code(val origin: String?, val code: String) : Input()
        /** only the digits of the code: the panel finds the account (3.67.2) */
        data class Bare(val origin: String?, val digits: String) : Input()
        /** `/sub/h/<hash>`: good for that panel only; the page names the code */
        data class Hash(val origin: String, val hash: String) : Input()
    }

    /** the input read as one of the shapes above; null = none of them */
    fun parse(raw: String): Input? {
        val s = clean(raw)
        if (s.isEmpty()) return null
        LINK.find(s)?.let { m -> fromLink(m.value)?.let { return it } }
        // a code anywhere in the text (a whole bot message pasted)
        FULL_CODE.find(s)?.let { return Input.Code(null, it.value) }
        val digits = s.filter { it != ' ' && it != '-' }
        if (BARE_ONLY.matches(digits)) return Input.Bare(null, digits)
        return null
    }

    private fun fromLink(link: String): Input? {
        val o = origin(link) ?: return null
        val afterScheme = link.substringAfter("://", link)
        val pathAndQuery = afterScheme.substring(afterScheme.indexOf('/').coerceAtLeast(0))
        val path = pathAndQuery.substringBefore('?').substringBefore('#')
        val query = pathAndQuery.substringAfter('?', "").substringBefore('#')
        val sub = query.split('&').firstOrNull { it.startsWith("sub=") }?.removePrefix("sub=")
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        if (sub != null) {
            if (FULL_CODE_ONLY.matches(sub)) return Input.Code(o, sub)
            if (BARE_ONLY.matches(sub)) return Input.Bare(o, sub)
        }
        val segs = path.split('/').filter { it.isNotEmpty() }.toMutableList()
        val i = segs.indexOf("sub")
        if (i < 0) return null
        var rest = segs.drop(i + 1)
        while (rest.firstOrNull() == "sub") rest = rest.drop(1)
        val first = rest.firstOrNull() ?: return null
        return when {
            first == "h" -> rest.getOrNull(1)?.lowercase()?.takeIf { HASH_ONLY.matches(it) }?.let { Input.Hash(o, it) }
            FULL_CODE_ONLY.matches(first) -> Input.Code(o, first)
            BARE_ONLY.matches(first) -> Input.Bare(o, first)
            else -> null
        }
    }

    /**
     * The account's code named in its own page (a config link's `sub=`, an app download `/sub/<code>/app/`).
     * `endsWith`: the digits the user typed; the code found must be theirs.
     */
    fun codeInPage(html: String, endsWith: String? = null): String? =
        FULL_CODE.findAll(html).map { it.value }
            .firstOrNull { c -> endsWith == null || c.endsWith("-$endsWith") }
}

/** why the subscription could not be read, told apart so the user sees what to do */
class PanelError(val kind: Kind, cause: Throwable? = null) : IOException(kind.name, cause) {
    enum class Kind { NO_HOSTS, BAD_INPUT, DNS, NETWORK, TLS, PIN, INVALID_CODE, RELAY, HTTP, SEAL, PARSE, BARE_UNSUPPORTED }

    /** the message the user sees */
    val persian: String get() = when (kind) {
        Kind.NO_HOSTS -> "این نسخه آدرس پنل ندارد (KANKI_PANEL_HOSTS هنگام ساخت داده نشده). لینک کامل اشتراک را وارد کنید."
        Kind.BAD_INPUT -> "لینک یا کد اشتراک معتبر نیست. کد فعال‌سازی را از ربات کپی کنید."
        Kind.DNS -> "آدرس پنل پیدا نشد (DNS). اینترنت یا DNS گوشی را بررسی کنید."
        Kind.NETWORK -> "پنل در دسترس نیست (اینترنت قطع است یا آدرس مسدود شده). دوباره امتحان کنید."
        Kind.TLS -> "اتصال امن به پنل برقرار نشد (گواهی TLS). تاریخ و ساعت گوشی را بررسی کنید."
        Kind.PIN -> "گواهی پنل با گواهی مورد انتظار این نسخه یکی نیست (پین TLS). نسخه‌ی تازه‌ی برنامه را نصب کنید."
        Kind.INVALID_CODE -> "این کد اشتراک پیدا نشد یا معتبر نیست (۴۰۱/۴۰۴). کد را دوباره از ربات کپی کنید."
        Kind.RELAY -> "سرور واسط اشتراک جواب درست نداد (۵۰۲ یا حذف سرآیند). کمی بعد دوباره امتحان کنید."
        Kind.HTTP -> "پنل خطا داد. کمی بعد دوباره امتحان کنید."
        Kind.SEAL -> "جواب رمزشده‌ی پنل باز نشد. یک بار خارج و دوباره وارد شوید."
        Kind.PARSE -> "جواب پنل صفحه‌ی اشتراک نبود (JSON/HTML نامعتبر)."
        Kind.BARE_UNSUPPORTED -> "پنل این کد کوتاه را نمی‌شناسد. کد کامل kanki-… را از ربات وارد کنید."
    }

    companion object {
        /** the more useful of two reasons from different hosts (a real panel's «unknown code» wins) */
        private val rank = listOf(Kind.INVALID_CODE, Kind.BARE_UNSUPPORTED, Kind.SEAL, Kind.PIN, Kind.TLS, Kind.PARSE, Kind.RELAY, Kind.HTTP, Kind.DNS, Kind.NETWORK, Kind.NO_HOSTS, Kind.BAD_INPUT)

        fun better(a: PanelError?, b: PanelError): PanelError =
            if (a == null || rank.indexOf(b.kind) < rank.indexOf(a.kind)) b else a

        /** the reason of any failure of a panel request */
        fun of(t: Throwable): PanelError {
            if (t is PanelError) return t
            var cur: Throwable? = t
            while (cur != null) {
                val e: Throwable = cur
                when (e) {
                    is SubscriptionApi.HttpStatus -> return PanelError(
                        when (e.code) {
                            401, 403, 404, 410 -> Kind.INVALID_CODE
                            405, 502, 503, 504, 520, 521, 522, 523, 524, 525, 526 -> Kind.RELAY
                            else -> Kind.HTTP
                        }, t,
                    )
                    is java.net.UnknownHostException -> return PanelError(Kind.DNS, t)
                    is java.security.cert.CertificateException ->
                        return PanelError(if (e.message?.contains("pinned") == true) Kind.PIN else Kind.TLS, t)
                    is javax.net.ssl.SSLPeerUnverifiedException, is javax.net.ssl.SSLHandshakeException -> {
                        if (generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("pinned") == true }) return PanelError(Kind.PIN, t)
                        return PanelError(Kind.TLS, t)
                    }
                    is java.net.SocketTimeoutException, is java.net.ConnectException, is java.net.NoRouteToHostException,
                    is java.net.SocketException -> return PanelError(Kind.NETWORK, t)
                    is javax.net.ssl.SSLException -> return PanelError(Kind.TLS, t)
                }
                cur = e.cause
            }
            return PanelError(Kind.NETWORK, t)
        }
    }
}
