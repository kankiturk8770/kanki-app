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
            .putString("ovpn", ovpnTransport)
            .apply()
    }

    suspend fun login(input: String): Boolean {
        val r = SubscriptionApi.normalize(input)
        if (r == null) { error = "لینک یا کد اشتراک معتبر نیست"; return false }
        loading = true; error = null
        return try {
            val a = withContext(Dispatchers.IO) { SubscriptionApi.load(r.first, r.second) }
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
            account = a
            if (node == null || a.configs.none { it.node == node }) node = a.configs.firstOrNull()?.node
            loadInfos(a)
        } catch (e: Exception) {
            error = "دریافت اطلاعات اشتراک ناموفق بود (${e.message ?: "خطای شبکه"}). اینترنت را بررسی کنید."
        }
        loading = false
    }

    private suspend fun loadInfos(a: Account) {
        for (l in a.configs.filter { it.protocol in setOf("amneziawg", "wireguard", "openvpn/udp", "openvpn/tcp") }) {
            val key = "${l.node}/${l.protocol}"
            if (infos.containsKey(key)) continue
            runCatching { withContext(Dispatchers.IO) { ConfigInfo.parse(SubscriptionApi.fetch(l.url)) } }
                .onSuccess { infos[key] = it }
        }
    }

    suspend fun connect() {
        val a = account ?: return
        val key = protoKey()
        val link = a.configs.firstOrNull { it.protocol == key && it.node == node }
            ?: a.configs.firstOrNull { it.protocol == key }
        if (link == null) { error = "این پروتکل برای اشتراک شما فعال نیست."; return }
        error = null

        if (proto == "openvpn") {
            try {
                val text = withContext(Dispatchers.IO) { SubscriptionApi.fetch(link.url) }
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
                val cfg = applyMtu(DnsOptions.apply(SubscriptionApi.fetch(link.url), dns), mtuFor(proto))
                VpnEngine.up(ctx, cfg)
            }
            vstate = VState.ON
            measurePing()
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { VpnEngine.down(ctx) }
            vstate = VState.OFF
            error = "اتصال برقرار نشد: " + (e.message ?: e.javaClass.simpleName)
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
            val port = if (info.transport == "TCP" && info.port > 0) info.port else 443
            Pinger.tcp(info.host, port) ?: if (port != 80) Pinger.tcp(info.host, 80) else null
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
                val cfg = applyMtu(DnsOptions.apply(SubscriptionApi.fetch(link.url), dns), mtuFor(p))
                VpnEngine.up(ctx, cfg, resetTimer = false)
                Thread.sleep(700)
                RealPing.measure()
            } catch (e: Exception) {
                null
            } finally {
                runCatching {
                    if (wasOn && prevLink != null) {
                        val back = applyMtu(DnsOptions.apply(SubscriptionApi.fetch(prevLink.url), dns), mtuFor(proto))
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
