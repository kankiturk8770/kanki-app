package cafe.kankipro.vpn

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

const val BOT_URL = "https://t.me/kankivpn_bot"
const val SUPPORT_URL = "https://t.me/KANKI_VPN_ADMIN"

/** همه‌ی وضعیت اپ در یک جا */
class AppState(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("kanki", Context.MODE_PRIVATE)

    var base by mutableStateOf(prefs.getString("base", null))
    var code by mutableStateOf(prefs.getString("code", null))
    var account by mutableStateOf<Account?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var vstate by mutableStateOf(if (VpnEngine.isUp(ctx)) VState.ON else VState.OFF)
    var proto by mutableStateOf(prefs.getString("proto", "amneziawg") ?: "amneziawg")
    var node by mutableStateOf(prefs.getString("node", null))
    var dns by mutableStateOf(prefs.getString("dns", "") ?: "")
    var autoConnect by mutableStateOf(prefs.getBoolean("auto", false))
    var robotOnHome by mutableStateOf(prefs.getBoolean("robotHome", true))
    var sounds by mutableStateOf(prefs.getBoolean("sound", true))
    /** مسیر اتصال: tunnel (از طریق سرور واسط) یا direct (مستقیم به سرور) */
    var route by mutableStateOf(prefs.getString("route", "tunnel") ?: "tunnel")
    /** اجرای صفحه‌ی تأیید سیستم (برای IKEv2)؛ توسط MainActivity تنظیم می‌شود */
    var launchConsent: ((android.content.Intent, () -> Unit) -> Unit)? = null

    init {
        ThemeState.set(prefs.getString("theme", "gold") ?: "gold")
        LangState.lang = prefs.getString("lang", "fa") ?: "fa"
    }

    fun setTheme(id: String) { ThemeState.set(id); prefs.edit().putString("theme", id).apply() }
    fun setLang(l: String) { LangState.lang = l; prefs.edit().putString("lang", l).apply() }
    var ping by mutableStateOf<Long?>(null)
    val infos = mutableStateMapOf<String, ConfigInfo>()

    /** سروری که در تب سرورها باز است (بین تب‌ها حفظ می‌شود) */
    var openNode by mutableStateOf<String?>(null)
    /** نوع اتصال OpenVPN: udp یا tcp */
    var ovpnTransport by mutableStateOf(prefs.getString("ovpn", "udp") ?: "udp")
    /** نتایج تست پینگ: کلید «node/protocol» ؛ مقدار -1 یعنی ناموفق */
    val pings = mutableStateMapOf<String, Long>()
    val pinging = mutableStateMapOf<String, Boolean>()
    /** نتایج پینگ واقعی UDP (از داخل تونل) و TCP */
    val udpPings = mutableStateMapOf<String, Long>()
    val tcpPings = mutableStateMapOf<String, Long>()
    val busyTests = mutableStateMapOf<String, Boolean>()

    /** انیمیشن باز شدن قفل بعد از ورود */
    var unlocking by mutableStateOf(false)
    /** پنل اطلاعات حساب */
    var accountOpen by mutableStateOf(false)
    /** درخواست مجوز VPN (توسط MainActivity تنظیم می‌شود) */
    var requestVpn: ((() -> Unit) -> Unit)? = null
    private var realTesting = false

    /** مکان کاربر (از IP واقعی، قبل از اتصال) و مکان سرورها */
    var userGeo by mutableStateOf(
        prefs.getString("ugeo", null)?.split(",")?.let { p -> runCatching { Geo(p[0].toDouble(), p[1].toDouble()) }.getOrNull() }
    )
    val nodeGeo = mutableStateMapOf<String, Geo>()
    var userIso by mutableStateOf(prefs.getString("uiso", null))
    val nodeIso = mutableStateMapOf<String, String>()
    /** کانفیگ‌هایی که لینکشان هست ولی کانفیگ معتبر ندارند */
    val badCfg = mutableStateMapOf<String, Boolean>()

    /** این سرور واقعاً این پروتکل را دارد؟ */
    fun hasCfg(nodeId: String?, protocol: String): Boolean {
        val a = account ?: return false
        return a.configs.any { it.node == nodeId && it.protocol == protocol } && badCfg["$nodeId/$protocol"] != true
    }

    /**
     * OpenVPN حذف می‌شود و برای هر سروری که امنزیا دارد، یک نسخه‌ی «مستقیم» ساخته می‌شود
     * (همان کانفیگ امنزیا ولی با آدرس مستقیم سرور به‌جای تونل).
     */
    private fun augment(a: Account): Account {
        val base = a.configs.filter { !it.protocol.startsWith("openvpn") }
        val direct = base.filter { it.protocol == "amneziawg" }.mapNotNull { l ->
            directHost(l.nodeName)?.let { h -> l.copy(protocol = "amneziawg-direct", url = l.url + "#direct=" + h) }
        }
        return a.copy(configs = base + direct)
    }

    /** آدرس مستقیم هر کشور */
    private fun directHost(nodeName: String): String? = when (isoOfFlag(flagOf(nodeName))) {
        "DE" -> "g.kankipro.cafe"
        "TR" -> "tr.kankipro.cafe"
        else -> null
    }

    /** دریافت متن کانفیگ؛ برای نسخه‌ی مستقیم، آدرس Endpoint عوض می‌شود */
    private fun fetchConfig(l: ConfigLink): String {
        val url = l.url.substringBefore("#direct=")
        val host = l.url.substringAfter("#direct=", "")
        val txt = SubscriptionApi.fetch(url)
        if (host.isBlank()) return txt
        return txt.lines().joinToString("\n") { line ->
            if (line.trim().startsWith("Endpoint", ignoreCase = true) && line.contains("=")) {
                val port = line.substringAfterLast(":").trim()
                "Endpoint = $host:$port"
            } else line
        }
    }

    /** پروتکل‌هایی که در این اشتراک واقعاً وجود دارند */
    fun hasIkev2(nodeId: String?): Boolean =
        android.os.Build.VERSION.SDK_INT >= 30 && account?.configs?.any { it.node == nodeId && it.protocol == "ikev2" } == true

    /** پروتکل‌های موجود برای یک مسیر (تانل یا مستقیم) */
    fun available(nodeId: String? = node, r: String = route): List<String> = if (r == "direct") buildList {
        if (hasCfg(nodeId, "amneziawg-direct")) add("amneziawg-direct")
        if (hasIkev2(nodeId)) add("ikev2")
    } else buildList {
        if (hasCfg(nodeId, "amneziawg")) add("amneziawg")
        if (hasCfg(nodeId, "wireguard")) add("wireguard")    }

    /** تغییر مسیر بین تانل و مستقیم */
    fun setRoute(r: String) {
        route = r
        prefs.edit().putString("route", r).apply()
        fixProto()
    }

    /** اتصال IKEv2 با موتور خود اندروید */
    private suspend fun connectIkev2() {
        if (android.os.Build.VERSION.SDK_INT < 30) { error = "IKEv2 فقط روی اندروید ۱۱ به بالا کار می‌کند."; return }
        val a = account ?: return
        val guide = a.configs.firstOrNull { it.node == node && it.protocol == "ikev2" }
        if (guide == null) { error = "این پروتکل برای اشتراک شما فعال نیست."; return }
        val ca = a.configs.firstOrNull { it.node == node && it.protocol == "ikev2/ca" }
        vstate = VState.CONNECTING; error = null
        try {
            val gtxt = withContext(Dispatchers.IO) { SubscriptionApi.fetch(guide.url) }
            val ctxt = ca?.let { l -> runCatching { withContext(Dispatchers.IO) { SubscriptionApi.fetchBytes(l.url) } }.getOrNull() }
            val info = Ikev2Info.parse(gtxt) ?: throw IllegalStateException("guide")
            withContext(Dispatchers.IO) { VpnEngine.down(ctx) }
            val consent = Ikev2Engine.provision(ctx, info, ctxt)
            if (consent != null) {
                val launcher = launchConsent ?: throw IllegalStateException("consent")
                vstate = VState.OFF
                launcher(consent) {
                    runCatching { Ikev2Engine.start(ctx); VpnEngine.markConnected(); vstate = VState.ON }
                        .onFailure { error = "اتصال برقرار نشد. سرور یا پروتکل دیگری را امتحان کنید." }
                }
                return
            }
            Ikev2Engine.start(ctx)
            VpnEngine.markConnected()
            vstate = VState.ON
        } catch (e: Exception) {
            vstate = VState.OFF
            error = "اتصال برقرار نشد. سرور یا پروتکل دیگری را امتحان کنید."
        }
    }

    /** سوییچ سریع سرور (اگر وصل است، قطع و به سرور جدید وصل می‌شود) */
    suspend fun switchNode(n: String) {
        if (n == node) return
        val wasOn = vstate == VState.ON
        if (wasOn) disconnect()
        node = n
        fixProto()
        save()
        if (wasOn && proto != "openvpn") connect()
    }

    fun fixProto() {
        if (available().isEmpty()) {
            val other = if (route == "direct") "tunnel" else "direct"
            if (available(node, other).isNotEmpty()) { route = other; prefs.edit().putString("route", other).apply() }
        }
        val av = available()
        if (av.isNotEmpty() && proto !in av) { proto = av.first(); save() }
        val a = account ?: return
        if (proto == "openvpn" && !hasCfg(node, "openvpn/$ovpnTransport")) {
            a.configs.firstOrNull { it.node == node && it.protocol.startsWith("openvpn") && hasCfg(node, it.protocol) }
                ?.let { ovpnTransport = it.protocol.substringAfter("/"); save() }
        }
    }

    /** بعد از اتصال: IP خروجی از داخل تونل = مکان واقعی سرور */
    suspend fun locateExit() {
        val n = node ?: return
        kotlinx.coroutines.delay(800)
        withContext(Dispatchers.IO) { lookupGeo() }?.let { (g, iso) ->
            nodeGeo[n] = g
            nodeIso[n] = iso.uppercase()
            prefs.edit().putString("ngeo_$n", "${g.lat},${g.lon}").putString("niso_$n", iso.uppercase()).apply()
        }
    }

    /** مکان کاربر و سرورها را پیدا می‌کند */
    suspend fun locate() {
        if (vstate == VState.OFF) {
            withContext(Dispatchers.IO) { lookupGeo() }?.let { (g, iso) ->
                userGeo = g
                userIso = iso.uppercase()
                prefs.edit().putString("ugeo", "${g.lat},${g.lon}").putString("uiso", userIso).apply()
            }
        }
        val a = account ?: return
        for ((nodeId, links) in a.configs.groupBy { it.node }) {
            if (!nodeIso.containsKey(nodeId)) {
                (prefs.getString("niso_$nodeId", null) ?: isoOfFlag(flagOf(links.first().nodeName)))?.let { nodeIso[nodeId] = it }
            }
            if (nodeGeo.containsKey(nodeId)) continue
            // ۱) مکان واقعی خروجی که قبلاً بعد از اتصال ذخیره شده
            prefs.getString("ngeo_$nodeId", null)?.split(",")?.let { p ->
                runCatching { nodeGeo[nodeId] = Geo(p[0].toDouble(), p[1].toDouble()) }
            }
            if (nodeGeo.containsKey(nodeId)) continue
            // ۲) از روی اسم سرور (مثلاً GERMANY → فرانکفورت)
            val byName = geoOfName(links.first().nodeName)
            if (byName != null) { nodeGeo[nodeId] = byName; continue }
            // ۳) در آخر از IP آدرس سرور
            val host = links.firstNotNullOfOrNull { infos["$nodeId/${it.protocol}"]?.host }
            if (host != null && host != "—") {
                withContext(Dispatchers.IO) { lookupGeo(host) }?.let { (g, _) -> nodeGeo[nodeId] = g }
            }
        }
    }

    fun mtuFor(p: String): String = prefs.getString("mtu_$p", "") ?: ""
    fun setMtu(p: String, v: String) { prefs.edit().putString("mtu_$p", v.trim()).apply() }

    /** کلید کانفیگ فعال (برای OpenVPN نوع اتصال هم لحاظ می‌شود) */
    fun protoKey(p: String = proto) = if (p == "openvpn") "openvpn/$ovpnTransport" else p

    val loggedIn get() = base != null && code != null
    val subLink get() = if (loggedIn) "$base/sub/$code" else ""

    fun save() {
        prefs.edit()
            .putString("proto", proto).putString("node", node)
            .putString("dns", dns).putBoolean("auto", autoConnect)
            .putBoolean("robotHome", robotOnHome).putBoolean("sound", sounds)
            .putString("ovpn", ovpnTransport)
            .apply()
    }

    suspend fun login(input: String): Boolean {
        val r = SubscriptionApi.normalize(input)
        if (r == null) { error = "لینک یا کد اشتراک معتبر نیست"; return false }
        loading = true; error = null
        return try {
            val a = augment(withContext(Dispatchers.IO) { SubscriptionApi.load(r.first, r.second) })
            unlocking = true
            base = r.first; code = r.second
            prefs.edit().putString("base", base).putString("code", code).apply()
            account = a
            node = a.configs.firstOrNull()?.node
            loading = false
            true
        } catch (e: Exception) {
            loading = false
            error = "این اشتراک پیدا نشد یا اینترنت در دسترس نیست"
            false
        }
    }

    suspend fun refresh() {
        val b = base ?: return
        val c = code ?: return
        loading = true; error = null
        try {
            val a = withContext(Dispatchers.IO) {
                runCatching { SubscriptionApi.load(b, c) }.getOrElse {
                    Thread.sleep(1500)
                    SubscriptionApi.load(b, c)
                }
            }
            account = augment(a)
            if (node == null || a.configs.none { it.node == node }) node = a.configs.firstOrNull()?.node
            loadInfos(account ?: a)
            fixProto()
            locate()
        } catch (e: Exception) {
            error = "دریافت اطلاعات اشتراک ناموفق بود. اینترنت را بررسی کنید."
        }
        loading = false
    }

    private suspend fun loadInfos(a: Account) {
        for (l in a.configs.filter { it.protocol in setOf("amneziawg", "amneziawg-direct", "wireguard") }) {
            val key = "${l.node}/${l.protocol}"
            if (infos.containsKey(key)) continue
            val txt = runCatching { withContext(Dispatchers.IO) { fetchConfig(l) } }.getOrNull()
            val ok = txt != null && (if (l.protocol.startsWith("openvpn")) txt.contains("remote ") else txt.contains("[Interface]") && txt.contains("[Peer]"))
            if (ok) { infos[key] = ConfigInfo.parse(txt!!); badCfg.remove(key) } else badCfg[key] = true
        }
    }

    suspend fun connect() {
        val a = account ?: return
        if (proto == "ikev2") { connectIkev2(); return }
        val key = protoKey()
        val link = a.configs.firstOrNull { it.protocol == key && it.node == node }
        if (link == null) { error = "این پروتکل برای اشتراک شما فعال نیست."; return }
        error = null

        if (proto == "openvpn") {
            try {
                val text = withContext(Dispatchers.IO) { fetchConfig(link) }
                val opened = OpenVpnHandoff.open(ctx, text, "Kanki-${link.nodeName}-${ovpnTransport.uppercase()}")
                error = if (opened) null else "برای OpenVPN ابتدا اپ «OpenVPN Connect» را نصب کنید، سپس دوباره بزنید."
            } catch (e: Exception) {
                error = "دریافت پروفایل OpenVPN ناموفق بود: " + (e.message ?: e.javaClass.simpleName)
            }
            return
        }

        vstate = VState.CONNECTING
        try {
            withContext(Dispatchers.IO) {
                var cfg = applyMtu(DnsOptions.apply(fetchConfig(link), dns), mtuFor(proto))
                cfg = applyKeepalive(cfg, 25)
                VpnEngine.up(ctx, cfg)
            }
            vstate = VState.ON
            measurePing()
            locateExit()
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { VpnEngine.down(ctx) }
            vstate = VState.OFF
            error = "اتصال برقرار نشد. سرور یا پروتکل دیگری را امتحان کنید."
        }
    }

    /** سوییچ سریع به پروتکل دیگر (اگر وصل است، قطع و دوباره وصل می‌شود) */
    suspend fun switchTo(p: String) {
        val wasOn = vstate == VState.ON
        if (wasOn) disconnect()
        proto = p
        save()
        if (wasOn || p == "openvpn") connect()
    }

    /** بروزرسانی اطلاعات کانفیگ‌های یک سرور */
    suspend fun refreshNode(nodeId: String) {
        infos.keys.filter { it.startsWith("$nodeId/") }.forEach { infos.remove(it) }
        refresh()
    }

    /** پینگ TCP واقعی: زمان دست‌دادن TCP با خود سرور */
    suspend fun tcpTest(nodeId: String, p: String) {
        val key = "tcp:$nodeId/$p"
        val info = infos["$nodeId/$p"] ?: return
        busyTests[key] = true
        val r = withContext(Dispatchers.IO) {
            val ports = if (info.transport == "TCP" && info.port > 0) listOf(info.port) else listOf(443, 80, 22, 8443, 2053)
            var r: Long? = null
            for (pt in ports) { r = Pinger.tcp(info.host, pt, 2); if (r != null) break }
            r
        }
        tcpPings["$nodeId/$p"] = r ?: -1L
        busyTests[key] = false
    }

    /**
     * پینگ واقعی UDP مثل v2rayNG: تونل با همین کانفیگ بالا می‌آید،
     * زمان پاسخ از داخل تونل اندازه گرفته می‌شود و بعد اتصال قبلی برمی‌گردد.
     */
    suspend fun realTest(nodeId: String, p: String) {
        if (realTesting || vstate == VState.CONNECTING) return
        if (p.startsWith("openvpn")) { tcpTest(nodeId, p); return }
        val a = account ?: return
        val link = a.configs.firstOrNull { it.node == nodeId && it.protocol == p } ?: return
        val key = "udp:$nodeId/$p"
        realTesting = true
        busyTests[key] = true
        val wasOn = vstate == VState.ON
        val prevLink = if (wasOn) a.configs.firstOrNull { it.node == node && it.protocol == protoKey() } else null
        val result = withContext(Dispatchers.IO) {
            try {
                val cfg = applyMtu(DnsOptions.apply(fetchConfig(link), dns), mtuFor(p))
                VpnEngine.up(ctx, cfg, resetTimer = false)
                Thread.sleep(700)
                RealPing.measure()
            } catch (e: Exception) {
                null
            } finally {
                runCatching {
                    if (wasOn && prevLink != null) {
                        val back = applyMtu(DnsOptions.apply(fetchConfig(prevLink), dns), mtuFor(proto))
                        VpnEngine.up(ctx, back, resetTimer = false)
                    } else if (!wasOn) {
                        VpnEngine.down(ctx)
                    }
                }
            }
        }
        udpPings["$nodeId/$p"] = result ?: -1L
        busyTests[key] = false
        realTesting = false
    }

    /** تست پینگ یک کانفیگ */
    suspend fun pingTest(nodeId: String, p: String) {
        val key = "$nodeId/$p"
        val info = infos[key] ?: return
        pinging[key] = true
        val r = withContext(Dispatchers.IO) { Pinger.test(info) }
        pings[key] = r ?: -1L
        pinging[key] = false
    }

    suspend fun disconnect() {
        if (android.os.Build.VERSION.SDK_INT >= 30) withContext(Dispatchers.IO) { Ikev2Engine.stop(ctx) }
        withContext(Dispatchers.IO) { VpnEngine.down(ctx) }
        vstate = VState.OFF
        ping = null
    }

    suspend fun measurePing() {
        ping = withContext(Dispatchers.IO) {
            runCatching {
                val t0 = System.nanoTime()
                val c = URL("https://www.gstatic.com/generate_204").openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 5000
                c.responseCode
                c.disconnect()
                (System.nanoTime() - t0) / 1_000_000
            }.getOrNull()
        }
    }

    suspend fun logout() {
        disconnect()
        prefs.edit().remove("base").remove("code").remove("node").apply()
        base = null; code = null; account = null; node = null
        infos.clear()
    }
}
