package cafe.kankipro.vpn

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/*
 * اتصال Xray
 * -----------
 * مثل Hysteria2: هسته‌ی رسمی Xray-core (فایل libxray.so که در واقع برنامه‌ی xray است) یک پراکسی SOCKS5
 * روی 127.0.0.1 باز می‌کند و hev-socks5-tunnel همه‌ی ترافیک گوشی را به آن می‌فرستد. فایل‌های geo داخل
 * برنامه نیستند (حجم کم)، پس کانفیگ هیچ geosite/geoip ندارد. لینک‌ها از ساب پنل می‌آیند (بخش xray).
 * موتور امنزیا دست نخورده؛ این فقط یک هسته‌ی دیگر کنار Hysteria2 است.
 */
object XrayCfg {
    fun supported(link: String): Boolean {
        val l = link.trim().lowercase()
        return l.startsWith("vless://") || l.startsWith("vmess://") || l.startsWith("trojan://") || l.startsWith("ss://")
    }

    /** متنی که برنامه نگه می‌دارد (مثل Hysteria2، شبیه کانفیگ وایرگارد تا DNS و MTU و برنامه‌های مستثنی رویش اعمال شوند) */
    fun wrap(link: String): String {
        val (h, p) = endpoint(link)
        return "[Interface]\n[Xray]\nEndpoint = $h:$p\nURI = ${link.trim()}\n"
    }

    fun isXray(text: String) = text.lines().any { it.trim().equals("[Xray]", ignoreCase = true) }

    /** اسم پروتکل برای نمایش: VLESS، VMess، Trojan، Shadowsocks */
    fun protoName(link: String): String = when (link.trim().substringBefore("://").lowercase()) {
        "vless" -> "VLESS"; "vmess" -> "VMess"; "trojan" -> "Trojan"; else -> "Shadowsocks"
    }

    /** ترنسپورت و امنیت برای نمایش، مثل «REALITY · TCP» */
    fun detail(link: String): String = runCatching {
        val o = outbound(link)
        val st = o.optJSONObject("streamSettings")
        val net = st?.optString("network")?.ifBlank { "tcp" } ?: "tcp"
        val sec = st?.optString("security")?.takeIf { it.isNotBlank() && it != "none" }
        listOfNotNull(sec?.uppercase(), net.uppercase()).joinToString(" · ")
    }.getOrDefault("")

    private fun b64(s: String): ByteArray? {
        var t = s.trim().replace('-', '+').replace('_', '/').replace("\n", "")
        while (t.length % 4 != 0) t += "="
        return runCatching { android.util.Base64.decode(t, android.util.Base64.DEFAULT) }.getOrNull()
    }

