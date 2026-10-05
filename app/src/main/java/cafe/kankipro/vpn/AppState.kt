package cafe.kankipro.vpn

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

const val BOT_URL = "https://t.me/kankivpn_bot"
const val SUPPORT_URL = "https://t.me/KANKI_VPN_ADMIN"

/**
 * همه‌ی وضعیت اپ در یک جا.
 * فقط یک نمونه از آن برای کل برنامه ساخته می‌شود (AppState.from)؛ پس با چرخش صفحه
 * یا ساخته‌شدن دوباره‌ی صفحه، حلقه‌ها و شنودگر شبکه دوباره و چندباره راه نمی‌افتند.
 */
class AppState private constructor(context: Context) {
    companion object {
        @Volatile
        private var instance: AppState? = null

        fun from(context: Context): AppState =
            instance ?: synchronized(this) {
                instance ?: AppState(context.applicationContext).also { instance = it }
            }
    }

    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("kanki", Context.MODE_PRIVATE)

    var base by mutableStateOf(prefs.getString("base", null))
    var code by mutableStateOf(prefs.getString("code", null))
    var account by mutableStateOf<Account?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** جزئیات فنی آخرین خطا (با زدن «جزئیات» دیده می‌شود) */
    var errorDetail by mutableStateOf<String?>(null)
    private val bg = kotlinx.coroutines.MainScope()
    var vstate by mutableStateOf(if (VpnEngine.isUp(ctx)) VState.ON else VState.OFF)
    var proto by mutableStateOf(prefs.getString("proto", "amneziawg") ?: "amneziawg")
    var node by mutableStateOf(prefs.getString("node", null))
    var dns by mutableStateOf(prefs.getString("dns", "") ?: "")
    var autoConnect by mutableStateOf(prefs.getBoolean("auto", false))
    var robotOnHome by mutableStateOf(prefs.getBoolean("robotHome", true))
    var sounds by mutableStateOf(prefs.getBoolean("sound", true))
    /** مسیر اتصال: tunnel (از طریق سرور واسط) یا direct (مستقیم به سرور) */
    var route by mutableStateOf(prefs.getString("route", "tunnel") ?: "tunnel")


    private var lastNet: android.net.Network? = null

    /** true یعنی کاربر خودش وصل کرده و باید وصل بماند (برای بازاتصال خودکار) */
    private var wantOn = false
    private var autoRetries = 0
    /** آخرین خواندن صفحه‌ی اشتراک از شبکه بود یا از نسخه‌ی ذخیره‌شده */
    @Volatile private var lastFromCache = false
    private val syncIntervalMs = 12L * 60 * 60 * 1000

    // ---------------- تونل‌زنی انتخابی: برنامه‌هایی که از VPN رد نمی‌شوند
    val excluded = androidx.compose.runtime.mutableStateListOf<String>().apply {
        addAll(prefs.getStringSet("excluded", emptySet()) ?: emptySet())
    }
    fun toggleExcluded(pkg: String) {
        if (pkg in excluded) excluded.remove(pkg) else excluded.add(pkg)
        prefs.edit().putStringSet("excluded", excluded.toSet()).apply()
    }

    // ---------------- انتخاب هوشمند بهترین سرور
    data class SmartRow(val node: String, val proto: String, val title: String, val flag: String, val ping: Long, val jitter: Long)
    val smartRows = androidx.compose.runtime.mutableStateListOf<SmartRow>()
    var smartBusy by mutableStateOf(false)
    /** فهرست نتایج انتخاب هوشمند باز است؟ (بعد از اتصال موفق خودکار بسته می‌شود) */
    var smartOpen by mutableStateOf(false)

