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

    val loggedIn get() = base != null && code != null
    val subLink get() = if (loggedIn) "$base/sub/$code" else ""

    fun save() {
        prefs.edit()
            .putString("proto", proto).putString("node", node)
            .putString("dns", dns).putBoolean("auto", autoConnect)
            .apply()
    }

    suspend fun login(input: String): Boolean {
        val r = SubscriptionApi.normalize(input)
        if (r == null) { error = "لینک یا کد اشتراک معتبر نیست"; return false }
        loading = true; error = null
        return try {
            val a = withContext(Dispatchers.IO) { SubscriptionApi.load(r.first, r.second) }
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
            val a = withContext(Dispatchers.IO) { SubscriptionApi.load(b, c) }
            account = a
            if (node == null || a.configs.none { it.node == node }) node = a.configs.firstOrNull()?.node
            loadInfos(a)
        } catch (e: Exception) {
            error = "دریافت اطلاعات اشتراک ناموفق بود. اینترنت را بررسی کنید."
        }
        loading = false
    }

    private suspend fun loadInfos(a: Account) {
        for (l in a.configs.filter { it.protocol == "amneziawg" || it.protocol == "wireguard" }) {
            val key = "${l.node}/${l.protocol}"
            if (infos.containsKey(key)) continue
            runCatching { withContext(Dispatchers.IO) { ConfigInfo.parse(SubscriptionApi.fetch(l.url)) } }
                .onSuccess { infos[key] = it }
        }
    }

    suspend fun connect() {
        val a = account ?: return
        val link = a.configs.firstOrNull { it.protocol == proto && it.node == node }
            ?: a.configs.firstOrNull { it.protocol == proto }
        if (link == null) { error = "این پروتکل برای اشتراک شما فعال نیست."; return }
        vstate = VState.CONNECTING; error = null
        try {
            withContext(Dispatchers.IO) {
                val cfg = DnsOptions.apply(SubscriptionApi.fetch(link.url), dns)
                VpnEngine.up(ctx, cfg)
            }
            vstate = VState.ON
            measurePing()
        } catch (e: Exception) {
            vstate = VState.OFF
            error = "اتصال برقرار نشد: " + (e.message ?: e.javaClass.simpleName)
        }
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