    private fun dec(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    private fun query(q: String): Map<String, String> = q.split("&").filter { it.isNotEmpty() }.associate {
        val p = it.split("=", limit = 2)
        p[0] to dec(p.getOrElse(1) { "" })
    }

    private fun hostPort(hp: String): Pair<String, Int> {
        val t = hp.trimEnd('/')
        return if (t.startsWith("[")) {
            val i = t.indexOf(']')
            t.substring(1, i) to (t.substring(i + 2).toIntOrNull() ?: error("xray: bad port"))
        } else {
            val i = t.lastIndexOf(':')
            if (i < 0) error("xray: no port in the link")
            t.substring(0, i) to (t.substring(i + 1).toIntOrNull() ?: error("xray: bad port"))
        }
    }

    private fun vmessJson(link: String): JSONObject =
        JSONObject(String(b64(link.trim().substringAfter("://").substringBefore("#")) ?: error("xray: bad vmess link"), Charsets.UTF_8))

    /** آدرس و پورت سرور یک لینک */
    fun endpoint(link: String): Pair<String, Int> {
        val l = link.trim()
        if (l.lowercase().startsWith("vmess://")) {
            val j = vmessJson(l)
            return j.optString("add") to (j.optString("port").toIntOrNull() ?: j.optInt("port"))
        }
        val rest = l.substringAfter("://").substringBefore("#")
        val hostq = rest.substringAfterLast("@")
        return hostPort(hostq.substringBefore("?"))
    }

    private fun isName(h: String) = h.any { it.isLetter() } && !h.contains(':')

    private fun csv(s: String) = JSONArray(s.split(",").map { it.trim() }.filter { it.isNotEmpty() })

    /** تنظیمات ترنسپورت و امنیت سمت کلاینت از پارامترهای لینک. host = آدرس سرور (برای SNI پیش‌فرض) */
    private fun stream(q: Map<String, String>, host: String): JSONObject {
        fun g(k: String) = q[k].orEmpty()
        val net = g("type").ifBlank { "tcp" }.let { if (it == "splithttp") "xhttp" else it }
        val st = JSONObject().put("network", net)
        val path = g("path").ifBlank { "/" }
        // the app connects by IP: the HTTP Host must stay the server's name (a CDN routes by it)
        val hh = g("host").ifBlank { if (isName(host)) host else "" }
        when (net) {
            "ws" -> st.put("wsSettings", JSONObject().put("path", path).put("host", hh))
            "httpupgrade" -> st.put("httpupgradeSettings", JSONObject().put("path", path).put("host", hh))
            "xhttp" -> st.put("xhttpSettings", JSONObject().put("path", path).put("host", hh).put("mode", g("mode").ifBlank { "auto" }))
            "grpc" -> st.put("grpcSettings", JSONObject().put("serviceName", g("serviceName")).put("multiMode", g("mode") == "multi").put("authority", g("authority")))
            "kcp" -> st.put("kcpSettings", JSONObject())
            else -> if (g("headerType") == "http") st.put(
                "tcpSettings",
                JSONObject().put("header", JSONObject().put("type", "http").put("request", JSONObject().put("path", JSONArray().put(path)).put("headers", JSONObject().put("Host", csv(g("host")))))),
            )
        }
        val fp = g("fp").ifBlank { "chrome" }
        when (g("security")) {
            "tls" -> {
                val t = JSONObject().put("serverName", g("sni").ifBlank { g("host").ifBlank { host } }).put("fingerprint", fp)
                if (g("alpn").isNotBlank()) t.put("alpn", csv(g("alpn")))
                if (g("allowInsecure") == "1" || g("insecure") == "1") t.put("allowInsecure", true)
                st.put("security", "tls").put("tlsSettings", t)
            }
            "reality" -> st.put("security", "reality").put(
                "realitySettings",
                JSONObject().put("serverName", g("sni")).put("fingerprint", fp).put("publicKey", g("pbk")).put("shortId", g("sid")).put("spiderX", g("spx")),
            )
        }
        return st
    }

    /**
     * خروجی Xray از روی لینک. serverIp: آی‌پی که برنامه از قبل پیدا کرده (هسته‌ی Go روی اندروید
     * فایل resolv.conf ندارد و خودش دامنه را پیدا نمی‌کند)؛ اسم دامنه برای SNI و Host می‌ماند.
     */
    fun outbound(link: String, serverIp: String? = null): JSONObject {
        val l = link.trim()
        val scheme = l.substringBefore("://").lowercase()
        if (scheme == "vmess") {
            val j = vmessJson(l)
            fun s(k: String) = j.opt(k)?.toString().orEmpty()
            val q = mutableMapOf(
                "type" to s("net"), "security" to (if (s("tls") == "tls") "tls" else ""), "sni" to s("sni"), "fp" to s("fp"),
                "alpn" to s("alpn"), "host" to s("host"), "mode" to s("mode"), "headerType" to s("type"),
            )
            if (s("net") == "grpc") q["serviceName"] = s("path") else q["path"] = s("path")
            val user = JSONObject().put("id", s("id")).put("security", s("scy").ifBlank { "auto" })
            val vnext = JSONObject().put("address", serverIp ?: s("add")).put("port", s("port").toIntOrNull() ?: j.optInt("port")).put("users", JSONArray().put(user))
            return JSONObject().put("protocol", "vmess").put("settings", JSONObject().put("vnext", JSONArray().put(vnext))).put("streamSettings", stream(q, s("add")))
        }
        val rest = l.substringAfter("://").substringBefore("#")
        val userinfo = rest.substringBeforeLast("@", "")
        val hostq = rest.substringAfterLast("@")
        val (host, port) = hostPort(hostq.substringBefore("?"))
        val q = query(hostq.substringAfter("?", ""))
        val addr = serverIp ?: host
        return when (scheme) {
            "vless" -> {
                val u = JSONObject().put("id", dec(userinfo)).put("encryption", q["encryption"]?.ifBlank { null } ?: "none")
                q["flow"]?.takeIf { it.isNotBlank() }?.let { u.put("flow", it) }
                val vnext = JSONObject().put("address", addr).put("port", port).put("users", JSONArray().put(u))
                JSONObject().put("protocol", "vless").put("settings", JSONObject().put("vnext", JSONArray().put(vnext))).put("streamSettings", stream(q, host))
            }
            "trojan" -> {
                val srv = JSONObject().put("address", addr).put("port", port).put("password", dec(userinfo))
                JSONObject().put("protocol", "trojan").put("settings", JSONObject().put("servers", JSONArray().put(srv))).put("streamSettings", stream(q, host))
            }
            "ss" -> {
                val ui = dec(userinfo)
                val plain = if (ui.contains(":")) ui else String(b64(ui) ?: error("xray: bad ss link"), Charsets.UTF_8)
                val srv = JSONObject().put("address", addr).put("port", port).put("method", plain.substringBefore(":")).put("password", plain.substringAfter(":"))
                JSONObject().put("protocol", "shadowsocks").put("settings", JSONObject().put("servers", JSONArray().put(srv)))
            }
            else -> error("xray: unsupported link")
        }
    }

    /** کل کانفیگ Xray: یک ورودی SOCKS محلی و یک خروجی به سرور */
    fun config(link: String, serverIp: String, socksPort: Int): String {
        val out = outbound(link, serverIp).put("tag", "proxy")
        val socks = JSONObject().put("tag", "socks").put("listen", "127.0.0.1").put("port", socksPort).put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", true))
            .put("sniffing", JSONObject().put("enabled", false))
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(socks))
            .put("outbounds", JSONArray().put(out).put(JSONObject().put("protocol", "blackhole").put("tag", "block")))
            .toString()
    }
}