    /** پینگ و جیتر همه‌ی کانفیگ‌ها را می‌سنجد و بهترین را انتخاب می‌کند */
    suspend fun smartPick(): SmartRow? {
        val a = account ?: return null
        smartBusy = true
        smartRows.clear()
        val rows = try {
            val cands = a.configs.distinctBy { it.node }.flatMap { n ->
                (available(n.node, "tunnel") + available(n.node, "direct")).mapNotNull { p ->
                    infos["${n.node}/$p"]?.host?.takeIf { it != "—" }?.let { h -> Triple(n, p, h) }
                }
            }
            val measured = kotlinx.coroutines.coroutineScope {
                cands.map { it.third }.distinct().map { h ->
                    async(Dispatchers.IO) { h to Pinger.stats(h) }
                }.awaitAll().toMap()
            }
            cands.mapNotNull { (n, p, h) ->
                measured[h]?.let { (ping, jit) -> SmartRow(n.node, p, "${n.nodeName} · ${protoTitle(p)}", flagOf(n.nodeName), ping, jit) }
            }.sortedBy { it.ping + 2 * it.jitter }
        } finally {
            smartBusy = false
        }
        smartRows.addAll(rows.take(4))
        smartOpen = rows.isNotEmpty()
        val best = rows.firstOrNull() ?: return null
        node = best.node
        route = if (best.proto.endsWith("-direct")) "direct" else "tunnel"
        prefs.edit().putString("route", route).apply()
        proto = best.proto
        save()
        return best
    }

    /**
     * دکمه‌ی «انتخاب هوشمند»: بهترین سرور را پیدا می‌کند، وصل می‌شود و بعد از اتصال موفق
     * فهرست نتایج خودکار بسته می‌شود (دکمه به حالت اول برمی‌گردد).
     * در محدوده‌ی خود برنامه اجرا می‌شود، پس با عوض‌کردن تب وسط کار قطع نمی‌شود.
     */
    fun smartConnect() {
        if (smartBusy || vstate == VState.CONNECTING) return
        bg.launch {
            try {
                if (vstate == VState.ON) disconnect()
                val best = smartPick()
                if (best == null) {
                    toast(ctx, "سروری برای سنجش پیدا نشد")
                    return@launch
                }
                val go: () -> Unit = {
                    bg.launch {
                        connect()
                        if (vstate == VState.ON) {
                            kotlinx.coroutines.delay(1500)   // چند لحظه برنده دیده شود، بعد جمع شود
                            smartOpen = false
                        }
                    }
                }
                val ask = requestVpn
                if (ask != null) ask(go) else go()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                smartBusy = false
                toast(ctx, "انتخاب هوشمند ناموفق بود، دوباره امتحان کنید")
            }
        }
    }

    // ---------------- نوتیفیکیشن ثابت با سرعت لحظه‌ای
    private var notifJob: kotlinx.coroutines.Job? = null
    private fun startNotif() {
        if (notifJob?.isActive == true) return
        notifJob = bg.launch {
            var last: Pair<Long, Long>? = null
            while (VpnEngine.isUp(ctx)) {
                val s = VpnEngine.stats(ctx)
                var rx = 0L; var tx = 0L
                if (s != null) { last?.let { rx = (s.first - it.first).coerceAtLeast(0); tx = (s.second - it.second).coerceAtLeast(0) }; last = s }
                val sec = ((System.currentTimeMillis() - VpnEngine.connectedAt) / 1000).coerceAtLeast(0)
                val time = String.format(java.util.Locale.US, "%02d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)
                KankiNotif.show(ctx, LastConfig.title(ctx), "↓ ${formatBytes(rx)}/s   ↑ ${formatBytes(tx)}/s   ·   $time")
                kotlinx.coroutines.delay(2000)
            }
            KankiNotif.hide(ctx)
        }
    }

    /** هماهنگی وضعیت با دکمه‌ی منوی کشویی و نوتیفیکیشن */
    private fun watchState() {
        bg.launch {
            while (true) {
                kotlinx.coroutines.delay(2000)
                if (realTesting || vstate == VState.CONNECTING) continue
                val up = VpnEngine.isUp(ctx)
                if (vstate == VState.ON && !up) {
                    vstate = VState.OFF; ping = null; KankiNotif.hide(ctx)
                    // هسته‌ی Hysteria2 خودش مرده؛ حداکثر ۳ بار خودکار دوباره وصل می‌شود
                    if (HyEngine.crashed && wantOn && autoRetries < 3) {
                        HyEngine.crashed = false
                        autoRetries++
                        connect()
                    }
                }
                else if (vstate == VState.OFF && up) {
                    if (VpnEngine.connectedAt == 0L) VpnEngine.markConnected()
                    vstate = VState.ON
                    startNotif()
                }
            }
        }
    }

    /** وقتی شبکه عوض شد (وای‌فای ⇄ دیتا) و تونل جواب نداد، خودکار دوباره وصل می‌شود */
    private fun watchNetwork() {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val req = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            cm.registerNetworkCallback(req, object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    val prev = lastNet
                    lastNet = network
                    if (prev == null || prev == network) return
                    bg.launch {
                        kotlinx.coroutines.delay(2500)
                        if (vstate != VState.ON) return@launch
                        val alive = withContext(Dispatchers.IO) { RealPing.once() != null || RealPing.once() != null }
                        if (!alive) { disconnect(); connect() }
                    }
                }
            })
        }
    }

    /** هر ~۱۵ ثانیه از داخل تونل تست می‌کند؛ اگر ۳ بار پشت‌سرهم جواب نیامد، دوباره وصل می‌شود */
    private fun watchHealth() {
        bg.launch {
            var fails = 0
            while (true) {
                kotlinx.coroutines.delay(15_000)
                if (vstate != VState.ON || realTesting || !wantOn) { fails = 0; continue }
                val ok = withContext(Dispatchers.IO) { RealPing.alive() }
                if (ok) { fails = 0; autoRetries = 0; continue }
                // اگر اصلاً شبکه‌ای زیر تونل نیست (قطعی گوشی)، بازاتصال بی‌فایده است
                if (!hasUnderlyingNet()) { fails = 0; continue }
                fails++
                if (fails >= 3) {
                    fails = 0
                    disconnect()
                    connect()
                }
            }
        }
    }

    private fun hasUnderlyingNet(): Boolean = runCatching {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return@runCatching true
        cm.allNetworks.any { n ->
            val c = cm.getNetworkCapabilities(n)
            c != null && c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
        }
    }.getOrDefault(true)

    /** هر ۱۲ ساعت یک بار، بی‌صدا اشتراک و کانفیگ‌ها را از پنل تازه می‌کند */
    private fun watchSync() {
        bg.launch {
            while (true) {
                kotlinx.coroutines.delay(30 * 60 * 1000L)
                if (!loggedIn || loading || vstate == VState.CONNECTING || realTesting) continue
                if (System.currentTimeMillis() - prefs.getLong("last_sync", 0L) < syncIntervalMs) continue
                silentSync()
            }
        }
    }

    private suspend fun silentSync() {
        val b = base ?: return
        val c = code ?: return
        runCatching {
            val a = augment(withContext(Dispatchers.IO) { loadAccount(b, c) })
            if (lastFromCache) return@runCatching   // پنل جواب نداد؛ ساعت ثبت نمی‌شود تا بعداً دوباره تلاش شود
            loadInfos(a, force = true)
            account = a
            if (node == null || a.configs.none { it.node == node }) node = a.configs.firstOrNull()?.node
            if (vstate == VState.OFF) fixProto()
            prefs.edit().putLong("last_sync", System.currentTimeMillis()).apply()
        }
    }

    private var usageBusy = false

    /**
     * فقط حجم مصرفی، زمان باقی‌مانده و وضعیت حساب را از صفحه‌ی اشتراک می‌گیرد (یک درخواست کوچک).
     * کانفیگ‌ها و فهرست سرورها دست نمی‌خورد؛ آن‌ها هر ۱۲ ساعت یا با بروزرسانی دستی بروز می‌شوند.
     */
    suspend fun refreshUsage() {
        val b = base ?: return
        val c = code ?: return
        if (usageBusy || loading) return
        usageBusy = true
        try {
            val fresh = runCatching { augment(withContext(Dispatchers.IO) { loadAccount(b, c) }) }.getOrNull()
            if (fresh == null || lastFromCache) return
            val cur = account
            account = if (cur != null) fresh.copy(configs = cur.configs) else fresh
        } finally {
            usageBusy = false
        }
    }

    /**
     * هنگام باز شدن برنامه: اگر کمتر از ۱۲ ساعت از آخرین بروزرسانی گذشته باشد، همه‌چیز از حافظه‌ی گوشی
     * خوانده می‌شود و به پنل درخواستی نمی‌رود. وگرنه (یا بار اول) یک بروزرسانی کامل انجام می‌شود.
     */
    suspend fun startupLoad() {
        val b = base ?: return
        val c = code ?: return
        val fresh = System.currentTimeMillis() - prefs.getLong("last_sync", 0L) < syncIntervalMs
        val html = prefs.getString("subhtml_$c", null)
        val cached = if (fresh && html != null) runCatching { augment(SubscriptionApi.parse(html, b)) }.getOrNull() else null
        if (cached == null) { refresh(); return }
        account = cached
        if (node == null || cached.configs.none { it.node == node }) node = cached.configs.firstOrNull()?.node
        loadInfos(cached, force = false)
        fixProto()
        refreshUsage()   // حجم مصرفی همیشه تازه است، حتی وقتی کانفیگ‌ها از حافظه خوانده شده‌اند
        locate()
    }

    init {
        watchNetwork()
        watchState()
        watchHealth()
        watchSync()
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
     * OpenVPN و IKEv2 حذف می‌شوند. کانفیگ‌های بخش «تانل» همان‌هایی‌اند که از پنل می‌آیند
     * (Endpoint آن‌ها دامنه‌ی ایرانی است).
     *
     * بخش «مستقیم»: فقط AmneziaWG (وایرگارد ساده‌ی مستقیم در ایران وصل نمی‌شود، پس برایش
     * نسخه‌ی مستقیم ساخته نمی‌شود). برای هر سرور، همان کانفیگ AmneziaWG برداشته می‌شود و
     * فقط Endpoint آن با دامنه‌ی مستقیم کشورِ همان سرور عوض می‌شود (در fetchConfig).
     * سرور تازه (مثلاً فرانسه یا هلند) که بعداً به پنل اضافه شود، خودکار تشخیص داده می‌شود.
     */
    private fun augment(a: Account): Account {
        val base = a.configs.filter { !it.protocol.startsWith("openvpn") && !it.protocol.startsWith("ikev2") }
        val direct = base.filter { it.protocol == "amneziawg" }.mapNotNull { l ->
            directHost(l)?.let { h -> l.copy(protocol = l.protocol + "-direct", url = l.url + "#direct=" + h) }
        }
        return a.copy(configs = base + direct)
    }

    /** کد دوحرفی کشور یک سرور: اول از اسم سرور، اگر نشد از شناسه‌ی نود (مثلاً tr یا fr) */
    private fun isoOfConfig(l: ConfigLink): String? =
        isoOfFlag(flagOf(l.nodeName)) ?: isoOfFlag(flagOf(l.node))

    /**
     * دامنه‌ی مستقیم هر کشور:
     *   ترکیه → tr.kankipro.cafe    آلمان → g.kankipro.cafe   (آدرس پیش‌فرض اشتراک: fi.kankipro.cafe)
     *   فرانسه → fr.kankipro.cafe    هلند → nl.kankipro.cafe
     * هر کشور تازه هم به همین شکل «کدکشور.kankipro.cafe» می‌شود.
     */
    private fun directHost(l: ConfigLink): String? = when (val iso = isoOfConfig(l)) {
        null -> null
        "DE" -> "g.kankipro.cafe"
        else -> "${iso.lowercase()}.kankipro.cafe"
    }

    /** دریافت متن کانفیگ؛ برای نسخه‌ی مستقیم، آدرس Endpoint عوض می‌شود */
    private fun fetchConfig(l: ConfigLink, forceNet: Boolean = false): String {
        val url = l.url.substringBefore("#direct=")
        val host = l.url.substringAfter("#direct=", "")
        val cacheKey = "cfg_${l.node}_${l.protocol.substringBefore("-direct")}"
        val cached = prefs.getString(cacheKey, null)
        // کانفیگ یک بار گرفته و ذخیره می‌شود؛ فقط در بروزرسانی (دستی یا ۱۲ ساعته) دوباره از پنل خوانده می‌شود.
        // اگر پنل در دسترس نبود (فیلتر/قطعی)، آخرین کانفیگ سالم استفاده می‌شود
        val txt = if (!forceNet && cached != null) cached else
            runCatching { SubscriptionApi.fetch(url).let { if (l.protocol == "hysteria2") HyUri.wrap(it) else it } }
                .onSuccess { t -> if (t.contains("[Interface]")) prefs.edit().putString(cacheKey, t).apply() }
                .getOrElse { e -> cached ?: throw e }
        if (host.isBlank()) return txt
        return txt.lines().joinToString("\n") { line ->
            if (line.trim().startsWith("Endpoint", ignoreCase = true) && line.contains("=")) {
                val port = line.substringAfterLast(":").trim()
                "Endpoint = $host:$port"
            } else line
        }
    }

    /** صفحه‌ی اشتراک؛ اگر در دسترس نبود از نسخه‌ی ذخیره‌شده خوانده می‌شود */
    private fun loadAccount(b: String, c: String): Account {
        lastFromCache = false
        val html = runCatching { SubscriptionApi.fetch("$b/sub/$c") }
            .onSuccess { prefs.edit().putString("subhtml_$c", it).apply() }
            .getOrElse { e -> lastFromCache = true; prefs.getString("subhtml_$c", null) ?: throw e }
        return SubscriptionApi.parse(html, b)
    }

    /** پروتکل‌های موجود برای یک مسیر (تانل یا مستقیم) */
    fun available(nodeId: String? = node, r: String = route): List<String> = if (r == "direct") buildList {
        if (hasCfg(nodeId, "amneziawg-direct")) add("amneziawg-direct")
    } else buildList {
        if (hasCfg(nodeId, "amneziawg")) add("amneziawg")
        if (hasCfg(nodeId, "wireguard")) add("wireguard")
        if (hasCfg(nodeId, "hysteria2")) add("hysteria2")
    }

    /** تغییر مسیر بین تانل و مستقیم */
    fun changeRoute(r: String) {
        smartOpen = false
        route = r
        prefs.edit().putString("route", r).apply()
        fixProto()
    }

    /** سوییچ سریع سرور (اگر وصل است، قطع و به سرور جدید وصل می‌شود) */
    suspend fun switchNode(n: String) {
        if (n == node) return
        smartOpen = false
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
            // اول میزبان «مستقیم» (دامنه‌ی خود کشور)، چون دامنه‌ی تانل ممکن است ایرانی باشد
            val host = (listOf("amneziawg-direct") + links.map { it.protocol })
                .firstNotNullOfOrNull { infos["$nodeId/$it"]?.host?.takeIf { h -> h != "—" } }
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
            val a = augment(withContext(Dispatchers.IO) { loadAccount(r.first, r.second) })
            unlocking = true
            base = r.first; code = r.second
            prefs.edit().putString("base", base).putString("code", code).putLong("last_sync", System.currentTimeMillis()).apply()
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
                runCatching { loadAccount(b, c) }.getOrElse {
                    Thread.sleep(1500)
                    loadAccount(b, c)
                }
            }
            account = augment(a)
            if (node == null || a.configs.none { it.node == node }) node = a.configs.firstOrNull()?.node
            // بروزرسانی کامل: همه‌ی کانفیگ‌ها دوباره از پنل خوانده می‌شود، تا عوض‌شدن دامنه‌ی تانل جا نماند
            loadInfos(account ?: a, force = true)
            if (!lastFromCache) prefs.edit().putLong("last_sync", System.currentTimeMillis()).apply()
            fixProto()
            locate()
        } catch (e: Exception) {
            error = "دریافت اطلاعات اشتراک ناموفق بود. اینترنت را بررسی کنید."
        }
        loading = false
    }

    /** force = true: کانفیگ‌هایی که قبلاً خوانده شده‌اند هم دوباره از پنل خوانده می‌شوند */
    private suspend fun loadInfos(a: Account, force: Boolean = false) {
        for (l in a.configs.filter { it.protocol in setOf("amneziawg", "amneziawg-direct", "wireguard", "hysteria2") }) {
            val key = "${l.node}/${l.protocol}"
            if (!force && infos.containsKey(key)) continue
            val txt = runCatching { withContext(Dispatchers.IO) { fetchConfig(l, forceNet = force) } }.getOrNull()
            val ok = txt != null && when {
                l.protocol.startsWith("openvpn") -> txt.contains("remote ")
                l.protocol == "hysteria2" -> txt.contains("hysteria2://")
                else -> txt.contains("[Interface]") && txt.contains("[Peer]")
            }
            if (ok) { infos[key] = ConfigInfo.parse(txt!!); badCfg.remove(key) }
            else if (!infos.containsKey(key)) badCfg[key] = true   // اگر قبلاً سالم خوانده شده بود، همان می‌ماند
        }
    }

    suspend fun connect() {
        val a = account ?: return
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
            val finalCfg = withContext(Dispatchers.IO) {
                var cfg = applyMtu(DnsOptions.apply(fetchConfig(link), dns), mtuFor(proto))
                cfg = applyKeepalive(cfg, 25)
                if (!HyEngine.isHy(cfg)) cfg = stripIpv6(cfg)
                cfg = applyExcluded(cfg, excluded.toSet())
                VpnEngine.up(ctx, cfg)
                cfg
            }
            // تا وقتی واقعاً از داخل تونل جواب نیاید، «متصل» نشان داده نمی‌شود
            val ok = withContext(Dispatchers.IO) { awaitHandshake() }
            if (!ok) throw IllegalStateException("no handshake: server did not answer (filtered or down)")
            vstate = VState.ON
            wantOn = true
            LastConfig.save(ctx, finalCfg, "${flagOf(link.nodeName)} ${link.nodeName} · ${protoTitle(link.protocol)}")
            startNotif()
            measurePing()
            locateExit()
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { VpnEngine.down(ctx) }
            vstate = VState.OFF
            error = "اتصال برقرار نشد. سرور یا پروتکل دیگری را امتحان کنید."
            errorDetail = "${protoKey()}: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** چند بار از داخل تونل درخواست می‌فرستد؛ اگر جواب آمد یعنی هندشیک واقعی انجام شده */
    private fun awaitHandshake(timeoutMs: Long = 9000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (RealPing.once() != null) return true
            Thread.sleep(700)
        }
        return false
    }

    /** سوییچ سریع به پروتکل دیگر (اگر وصل است، قطع و دوباره وصل می‌شود) */
    suspend fun switchTo(p: String) {
        smartOpen = false
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
        // وقتی وصل هستی، تست واقعی تونل را قطع و وصل می‌کند (وسط بازی مشکل‌ساز است)؛ پس فقط وقتی قطعی
        if (realTesting || vstate != VState.OFF) return
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
                val cfg = applyKeepalive(applyMtu(DnsOptions.apply(fetchConfig(link), dns), mtuFor(p)), 25)
                VpnEngine.up(ctx, cfg, resetTimer = false)
                Thread.sleep(700)
                RealPing.measure()
            } catch (e: Exception) {
                null
            } finally {
                runCatching {
                    if (wasOn && prevLink != null) {
                        val back = applyKeepalive(applyMtu(DnsOptions.apply(fetchConfig(prevLink), dns), mtuFor(proto)), 25)
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
        wantOn = false
        withContext(Dispatchers.IO) { VpnEngine.down(ctx) }
        KankiNotif.hide(ctx)
        vstate = VState.OFF
        ping = null
    }

    /** پینگ واقعی از داخل تونل (HTTP روی اتصال زنده؛ نه HTTPS که چند رفت‌وبرگشت اضافه دارد) */
    suspend fun measurePing() {
        ping = withContext(Dispatchers.IO) { RealPing.measure() }
    }

    /** خروج کامل: اشتراک و همه‌ی چیزهایی که از آن ذخیره شده (کانفیگ‌ها و کلیدها) از گوشی پاک می‌شود */
    suspend fun logout() {
        disconnect()
        val e = prefs.edit().remove("base").remove("code").remove("node").remove("last_sync").remove("ugeo").remove("uiso")
        prefs.all.keys
            .filter { it.startsWith("cfg_") || it.startsWith("subhtml_") || it.startsWith("ngeo_") || it.startsWith("niso_") }
            .forEach { e.remove(it) }
        e.apply()
        LastConfig.clear(ctx)
        base = null; code = null; account = null; node = null
        infos.clear(); badCfg.clear()
        pings.clear(); pinging.clear(); udpPings.clear(); tcpPings.clear(); busyTests.clear()
        nodeGeo.clear(); nodeIso.clear(); userGeo = null; userIso = null
        smartRows.clear(); smartOpen = false
        openNode = null; accountOpen = false
        error = null; errorDetail = null
    }
}
