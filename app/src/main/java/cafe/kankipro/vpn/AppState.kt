package cafe.kankipro.vpn

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

const val BOT_URL = "https://t.me/kankivpn_bot"
const val SUPPORT_URL = "https://t.me/KANKI_VPN_ADMIN"
/** پسوند شناسه‌ی کانفیگ دوم KANKI MIX (از راه تانل) در اشتراک؛ همان TUNNEL_SUFFIX پنل */
const val TUNNEL_TWIN = "@tunnel"

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
    /** صفحه‌ی چت اضطراری باز است */
    var emergencyOpen by mutableStateOf(false)
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

    // ---------------- حالت‌های اتصال (هوشمند، گیمینگ، ضد فیلتر، DNS)
    var mode by mutableStateOf(KMode.of(prefs.getString("mode", "smart")))
        private set
    fun changeMode(m: KMode) { mode = m; prefs.edit().putString("mode", m.key).apply() }

    /** تب فعلی (برنامه هم می‌تواند تب را عوض کند، مثلاً لمس دوباره‌ی «ضد فیلتر» تب سرورها را باز می‌کند) */
    var tab by mutableStateOf(KTab.HOME)

    /** پنل‌های باز صفحه‌ی اول؛ تا وقتی یکی باز است، دکمه‌ی اصلی اتصال کاری نمی‌کند (گیج‌کننده نباشد) */
    var gameSheet by mutableStateOf(false)
    var appsPicker by mutableStateOf(false)
    /** پنل ضد فیلتر (نگه‌داشتن کاشی، یا لمس دوباره وقتی همین حالت انتخاب است): فقط سرورهای Xray و Amnezia */
    var filterSheet by mutableStateOf(false)

    /** KANKI MIX: هر بسته‌ی بازی دو بار فرستاده شود (ضد پکت‌لاس)؛ وسط اتصال هم عوض می‌شود */
    var kmixDup by mutableStateOf(prefs.getBoolean("kmixDup", true))
        private set
    fun changeKmixDup(v: Boolean) {
        kmixDup = v
        prefs.edit().putBoolean("kmixDup", v).apply()
        if (HyEngine.kmix) KmixCore.setGaming(v)
    }

    // گیمینگ: پروتکل و کشوری که کاربر انتخاب کرده (قفل)، یا «انتخاب خودکار» کم‌پینگ‌ترین پروتکل
    var gameAuto by mutableStateOf(prefs.getBoolean("gameAuto", true))
        private set
    var gameProto by mutableStateOf(prefs.getString("gameProto", "amneziawg") ?: "amneziawg")
        private set
    var gameNode by mutableStateOf(prefs.getString("gameNode", null))
        private set
    /** کاربر حداقل یک بار پنل گیمینگ را تأیید کرده؟ (وگرنه دکمه‌ی اصلی اول پنل را باز می‌کند) */
    var gameSet by mutableStateOf(prefs.getBoolean("gameSet", false))
        private set
    fun setGame(auto: Boolean, p: String, n: String?) {
        gameAuto = auto; gameProto = p; gameNode = n; gameSet = true
        prefs.edit().putBoolean("gameAuto", auto).putString("gameProto", p).putString("gameNode", n).putBoolean("gameSet", true).apply()
    }

    // ضد فیلتر: خودکار (اول مستقیم، بعد تانل) یا انتخاب دستی. filterProto: شناسه‌ی اینباند Xray (xray-…) یا amneziawg
    var filterAuto by mutableStateOf(prefs.getBoolean("filterAuto", true))
        private set
    var filterProto by mutableStateOf(prefs.getString("filterProto", "") ?: "")
        private set
    var filterNode by mutableStateOf(prefs.getString("filterNode", null))
        private set
    /** ضد فیلتر خودکار: فقط Xray (پیش‌فرض)، یا همه‌ی راه‌ها (Xray، AmneziaWG مستقیم و تانل) به ترتیب پینگ */
    var filterXray by mutableStateOf(prefs.getBoolean("filterXray", true))
        private set
    fun changeFilterXray(v: Boolean) { filterXray = v; prefs.edit().putBoolean("filterXray", v).apply() }
    /** تیک Xray در پنل ضد فیلتر (تیک Amnezia همان !filterXray است)؛ همیشه دست‌کم یکی روشن است */
    var filterUseX by mutableStateOf(prefs.getBoolean("filterUseX", true))
        private set
    fun changeFilterTicks(xray: Boolean, amnezia: Boolean) {
        if (!xray && !amnezia) return
        filterUseX = xray
        prefs.edit().putBoolean("filterUseX", xray).apply()
        changeFilterXray(!amnezia)
    }
    /** «خودکار» در تب سرورها: ضد فیلتر خودش بهترین را انتخاب کند (انتخاب دستی کنار می‌رود) */
    fun changeFilterAuto(v: Boolean) { if (v) setFilter(true, "", null) else setFilter(false, filterProto, filterNode) }

    fun setFilter(auto: Boolean, p: String, n: String?) {
        filterAuto = auto; filterProto = p; filterNode = n
        prefs.edit().putBoolean("filterAuto", auto).putString("filterProto", p).putString("filterNode", n).apply()
    }

    // ---------------- برنامه‌های مجاز (بازی‌ها و برنامه‌های انتخابی) برای «فقط بازی‌ها» و حالت DNS
    /** گیمینگ: فقط بازی‌ها و برنامه‌های انتخابی اینترنت داشته باشند */
    var gamesOnly by mutableStateOf(prefs.getBoolean("gamesOnly", false))
        private set
    fun changeGamesOnly(v: Boolean) { gamesOnly = v; prefs.edit().putBoolean("gamesOnly", v).apply() }
    /** بازی‌های نصب‌شده خودکار تشخیص داده شوند */
    var autoGames by mutableStateOf(prefs.getBoolean("autoGames", true))
        private set
    fun changeAutoGames(v: Boolean) { autoGames = v; prefs.edit().putBoolean("autoGames", v).apply() }
    /** برنامه‌هایی که کاربر اضافه کرده، و بازی‌های تشخیص‌داده‌شده‌ای که کاربر برداشته */
    val appsAdded = androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(prefs.getStringSet("appsAdded", emptySet()) ?: emptySet()) }
    val appsRemoved = androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(prefs.getStringSet("appsRemoved", emptySet()) ?: emptySet()) }
    /** بازی‌های نصب‌شده روی گوشی (در پس‌زمینه پیدا می‌شوند) */
    var detectedGames by mutableStateOf<List<String>>(emptyList())

    /** فهرست بازی‌ها یک بار خوانده شده (دوباره فقط از پنل انتخاب برنامه‌ها) */
    var gamesLoaded = false
        private set

    fun loadGames() {
        gamesLoaded = true
        bg.launch {
            val list = withContext(Dispatchers.IO) { Modes.gamePackages(ctx) }
            if (list != detectedGames) detectedGames = list
        }
    }

    fun isAllowedApp(pkg: String): Boolean =
        pkg in appsAdded || (autoGames && pkg in detectedGames && pkg !in appsRemoved)

    fun toggleApp(pkg: String) {
        if (isAllowedApp(pkg)) {
            appsAdded.remove(pkg)
            if (pkg in detectedGames && pkg !in appsRemoved) appsRemoved.add(pkg)
        } else {
            appsRemoved.remove(pkg)
            if (!isAllowedApp(pkg)) appsAdded.add(pkg)
        }
        prefs.edit().putStringSet("appsAdded", appsAdded.toSet()).putStringSet("appsRemoved", appsRemoved.toSet()).apply()
    }

    /** فهرست برنامه‌های مجاز همین حالا (بازی‌ها دوباره از روی گوشی خوانده می‌شوند) */
    private suspend fun allowedAppsNow(): List<String> {
        val games = withContext(Dispatchers.IO) { Modes.gamePackages(ctx) }
        detectedGames = games
        val picked = ((if (autoGames) games.filter { it !in appsRemoved } else emptyList()) + appsAdded).distinct()
        // ورود به حساب بازی‌ها از راه سرویس‌های گوگل و مرورگر است: همیشه همراهشان
        return if (picked.isEmpty()) picked else (picked + withContext(Dispatchers.IO) { Modes.helperPackages(ctx) }).distinct()
    }

    fun allowedCount(): Int = (detectedGames.filter { autoGames && it !in appsRemoved } + appsAdded).distinct().size

    /** سرور DNS انتخابی ("" = خودکار: اول ریلی ایران) و سروری که الان به آن وصل است */
    var dnsPick by mutableStateOf(prefs.getString("dnsPick", "") ?: "")
        private set
    fun changeDnsPick(id: String) { dnsPick = id; prefs.edit().putString("dnsPick", id).apply() }
    /**
     * DNS: بقیه‌ی برنامه‌ها اینترنت نداشته باشند. پیش‌فرض خاموش: برنامه‌های انتخابی فقط DNS کانکی را می‌گیرند
     * و ترافیکشان مستقیم می‌رود (پینگ کم)؛ روشن کردنش ترافیک آن‌ها را از tun2socks داخل گوشی رد می‌کند.
     */
    var dnsBlock by mutableStateOf(prefs.getBoolean("dnsBlock", false))
        private set
    fun changeDnsBlock(v: Boolean) { dnsBlock = v; prefs.edit().putBoolean("dnsBlock", v).apply() }
    var dnsActive by mutableStateOf<DnsServer?>(null)
    /** اسم شبکه‌ی فعلی (Wi-Fi، ایرانسل …) */
    var netName by mutableStateOf("")
    /** مرحله‌ی فعلی اتصال برای نمایش کوچک («سنجش پینگ…»، «اتصال به …») */
    var tryStep by mutableStateOf<String?>(null)

    private fun memKey(m: KMode) = "op_" + Modes.networkKey(ctx) + "_" + m.key

    /** کاربر وسط کار قطع کرد؟ (false یعنی تلاش‌های بعدی انجام نشوند) */
    @Volatile private var wantTry = true

    /** دکمه‌ی اصلی اتصال: بر اساس حالت فعال */
    fun toggleMain() {
        if (gameSheet || appsPicker || filterSheet) return
        // وسط سنجش «هوشمند»: لمس یعنی لغو
        if (smartBusy && vstate == VState.OFF) { wantTry = false; smartStatus = "لغو شد"; return }
        when (vstate) {
            VState.ON, VState.CONNECTING -> userDisconnect()
            VState.OFF -> when (mode) {
                KMode.SMART -> smartConnect()
                KMode.GAME -> if (!gameSet) gameSheet = true else launchConnect { connectGame() }
                KMode.FILTER -> launchConnect { connectFilter() }
                KMode.DNS -> launchConnect { connectDns() }
            }
        }
    }

    /**
     * کاشی‌های حالت (لمس):
     *   هوشمند   همه را می‌سنجد و به بهترین وصل می‌شود (اگر همین حالا با هوشمند وصل است، کاری نمی‌کند)
     *   گیمینگ   پنل گیمینگ باز می‌شود
     *   ضد فیلتر  لمس اول: خودش بهترین راه را پیدا و وصل می‌کند (اول مستقیم، بعد تانل)؛
     *             لمس دوباره (وقتی همین حالت انتخاب است): تب سرورها برای انتخاب دستی
     *   DNS      انتخاب می‌شود؛ اگر VPN دیگری وصل است، به DNS سوییچ می‌کند
     */
    fun tapMode(m: KMode) {
        if (vstate == VState.CONNECTING && m != KMode.GAME) return
        when (m) {
            KMode.SMART -> {
                val already = mode == KMode.SMART && vstate == VState.ON
                changeMode(m)
                if (!already) smartConnect()
            }
            KMode.GAME -> {
                // DNS وصل است و کاربر گیمینگ را می‌زند: DNS قطع می‌شود تا صفحه چیزی جز واقعیت نشان ندهد
                if (mode == KMode.DNS && vstate != VState.OFF) userDisconnect()
                changeMode(m); gameSheet = true
            }
            KMode.FILTER -> {
                // لمس دوباره: پنل ضد فیلتر (فقط Xray و Amnezia)، نه همه‌ی سرورها
                if (mode == KMode.FILTER) { filterSheet = true; return }
                changeMode(m)
                setFilter(true, "", null)
                launchConnect(reconnect = true) { connectFilter() }
            }
            KMode.DNS -> {
                val wasOther = mode != KMode.DNS && vstate == VState.ON
                changeMode(m)
                if (wasOther) launchConnect(reconnect = true) { connectDns() }
            }
        }
    }

    /** تلاش اتصالی که الان در جریان است (همیشه فقط یکی) */
    private var connectJob: kotlinx.coroutines.Job? = null
    @Volatile private var attempt = 0
    private val connectLock = kotlinx.coroutines.sync.Mutex()

    /**
     * همه‌ی اتصال‌ها (دستی، هوشمند، اتصال دوباره‌ی خودکار) از همین‌جا می‌گذرند تا همیشه فقط یکی در جریان باشد.
     * تلاش قبلی (مثلاً KANKI MIX که تا ~۲۰ ثانیه منتظر سرور می‌ماند) اول قطع می‌شود و تا کاملاً تمام نشود
     * تلاش تازه شروع نمی‌شود؛ وگرنه شکست دیرهنگام آن، تونل تازه را می‌بست و خطایش را نشان می‌داد.
     * block شماره‌ی همین تلاش را می‌گیرد؛ stale(my) یعنی تلاش تازه‌تری (یا قطع کاربر) آمده.
     */
    private fun runExclusive(block: suspend (Int) -> Unit) {
        val prev = connectJob
        val my = ++attempt
        connectJob = bg.launch {
            if (prev != null && prev.isActive) {
                wantTry = false
                withContext(Dispatchers.IO) { VpnEngine.down(ctx) }
                prev.cancel()
            }
            connectLock.withLock {
                if (stale(my)) return@withLock
                if (vstate == VState.CONNECTING) vstate = VState.OFF
                block(my)
            }
        }
    }

    private fun stale(my: Int) = my != attempt

    /** قطع به دست کاربر: هر تلاش در جریان (و اتصال دوباره‌ی خودکار) متوقف می‌شود */
    fun userDisconnect() {
        attempt++
        connectJob?.cancel()
        bg.launch { disconnect() }
    }

    /** اتصال در محدوده‌ی خود برنامه (با عوض شدن تب قطع نمی‌شود)، بعد از گرفتن اجازه‌ی VPN */
    fun launchConnect(reconnect: Boolean = false, block: suspend () -> Unit) {
        val go: () -> Unit = {
            runExclusive {
                if (reconnect && vstate != VState.OFF) disconnect()
                wantTry = true
                error = null; errorDetail = null
                block()
            }
        }
        val ask = requestVpn
        if (ask != null) ask(go) else go()
    }

    /** چند گزینه را به ترتیب امتحان می‌کند (فقط برای حالت‌های خودکار بیشتر از یکی است) */
    private suspend fun connectChain(list: List<Pair<String, String>>, title: (Pair<String, String>) -> String): Boolean {
        for ((i, c) in list.withIndex()) {
            if (!wantTry) break
            node = c.first
            selectProto(c.second)
            tryStep = trf("اتصال به %s…", title(c))
            connect(quiet = i < list.size - 1, chained = true)
            if (vstate == VState.ON) { tryStep = null; return true }
        }
        tryStep = null
        return false
    }

    private fun nodeName(n: String) = placeName(n)

    // ---------------- کانفیگ دوم KANKI MIX (از راه تانل ایران)
    // پنل ۳.۷ به بعد برای هر سرور یک کانفیگ دوم KANKI MIX از راه تانل ایران هم می‌دهد، به شکل یک سرور
    // جدا با شناسه‌ی «<شناسه‌ی سرور>@tunnel». کشور و شهرش همان سرور اصلی است؛ فقط برچسبش فرق دارد.
    /** شناسه‌ی سرور اصلیِ کانفیگ تانل (برای بقیه همان شناسه) */
    fun parentOf(id: String) = id.removeSuffix(TUNNEL_TWIN)
    fun isTunnelTwin(id: String?) = id?.endsWith(TUNNEL_TWIN) == true
    private fun hasTwin(id: String) = account?.configs?.any { it.node == id + TUNNEL_TWIN } == true
    /**
     * برچسب ردیف KANKI MIX: کانفیگ تانل «تانل» (یا اسمی که ادمین در پنل برایش گذاشته)، کانفیگ اصلیِ
     * سروری که تانل هم دارد «مستقیم»؛ null اگر آن سرور فقط یکی دارد.
     */
    fun kmixTag(id: String): String? = when {
        isTunnelTwin(id) -> {
            // اسم‌هایی که خود پنل داده (nodeName حالا «کشور · شهر» است و برای هر دو یکی است)
            fun panelName(x: String) = account?.configs?.firstOrNull { it.node == x }?.nodeName?.trim().orEmpty()
            val n = panelName(id)
            val p = panelName(parentOf(id))
            n.takeIf { it.isNotBlank() && it != id && it != p && it != "$p (تانل)" } ?: tr("تانل")
        }
        hasTwin(id) -> tr("مستقیم")
        else -> null
    }
    /** شهر خروجی سرور (کانفیگ تانل: شهر سرور اصلی) */
    fun cityOf(id: String): String? = nodeCity[id] ?: nodeCity[parentOf(id)]

    /** پینگ چند کانفیگ با هم (با روش درست هر پروتکل)؛ نتیجه در pings هم ذخیره می‌شود */
    private suspend fun pingMany(list: List<Pair<String, String>>): Map<Pair<String, String>, Long?> {
        val res = kotlinx.coroutines.coroutineScope {
            list.map { c ->
                async(Dispatchers.IO) { c to infos["${c.first}/${c.second}"]?.let { runCatching { Pinger.test(it) }.getOrNull() } }
            }.awaitAll()
        }
        res.forEach { (c, v) -> pings["${c.first}/${c.second}"] = v ?: -1L }
        return res.toMap()
    }

    /** کانفیگ‌های یک خانواده‌ی گیم (KANKI MIX، Amnezia، WireGuard) روی همه‌ی سرورها */
    fun gameConfigs(family: String): List<Pair<String, String>> {
        val a = account ?: return emptyList()
        val protos = Modes.familyProtos(family)
        return a.configs.map { it.node }.distinct().flatMap { nd -> protos.filter { hasCfg(nd, it) }.map { nd to it } }
    }

    /**
     * گیمینگ: خانواده‌ی پروتکل (KANKI MIX، Amnezia، WireGuard) با سرور انتخابی (قفل؛ هیچ‌وقت بی‌صدا عوض
     * نمی‌شود)، یا «سریع‌ترین سرور» همان خانواده (کم‌پینگ‌ترین؛ اگر وصل نشد، بعدی؛ حداکثر سه تلاش).
     * پروتکلی که از تب سرورها انتخاب شده (مثلاً Hysteria2) هم مستقیم همان وصل می‌شود.
     */
    suspend fun connectGame() {
        netName = Modes.networkName(ctx)
        val fam = Modes.familyOf(gameProto)
        val list = if (gameAuto) {
            val all = gameConfigs(fam ?: "kankimix")
            if (all.isEmpty()) {
                error = trf("%s در اشتراک شما نیست. از «گیمینگ» پروتکل دیگری انتخاب کنید.", familyTitle(fam ?: "kankimix"))
                return
            }
            tryStep = "سنجش پینگ…"
            val m = pingMany(all)
            // کانفیگی که جواب پینگ نداد آخر می‌رود
            all.sortedBy { m[it]?.takeIf { v -> v > 0 } ?: 99_999L }.take(3)
        } else {
            val n = gameNode
            if (n == null || !hasCfg(n, gameProto)) {
                error = trf("%s روی این سرور نیست. از «گیمینگ» دوباره انتخاب کنید.", protoTitle(gameProto))
                return
            }
            listOf(n to gameProto)
        }
        val ok = connectChain(list) { c -> nodeName(c.first) + " · " + protoTitle(c.second) }
        if (!ok && wantTry && !gameAuto) {
            error = trf("اتصال با %s برقرار نشد. سرور یا پروتکل دیگری انتخاب کنید یا «هوشمند» را بزنید.", protoTitle(gameProto) + " · " + nodeName(gameNode ?: ""))
        }
    }

    fun familyTitle(f: String) = when (f) { "kankimix" -> "KANKI MIX"; "amnezia" -> "Amnezia"; else -> "WireGuard" }

    /**
     * ضد فیلتر. خودکار با «اولویت Xray» (پیش‌فرض): فقط اینباندهای Xray، کمترین پینگ اول؛ بدون آن تیک:
     * همه‌ی راه‌ها (Xray، AmneziaWG مستقیم و تانل، …) با هم به ترتیب پینگ. آخرین راهی که روی همین
     * اینترنت وصل شد، دفعه‌ی بعد اول امتحان می‌شود.
     * دستی: فقط همان اینباند Xray، یا AmneziaWG همان کشور (اول مستقیم، بعد تانل).
     */
    suspend fun connectFilter() {
        netName = Modes.networkName(ctx)
        val a = account ?: return
        if (!filterAuto) {
            val p = filterProto
            val n = filterNode
            val list = when {
                p.startsWith("xray-") -> listOf(p to "xray").filter { hasCfg(it.first, "xray") }
                // یک سرور Amnezia مشخص از پنل ضد فیلتر (تانل یا مستقیم): دقیقاً همان
                n != null && p.startsWith("amneziawg") -> listOf(n to p).filter { hasCfg(it.first, it.second) }
                n != null -> listOf(n to "amneziawg-direct", n to "amneziawg").filter { hasCfg(it.first, it.second) }
                else -> emptyList()
            }
            if (list.isEmpty()) { error = "گزینه‌ی انتخابی ضد فیلتر در اشتراک شما نیست. آن را نگه دارید و دوباره انتخاب کنید."; return }
            val ok = connectChain(list) { c -> nodeName(c.first) }
            if (!ok && wantTry) error = trf("اتصال با %s برقرار نشد. گزینه‌ی دیگری انتخاب کنید یا «هوشمند» را بزنید.", nodeName(list.first().first))
            return
        }
        val nodes = a.configs.map { it.node }.distinct()
        val xray = nodes.filter { hasCfg(it, "xray") }.map { it to "xray" }
        val direct = nodes.filter { hasCfg(it, "amneziawg-direct") }.map { it to "amneziawg-direct" }
        val tunnel = listOf("amneziawg", "hysteria2", "wireguard").flatMap { p -> nodes.filter { hasCfg(it, p) }.map { it to p } }
        // تیک‌های پنل ضد فیلتر: Xray (پیش‌فرض روشن) و Amnezia (همه‌ی راه‌ها)؛ کمترین پینگ اول
        val pool = (if (filterUseX) xray else emptyList()) + (if (!filterXray) direct + tunnel else emptyList())
        if (pool.isEmpty()) {
            error = if (filterXray && (direct + tunnel).isNotEmpty())
                "اشتراک شما Xray ندارد. «ضد فیلتر» را نگه دارید و تیک Amnezia را بزنید."
            else "کانفیگی برای اتصال پیدا نشد. اشتراک را بروز کنید."
            return
        }
        tryStep = "سنجش راه‌ها…"
        val m = pingMany(pool)
        fun ok(c: Pair<String, String>) = (m[c] ?: -1L) > 0
        val ranked = pool.filter { ok(it) }.sortedBy { m[it] ?: 99_999L }
        val remembered = prefs.getString(memKey(KMode.FILTER), null)?.split("|")?.takeIf { it.size == 2 }
            ?.let { it[0] to it[1] }?.takeIf { it in pool && ok(it) }
        val list = (listOfNotNull(remembered) + ranked.take(5) + pool.filter { !ok(it) }.take(2)).distinct().take(7)
        val done = connectChain(list) { c -> nodeName(c.first) + " · " + protoTitle(c.second) }
        if (done) prefs.edit().putString(memKey(KMode.FILTER), node + "|" + proto).apply()
        else if (wantTry) error = if (filterXray) "هیچ اینباند Xray وصل نشد. «ضد فیلتر» را نگه دارید و تیک Amnezia را بزنید یا «هوشمند» را بزنید."
            else "هیچ راهی وصل نشد. اینترنت را بررسی کنید یا «هوشمند» را بزنید."
    }

    /** انتخاب در تب سرورها: همان سرور و پروتکل در حالت مناسبش قفل می‌شود */
    fun lockFromServers(id: String, p: String) {
        node = id
        selectProto(p)
        when {
            p == "xray" -> { setFilter(false, id, null); changeMode(KMode.FILTER) }
            // در حالت ضد فیلتر، AmneziaWG (مستقیم/تانل) هم انتخاب دستی همین حالت است
            mode == KMode.FILTER && p.startsWith("amneziawg") -> setFilter(false, "", id)
            // هر پروتکل دیگر (KANKI MIX، WireGuard، Hysteria2، …) انتخاب دستی همان در گیمینگ است
            else -> { setGame(false, p, id); changeMode(KMode.GAME) }
        }
    }

    /** «یک کلیک تا کانفیگ»: کانفیگ انتخابی قفل و (بعد از گرفتن اجازه‌ی VPN) به همان وصل می‌شود */
    fun pickFromServers(id: String, p: String) {
        if (vstate == VState.CONNECTING || smartBusy) return
        lockFromServers(id, p)
        error = null; errorDetail = null
        tab = KTab.HOME
        launchConnect(reconnect = true) { if (mode == KMode.FILTER) connectFilter() else connectGame() }
    }

    /** مسیر یک سرور DNS برای نمایش: ریلی داخل ایران (از تانل پنل به خروجی) یا خروجی مستقیم */
    fun dnsRoute(s: DnsServer?): String? = when {
        s == null -> null
        s.role == "relay" -> "تانل"
        else -> "مستقیم"
    }

    /** موتور DNS اگر وسط کار به سرور دیگری رفت (ریلی ⇄ مستقیم)، همان نشان داده شود */
    fun syncDnsActive() {
        val ip = KankiDns.activeIp ?: return
        val cur = dnsActive ?: return
        if (cur.ip == ip) return
        account?.dns?.servers?.firstOrNull { it.ip == ip }?.let { dnsActive = it }
    }

    /**
     * حالت DNS: ثبت IP و بالا آوردن VPN کوچکِ DNS. هر دو نوع سرور پشتیبانی می‌شود:
     * «relay» (سرور داخل ایران که از تانل مخفی پنل به خروجی خارج می‌رود) و «exit» (خارج، مستقیم).
     * اول ریلی‌ها (به ترتیب سرعت)، بعد خروجی‌های مستقیم؛ موتور اگر سرور فعلی جواب نداد خودش سراغ بعدی می‌رود.
     * اگر کاربر سرور خاصی انتخاب کرده باشد و جواب بدهد، همان اول است.
     */
    suspend fun connectDns() {
        val info = account?.dns
        if (info == null) {
            error = "اشتراک شما DNS ندارد"
            errorDetail = null
            return
        }
        error = null
        vstate = VState.CONNECTING
        try {
            val order = KankiDns.ordered(info, dnsPick.ifBlank { null })
            // همه همزمان ثبت می‌شوند (آدرس ثبت HTTP روی خود IP هر سرور)
            val reg = withContext(Dispatchers.IO) {
                kotlinx.coroutines.coroutineScope { order.map { s -> async { s to KankiDns.register(s.registerHttp) } }.awaitAll() }
            }
            val ok = reg.filter { it.second != null }
            fun fastest(l: List<Pair<DnsServer, Long?>>) = l.sortedBy { it.second ?: Long.MAX_VALUE }.map { it.first }
            val ranked = (ok.filter { it.first.id == dnsPick && dnsPick.isNotBlank() }.map { it.first } +
                fastest(ok.filter { it.first.role == "relay" }) + fastest(ok.filter { it.first.role != "relay" })).distinct()
            val best = ranked.firstOrNull() ?: throw IllegalStateException("no DNS server answered the registration")
            // بقیه‌ی سرورها (حتی آن‌هایی که الان ثبت نشدند) به ترتیب ریلی ← مستقیم برای جابه‌جایی خودکار می‌مانند
            val fallback = ranked + order.filter { it !in ranked }.sortedBy { if (it.role == "relay") 0 else 1 }
            // DNS برای همه‌ی برنامه‌هاست (انتخاب برنامه در حالت DNS برداشته شد)
            val text = applyExcluded(KankiDns.wrap(fallback), excluded.toSet())
            if (!wantTry) throw IllegalStateException("cancelled")
            withContext(Dispatchers.IO) { VpnEngine.up(ctx, text); if (!wantTry) { VpnEngine.down(ctx); throw IllegalStateException("cancelled") } }
            val alive = withContext(Dispatchers.IO) { awaitHandshake(6000) }
            if (!alive) throw IllegalStateException("no internet after DNS mode")
            if (!wantTry) throw IllegalStateException("cancelled")
            dnsActive = best
            vstate = VState.ON
            wantOn = true
            LastConfig.save(ctx, text, "🛡 " + best.name.ifBlank { "Kanki DNS" } + " · " + tr(dnsRoute(best) ?: "رفع تحریم"))
            startNotif()
        } catch (e: Exception) {
            // «قطع» وسط اتصال: کار لغو شده، پس بدون NonCancellable همین down هم اجرا نمی‌شد و VPN وصل می‌ماند
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { VpnEngine.down(ctx) }
            vstate = VState.OFF
            dnsActive = null
            // پیشنهاد وصل شدن به VPN داده نمی‌شود؛ فقط پشتیبانی
            if (wantTry) {
                error = "DNS وصل نشد؛ مشکلی پیش آمده، با پشتیبانی تماس بگیرید"
                errorDetail = "dns: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private var lastNet: android.net.Network? = null

    /** true یعنی کاربر خودش وصل کرده و باید وصل بماند (برای بازاتصال خودکار) */
    private var wantOn = false
    private var autoRetries = 0
    /** آخرین خواندن صفحه‌ی اشتراک از شبکه بود یا از نسخه‌ی ذخیره‌شده */
    @Volatile private var lastFromCache = false
    private val syncIntervalMs = 3L * 60 * 60 * 1000

    // ---------------- ربات راهنما
    /** آموزش قدم‌به‌قدم یک بار دیده شده؟ (بار اول خودکار نشان داده می‌شود) */
    var guideDone: Boolean
        get() = prefs.getBoolean("guideDone", false)
        set(v) { prefs.edit().putBoolean("guideDone", v).apply() }
    /** نکته‌ی اول هر تب (سرورها، تست، تنظیمات) دیده شده؟ */
    val tipsSeen = mutableStateMapOf<String, Boolean>().apply {
        listOf("servers", "test", "settings").forEach { put(it, prefs.getBoolean("tip_$it", false)) }
    }
    fun markTip(k: String) { tipsSeen[k] = true; prefs.edit().putBoolean("tip_$k", true).apply() }
    /** پیام‌های ربات که کاربر در همین اجرا بسته است */
    val dismissed = androidx.compose.runtime.mutableStateListOf<String>()
    /** شناسه‌ی آخرین پیام ادمین که کاربر بسته؛ ذخیره می‌شود تا بعد از باز کردن دوباره‌ی برنامه برنگردد */
    var closedNotice by mutableStateOf(prefs.getString("notice_closed", null))
        private set
    fun closeNotice(id: String) { closedNotice = id; prefs.edit().putString("notice_closed", id).apply() }

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
    /** پنل کوچک انتخاب هوشمند باز است؟ (بعد از اتصال موفق خودکار بسته می‌شود) */
    var smartOpen by mutableStateOf(false)
    /** پیشرفت سنجش: چند تا از چند تا */
    var smartDone by mutableStateOf(0)
    var smartTotal by mutableStateOf(0)
    /** متن مرحله‌ی فعلی پنل */
    var smartStatus by mutableStateOf("")
    /** ردیفی که الان به آن وصل می‌شود («node|proto») */
    var smartCurrent by mutableStateOf<String?>(null)

    /** پینگ و جیتر همه‌ی کانفیگ‌ها را می‌سنجد؛ نتیجه به ترتیب کیفیت */
    private suspend fun smartMeasure(): List<SmartRow> {
        val a = account ?: return emptyList()
        val cands = a.configs.distinctBy { it.node }.flatMap { n ->
            (available(n.node, "tunnel") + available(n.node, "direct")).mapNotNull { p ->
                infos["${n.node}/$p"]?.takeIf { it.host != "—" }?.let { i -> Triple(n, p, i) }
            }
        }
        // هر میزبان/پورت یک بار سنجیده می‌شود
        val keys = cands.map { Triple(it.third.host, it.third.transport, it.third.port) }.distinct()
        smartTotal = keys.size
        smartDone = 0
        val measured = kotlinx.coroutines.coroutineScope {
            keys.map { k ->
                async {
                    val info = cands.first { Triple(it.third.host, it.third.transport, it.third.port) == k }.third
                    val r = withContext(Dispatchers.IO) { runCatching { Pinger.measure(info) }.getOrNull() }
                    smartDone += 1
                    k to r
                }
            }.awaitAll().toMap()
        }
        return cands.mapNotNull { (n, p, i) ->
            measured[Triple(i.host, i.transport, i.port)]?.let { (ping, jit) ->
                pings["${n.node}/$p"] = ping
                SmartRow(n.node, p, "${placeName(n.node)} · ${protoTitle(p)}" + (if (p == "kankimix") kmixTag(n.node)?.let { " · $it" } ?: "" else ""), flagEmoji(isoOfNode(n.node)) ?: "🌐", ping, jit)
            }
        }.sortedBy { it.ping + 2 * it.jitter }
    }

    /**
     * «هوشمند»: پنل کوچکی باز می‌شود، پینگ همه‌ی کانفیگ‌ها (Xray، AmneziaWG تانل و مستقیم، WireGuard،
     * Hysteria2) گرفته می‌شود، به کمترین پینگ وصل می‌شود و بعد از اتصال موفق پنل خودش بسته می‌شود.
     * اگر بهترین واقعاً وصل نشد، بعدی امتحان می‌شود (فقط حالت‌های خودکار اجازه‌ی عوض کردن دارند).
     */
    fun smartConnect() {
        if (smartBusy) return
        // پنل بعد از گرفتن اجازه‌ی VPN باز می‌شود (اگر کاربر رد کرد، پنل باز نمی‌ماند)
        val go: () -> Unit = {
            if (!smartBusy) {
                smartOpen = true
                smartBusy = true
                smartRows.clear()
                smartDone = 0; smartTotal = 0; smartCurrent = null
                smartStatus = "سنجش پینگ همه‌ی سرورها…"
                error = null; errorDetail = null
                runExclusive { runSmart() }
            }
        }
        val ask = requestVpn
        if (ask != null) ask(go) else go()
    }

    private suspend fun runSmart() {
        try {
            if (vstate != VState.OFF) disconnect()
            wantTry = true
            val rows = smartMeasure()
            if (!wantTry) return
            smartRows.clear()
            smartRows.addAll(rows.take(4))
            if (rows.isEmpty()) {
                error = "هیچ سروری جواب پینگ نداد. اینترنت را بررسی کنید."
                return
            }
            // کمترین پینگ اول؛ اتصال واقعاً آزمایش می‌شود (پاسخ از داخل تونل) و اگر نشد، بعدی
            val order = rows.sortedBy { it.ping }.distinctBy { it.node + "|" + it.proto }.take(5)
            for ((i, r) in order.withIndex()) {
                if (!wantTry) return
                smartCurrent = r.node + "|" + r.proto
                smartStatus = trf("اتصال به %s…", r.title)
                node = r.node
                selectProto(r.proto)
                connect(quiet = i < order.size - 1, chained = true)
                if (vstate == VState.ON) {
                    smartStatus = trf("وصل شد: %s", r.title)
                    kotlinx.coroutines.delay(1300)   // چند لحظه برنده دیده شود، بعد پنل بسته شود
                    return
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "انتخاب هوشمند ناموفق بود، دوباره امتحان کنید"
        } finally {
            smartBusy = false
            smartOpen = false
            smartCurrent = null
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
                if (screenOn()) KankiNotif.show(ctx, LastConfig.title(ctx), "↓ ${formatBytes(rx)}/s   ↑ ${formatBytes(tx)}/s   ·   $time")
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
                        runExclusive { wantTry = true; connect() }
                    }
                }
                else if (vstate == VState.OFF && up) {
                    if (VpnEngine.connectedAt == 0L) VpnEngine.markConnected()
                    vstate = VState.ON
                    wantOn = true   // اتصالی که از بیرون برنامه (کاشی/نوتیفیکیشن) برقرار شده هم زیر نظر باشد
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
                    // KANKI MIX سوکت‌هایش را فوراً روی شبکه‌ی تازه می‌سازد؛ بازی قطع نمی‌شود
                    if (HyEngine.kmix) KmixCore.reconnect()
                    bg.launch {
                        kotlinx.coroutines.delay(2500)
                        if (vstate != VState.ON) return@launch
                        val alive = withContext(Dispatchers.IO) { RealPing.once() != null || RealPing.once() != null }
                        if (!alive) revive()
                    }
                }

                // شبکه‌ی فعلی قطع شد (مثلاً وای‌فای رفت و دیتا ماند): بعد از چند ثانیه سلامت تونل بررسی می‌شود
                override fun onLost(network: android.net.Network) {
                    // شبکه‌ی دوم (مثلاً دیتا وقتی وای‌فای وصل است) رفت: تونل روی آن نبود
                    if (lastNet != null && network != lastNet) return
                    bg.launch {
                        kotlinx.coroutines.delay(3500)
                        if (vstate != VState.ON || !hasUnderlyingNet()) return@launch
                        val alive = withContext(Dispatchers.IO) { RealPing.alive() }
                        if (!alive) revive()
                    }
                }
            })
        }
    }

    @Volatile private var reviving = false

    /**
     * قطع و وصل دوباره‌ی تونل (همان سرور و پروتکل)؛ همزمان فقط یک بار، و مثل بقیه‌ی اتصال‌ها از runExclusive،
     * پس اگر کاربر وسطش سرور یا حالت دیگری زد یا قطع کرد، فوراً کنار می‌رود.
     */
    private fun revive() {
        if (reviving || vstate != VState.ON) return
        reviving = true
        val wasDns = dnsActive != null
        runExclusive { my ->
            try {
                disconnect()
                // تا سه بار (شبکه‌ی تازه گاهی چند ثانیه طول می‌کشد تا واقعاً اینترنت بدهد)؛ فقط تلاش آخر خطا نشان می‌دهد
                for (k in 1..3) {
                    if (stale(my)) break
                    wantTry = true
                    if (wasDns) connectDns() else connect(quiet = k < 3)
                    if (vstate == VState.ON || !wantTry || stale(my)) break
                    kotlinx.coroutines.delay(2000L * k)
                }
            } finally { reviving = false }
        }
    }

    private fun screenOn(): Boolean =
        runCatching { ctx.getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true }.getOrDefault(true)

    /**
     * هر ۳۰ ثانیه (فقط وقتی صفحه روشن است، تا باتری و دیتا هدر نرود) از داخل تونل تست می‌کند؛
     * اگر ۲ بار پشت‌سرهم جواب نیامد، دوباره وصل می‌شود. مرگ هسته‌ی Hysteria جدا و فوری شناسایی می‌شود.
     */
    private fun watchHealth() {
        bg.launch {
            var fails = 0
            while (true) {
                kotlinx.coroutines.delay(30_000)
                if (vstate != VState.ON || realTesting || !wantOn || !screenOn()) { fails = 0; continue }
                val ok = withContext(Dispatchers.IO) { RealPing.alive() }
                if (ok) { fails = 0; autoRetries = 0; continue }
                // اگر اصلاً شبکه‌ای زیر تونل نیست (قطعی گوشی)، بازاتصال بی‌فایده است
                if (!hasUnderlyingNet()) { fails = 0; continue }
                fails++
                if (fails >= 2) {
                    fails = 0
                    revive()
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

    /** هر ۳ ساعت یک بار، بی‌صدا اشتراک و کانفیگ‌ها را از پنل تازه می‌کند */
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
            prefs.edit().putLong("last_sync", System.currentTimeMillis()).putLong("last_usage", System.currentTimeMillis()).apply()
            locate()
        }
    }

    private var usageBusy = false

    /**
     * فقط حجم مصرفی، زمان باقی‌مانده و وضعیت حساب را از صفحه‌ی اشتراک می‌گیرد (یک درخواست کوچک).
     * کانفیگ‌ها و فهرست سرورها دست نمی‌خورد؛ آن‌ها هر ۳ ساعت یا با بروزرسانی دستی بروز می‌شوند.
     */
    suspend fun refreshUsage(minAgeMs: Long = 30 * 60 * 1000L) {
        val b = base ?: return
        val c = code ?: return
        if (usageBusy || loading) return
        // با هر بار باز کردن برنامه درخواست نمی‌رود: حجم و زمان حداکثر هر ۳۰ دقیقه یک بار
        if (System.currentTimeMillis() - prefs.getLong("last_usage", 0L) < minAgeMs) return
        usageBusy = true
        try {
            val fresh = runCatching { augment(withContext(Dispatchers.IO) { loadAccount(b, c) }) }.getOrNull()
            if (fresh == null || lastFromCache) return
            prefs.edit().putLong("last_usage", System.currentTimeMillis()).apply()
            val cur = account
            account = if (cur != null) fresh.copy(configs = cur.configs) else fresh
        } finally {
            usageBusy = false
        }
    }

    /**
     * هنگام باز شدن برنامه: اگر کمتر از ۳ ساعت از آخرین بروزرسانی گذشته باشد، همه‌چیز از حافظه‌ی گوشی
     * خوانده می‌شود و به پنل درخواستی نمی‌رود. وگرنه (یا بار اول) یک بروزرسانی کامل انجام می‌شود.
     */
    suspend fun startupLoad() {
        val b = base ?: return
        val c = code ?: return
        val fresh = System.currentTimeMillis() - prefs.getLong("last_sync", 0L) < syncIntervalMs
        val html = prefs.getString("subhtml_$c", null)
        val cached = if (fresh && html != null) runCatching { augment(SubscriptionApi.parse(html, prefs.getString("subhost_$c", null) ?: b)) }.getOrNull() else null
        if (cached == null) {
            refresh()
            if (pingAfterLoad && account != null) { pingAfterLoad = false; startPingAll() }
            return
        }
        account = cached
        if (node == null || cached.configs.none { it.node == node }) node = cached.configs.firstOrNull()?.node
        loadInfos(cached, force = false)
        fixProto()
        refreshUsage()   // حجم مصرفی همیشه تازه است، حتی وقتی کانفیگ‌ها از حافظه خوانده شده‌اند
        locate()
        if (pingAfterLoad) { pingAfterLoad = false; startPingAll() }
    }

    /** تازه وارد شده: بعد از خواندن کانفیگ‌ها پینگ همه گرفته شود */
    private var pingAfterLoad = false

    init {
        loadGames()
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
    /** شهر خروجی هر سرور (اگر معلوم باشد) */
    val nodeCity = mutableStateMapOf<String, String>()
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
        // هر لینک Xray یک «سرور» جدا با پروتکل xray؛ مکانش (اگر پنل داده) کنار خروجی‌های بقیه‌ی سرورها
        val xrayExits = mutableMapOf<String, ExitInfo>()
        val xray = a.xray.mapIndexed { i, x ->
            val id = "xray-" + Emergency.sha256Hex(x.link.toByteArray()).take(8)
            x.exit?.let { xrayExits[id] = it }
            ConfigLink(protocol = "xray", node = id, nodeName = x.name.ifBlank { XrayCfg.protoName(x.link) + " " + (i + 1) }, url = "xray:" + x.link)
        }
        return a.copy(configs = base + direct + xray, exits = a.exits + xrayExits)
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
        if (l.protocol == "xray") return XrayCfg.wrap(l.url.removePrefix("xray:"))
        val url = l.url.substringBefore("#direct=")
        val host = l.url.substringAfter("#direct=", "")
        val cacheKey = "cfg_${l.node}_${l.protocol.substringBefore("-direct")}"
        val cached = prefs.getString(cacheKey, null)
        // کانفیگ یک بار گرفته و ذخیره می‌شود؛ فقط در بروزرسانی (دستی یا ۳ ساعته) دوباره از پنل خوانده می‌شود.
        // اگر پنل در دسترس نبود (فیلتر/قطعی)، آخرین کانفیگ سالم استفاده می‌شود
        val txt = if (!forceNet && cached != null) cached else
            runCatching {
                SubscriptionApi.fetchAny(url).let {
                    when (l.protocol) { "hysteria2" -> HyUri.wrap(it); "kankimix" -> KmixUri.wrap(it); else -> it }
                }
            }
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

    /**
     * صفحه‌ی اشتراک. اول آدرس اصلی (sub.kanki.pro، سرور ایران)، اگر جواب نداد آدرس قدیمی؛
     * اگر هیچ‌کدام در دسترس نبود از نسخه‌ی ذخیره‌شده خوانده می‌شود. لینک کانفیگ‌ها با همان آدرسی
     * که جواب داده سنجیده می‌شوند (و آن آدرس برای خواندن نسخه‌ی ذخیره‌شده نگه داشته می‌شود).
     */
    private fun loadAccount(b: String, c: String): Account {
        lastFromCache = false
        var err: Exception? = null
        if (SubscriptionApi.preferred == null) SubscriptionApi.preferred = prefs.getString("subhost_$c", null)
        if (SubscriptionApi.panelSubBase == null) SubscriptionApi.panelSubBase = prefs.getString("sub_base", null)
        try {
            val (url, html) = SubscriptionApi.race(SubscriptionApi.hosts(b).map { "$it/sub/$c" })
            val h = url.removeSuffix("/sub/$c")
            val acc = SubscriptionApi.parse(html, h)
            prefs.edit().putString("subhtml_$c", html).putString("subhost_$c", h)
                .apply { SubscriptionApi.panelSubBase?.let { putString("sub_base", it) } }.apply()
            return acc
        } catch (e: Exception) {
            err = e
        }
        lastFromCache = true
        val html = prefs.getString("subhtml_$c", null) ?: throw (err ?: java.io.IOException("subscription not reachable"))
        return SubscriptionApi.parse(html, prefs.getString("subhost_$c", null) ?: b)
    }

    /** پروتکل‌های موجود برای یک مسیر (تانل یا مستقیم) */
    fun available(nodeId: String? = node, r: String = route): List<String> = if (r == "direct") buildList {
        if (hasCfg(nodeId, "amneziawg-direct")) add("amneziawg-direct")
    } else buildList {
        if (hasCfg(nodeId, "kankimix")) add("kankimix")
        if (hasCfg(nodeId, "amneziawg")) add("amneziawg")
        if (hasCfg(nodeId, "wireguard")) add("wireguard")
        if (hasCfg(nodeId, "hysteria2")) add("hysteria2")
        if (hasCfg(nodeId, "xray")) add("xray")
    }

    /** تغییر مسیر بین تانل و مستقیم */
    fun changeRoute(r: String) {
        route = r
        prefs.edit().putString("route", r).apply()
        fixProto()
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

    /**
     * بعد از اتصال: IP عمومی خروجی از داخل خود تونل (همان IP که سایت‌ها می‌بینند) = مکان واقعی مقصد.
     * برای دفعه‌های بعد هم ذخیره می‌شود (برای پنل‌های قدیمی که exit_cc نمی‌دهند).
     */
    suspend fun locateExit() {
        val n = node ?: return
        exitIso = null
        if (mode == KMode.DNS) return
        kotlinx.coroutines.delay(800)
        val hit = withContext(Dispatchers.IO) { GeoLookup.lookupExit(ctx) ?: run { Thread.sleep(1500); GeoLookup.lookupExit(ctx) } } ?: return
        if (vstate != VState.ON || node != n) return
        exitIso = hit.iso
        // IP ایران از داخل تونل یعنی خروجی هنوز درست نیست (مثلاً فقط تا سرور ایران رسیده)؛ ذخیره نمی‌شود
        if (hit.iso == "IR") return
        nodeGeo[n] = hit.geo
        nodeIso[n] = hit.iso
        if (hit.city.isNotBlank()) nodeCity[n] = hit.city
        prefs.edit().putString("ngeo_$n", "${hit.geo.lat},${hit.geo.lon}").putString("niso_$n", hit.iso)
            .putString("ncity_$n", hit.city).apply()
    }

    /** کشور IP فعلی وقتی وصل است (از داخل تونل گرفته می‌شود)؛ برای «IP شما: …» */
    var exitIso by mutableStateOf<String?>(null)

    /** کشور یک سرور برای نمایش: خروجی واقعی (پنل یا ذخیره از داخل تونل)، وگرنه از اسمی که ادمین گذاشته */
    fun isoOfNode(id: String): String? =
        nodeIso[id] ?: nodeIso[parentOf(id)] ?: account?.configs?.firstOrNull { it.node == id }?.let { isoOfFlag(flagOf(it.nodeName)) }

    /**
     * مکان کاربر و سرورها.
     * کاربر: از راه شبکه‌ی اصلی گوشی (بیرون از VPN)، پس حتی وقتی وصل است کشور واقعی خودش است.
     * سرورها (هیچ‌وقت از روی IP یا اسم دامنه‌ی Endpoint، چون برای تانل‌ها سرور ایران است):
     *   ۱) خروجی واقعی که پنل داده (exit_cc / exit_city / exit_lat,lon)
     *   ۲) خروجی‌ای که قبلاً بعد از اتصال از داخل همان تونل گرفته و ذخیره شده
     *   ۳) فقط کشور از روی اسمی که ادمین برای سرور گذاشته (مثلاً GERMANY)
     */
    suspend fun locate() {
        // اول سرورها (بدون شبکه)، تا همه‌ی سنجاق‌ها همان لحظه روی نقشه بیایند
        locateNodes()
        withContext(Dispatchers.IO) { GeoLookup.lookupSelf(ctx) }?.let { hit ->
            userGeo = hit.geo
            userIso = hit.iso
            prefs.edit().putString("ugeo", "${hit.geo.lat},${hit.geo.lon}").putString("uiso", hit.iso).apply()
        }
    }

    private fun locateNodes() {
        val a = account ?: return
        for ((nodeId, links) in a.configs.groupBy { it.node }) {
            val ex = a.exits[nodeId]
            // خروجی ایران (از نسخه‌های قبل) معتبر نیست: یعنی فقط تا سرور ایران رسیده بوده
            val savedIso = prefs.getString("niso_$nodeId", null)?.takeIf { it != "IR" }
            val savedGeo = prefs.getString("ngeo_$nodeId", null)?.split(",")?.let { p ->
                runCatching { Geo(p[0].toDouble(), p[1].toDouble()) }.getOrNull()
            }
            val iso = ex?.iso ?: savedIso ?: isoOfFlag(flagOf(links.first().nodeName))
            if (iso == null) { nodeIso.remove(nodeId); nodeGeo.remove(nodeId); continue }
            nodeIso[nodeId] = iso
            val geo = ex?.geo ?: savedGeo?.takeIf { savedIso == iso } ?: geoOfIso(iso)
            if (geo != null) nodeGeo[nodeId] = geo else nodeGeo.remove(nodeId)
            val city = ex?.city?.takeIf { it.isNotBlank() } ?: prefs.getString("ncity_$nodeId", null)?.takeIf { savedIso == iso && it.isNotBlank() }
            if (city != null) nodeCity[nodeId] = city else nodeCity.remove(nodeId)
        }
    }

    /**
     * شهر یک سرور برای نمایش. دو سرور جدا در یک شهر شماره می‌خورند (استکهلم ۲)؛ کانفیگ‌هایی که
     * از یک IP خارج می‌شوند (مثلاً WireGuard و Xray روی یک سرور) یک سرورند و یک اسم دارند.
     */
    fun placeCity(id: String): String? {
        val iso = isoOfNode(id) ?: return null
        val city = exitCityName(iso, cityOf(id)) ?: return null
        val a = account ?: return city
        // کانفیگ تانل KANKI MIX همان سرور است (همان IP خروجی): یک سرور شمرده می‌شود
        fun who(n: String) = a.exits[n]?.ip?.takeIf { it.isNotBlank() } ?: a.exits[parentOf(n)]?.ip?.takeIf { it.isNotBlank() } ?: parentOf(n)
        val same = a.configs.map { it.node }.distinct()
            .filter { isoOfNode(it) == iso && exitCityName(iso, cityOf(it)) == city }
            .map { who(it) }.distinct()
        if (same.size < 2) return city
        return city + " " + faDigits((same.indexOf(who(id)) + 1).toString())
    }

    /**
     * اسم یک سرور: از روی جایی که ترافیکش واقعاً به اینترنت می‌رود (کشور · شهر)، نه اسمی که
     * ادمین در پنل گذاشته (مثل TeST). اگر کشورش معلوم نیست، همان اسم پنل.
     */
    fun placeName(id: String): String {
        val c = countryName(isoOfNode(id)) ?: return account?.configs?.firstOrNull { it.node == id }?.nodeName ?: id
        return placeCity(id)?.let { "$c · $it" } ?: c
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

    /**
     * ورود با کد اشتراک. baseHint: اگر کاربر لینک کامل جای‌گذاری کرده بود، آدرس پنل همان لینک
     * (در کادر فقط کد نشان داده می‌شود)؛ وگرنه برنامه آدرس را خودش پیدا می‌کند.
     */
    suspend fun login(input: String, baseHint: String? = null): Boolean {
        val n = SubscriptionApi.normalize(input)
        if (n == null) { error = "لینک یا کد اشتراک معتبر نیست"; return false }
        val r = if (baseHint != null && n.first == SubscriptionApi.DEFAULT_HOST) baseHint to n.second else n
        loading = true; error = null
        return try {
            val a = augment(withContext(Dispatchers.IO) { loadAccount(r.first, r.second) })
            unlocking = true
            base = r.first; code = r.second
            prefs.edit().putString("base", base).putString("code", code).putLong("last_sync", System.currentTimeMillis()).putLong("last_usage", System.currentTimeMillis()).apply()
            account = a
            node = a.configs.firstOrNull()?.node
            loading = false
            // بعد از ورود (startupLoad) پینگ همه‌ی کانفیگ‌ها هم گرفته می‌شود؛ موقعیت خروجی‌ها از داده‌ی پنل می‌آید
            pingAfterLoad = true
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
            val aug = augment(a)
            account = aug
            // گره‌های Xray فقط در فهرست کامل (augment) هستند؛ پس با همان مقایسه شود
            if (node == null || aug.configs.none { it.node == node }) node = aug.configs.firstOrNull()?.node
            // بروزرسانی کامل: همه‌ی کانفیگ‌ها دوباره از پنل خوانده می‌شود، تا عوض‌شدن دامنه‌ی تانل جا نماند
            loadInfos(aug, force = true)
            if (!lastFromCache) prefs.edit().putLong("last_sync", System.currentTimeMillis()).putLong("last_usage", System.currentTimeMillis()).apply()
            if (vstate == VState.OFF) fixProto()
            locate()
        } catch (e: Exception) {
            error = "دریافت اطلاعات اشتراک ناموفق بود. اینترنت را بررسی کنید."
        }
        loading = false
    }

    /** force = true: کانفیگ‌هایی که قبلاً خوانده شده‌اند هم دوباره از پنل خوانده می‌شوند */
    private suspend fun loadInfos(a: Account, force: Boolean = false) {
        for (l in a.configs.filter { it.protocol in setOf("kankimix", "amneziawg", "amneziawg-direct", "wireguard", "hysteria2", "xray") }) {
            val key = "${l.node}/${l.protocol}"
            if (!force && infos.containsKey(key)) continue
            val txt = runCatching { withContext(Dispatchers.IO) { fetchConfig(l, forceNet = force) } }.getOrNull()
            val ok = txt != null && when {
                l.protocol.startsWith("openvpn") -> txt.contains("remote ")
                l.protocol == "hysteria2" -> txt.contains("hysteria2://")
                l.protocol == "kankimix" -> KmixUri.isKmix(txt)
                l.protocol == "xray" -> XrayCfg.isXray(txt)
                else -> txt.contains("[Interface]") && txt.contains("[Peer]")
            }
            if (ok) { infos[key] = ConfigInfo.parse(txt!!); badCfg.remove(key) }
            else if (!infos.containsKey(key)) badCfg[key] = true   // اگر قبلاً سالم خوانده شده بود، همان می‌ماند
        }
    }

    /**
     * به سرور و پروتکل فعلی (node، proto) وصل می‌شود. هیچ‌وقت خودش سرور یا پروتکل را عوض نمی‌کند.
     * chained: بخشی از یک زنجیره‌ی امتحان است (لغو کاربر برای کل زنجیره معتبر می‌ماند).
     */
    suspend fun connect(quiet: Boolean = false, chained: Boolean = false) {
        if (!chained) wantTry = true
        if (!wantTry) return
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
        // گیمینگ با «فقط بازی‌ها»: فقط بازی‌ها و برنامه‌های انتخابی از VPN رد می‌شوند
        val onlyApps = if (mode == KMode.GAME && gamesOnly) allowedAppsNow() else emptyList()
        try {
            val finalCfg = withContext(Dispatchers.IO) {
                var cfg = applyMtu(DnsOptions.apply(fetchConfig(link), dns), mtuFor(proto))
                cfg = applyKeepalive(cfg, 25)
                if (!HyEngine.isHy(cfg)) cfg = stripIpv6(cfg)
                cfg = applyExcluded(cfg, excluded.toSet())
                cfg = applyKmixGaming(cfg, kmixDup)
                if (onlyApps.isNotEmpty()) {
                    // خود برنامه هم داخل تونل می‌ماند تا بررسی اتصال (هندشیک) واقعاً از تونل انجام شود
                    cfg = applyIncluded(cfg, onlyApps + ctx.packageName)
                    // Hysteria2/Xray: بقیه‌ی برنامه‌ها اصلاً اینترنت ندارند (AppGate). موتور امنزیا/وایرگارد
                    // این خط را نمی‌شناسد، پس فقط برای آن‌ها نوشته نمی‌شود.
                    if (HyEngine.isHy(cfg)) cfg = applyBlockOthers(cfg)
                }
                if (!wantTry) throw IllegalStateException("cancelled")
                VpnEngine.up(ctx, cfg)
                // کاربر وسط بالا آمدن «قطع» زد: همین‌جا پایین بیاید، نه اینکه بعداً «متصل» دیده شود
                if (!wantTry) { VpnEngine.down(ctx); throw IllegalStateException("cancelled") }
                cfg
            }
            // تا وقتی واقعاً از داخل تونل جواب نیاید، «متصل» نشان داده نمی‌شود
            val ok = withContext(Dispatchers.IO) { awaitHandshake() }
            if (!ok) throw IllegalStateException("no handshake: server did not answer (filtered or down)")
            // کاربر وسط اتصال «قطع» زد
            if (!wantTry) throw IllegalStateException("cancelled")
            vstate = VState.ON
            wantOn = true
            LastConfig.save(ctx, finalCfg, "${flagEmoji(isoOfNode(link.node)) ?: "🌐"} ${placeName(link.node)} · ${protoTitle(link.protocol)}")
            startNotif()
            // در کار جدا، تا قطع و وصل دوباره منتظر این درخواست‌ها نماند
            bg.launch { measurePing(); locateExit() }
        } catch (e: Exception) {
            // «قطع» وسط اتصال: کار لغو شده، پس بدون NonCancellable همین down هم اجرا نمی‌شد و VPN وصل می‌ماند
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { VpnEngine.down(ctx) }
            vstate = VState.OFF
            if (!quiet && wantTry) {
                error = "اتصال برقرار نشد. سرور یا پروتکل دیگری را امتحان کنید."
                errorDetail = "${protoKey()}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** چند بار از داخل تونل درخواست می‌فرستد؛ اگر جواب آمد یعنی هندشیک واقعی انجام شده */
    // هر تلاش مهلت کوتاه دارد و دو آدرس به نوبت امتحان می‌شوند، تا یک درخواست کند (مثلاً اولین بسته
    // که پیش از تمام شدن هندشیک امنزیا رفته) کل زمان را نخورد و اتصال سالم «ناموفق» حساب نشود
    private fun awaitHandshake(timeoutMs: Long = 12000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        var i = 0
        while (System.currentTimeMillis() < end) {
            if (!wantTry) return false
            if (RealPing.quick(i++) != null) return true
            Thread.sleep(400)
        }
        return false
    }

    /** انتخاب پروتکل؛ «Amnezia Direct» یعنی مسیر مستقیم، بقیه یعنی تانل */
    fun selectProto(p: String) {
        val r = if (p.endsWith("-direct")) "direct" else "tunnel"
        if (route != r) { route = r; prefs.edit().putString("route", r).apply() }
        proto = p
        save()
    }

    /** سوییچ سریع به پروتکل دیگر (اگر وصل است، قطع و دوباره وصل می‌شود) */
    suspend fun switchTo(p: String) {
        val wasOn = vstate == VState.ON
        if (wasOn) disconnect()
        selectProto(p)
        if (wasOn || p == "openvpn") connect()
    }

    /** بهترین پینگ اندازه‌گیری‌شده‌ی یک کانفیگ (UDP واقعی، بعد TCP، بعد پینگ ساده) */
    fun protoPing(nodeId: String?, p: String): Long? {
        val k = "$nodeId/$p"
        return listOfNotNull(udpPings[k], tcpPings[k], pings[k]).firstOrNull { it > 0 }
            ?: listOfNotNull(udpPings[k], tcpPings[k], pings[k]).firstOrNull()
    }

    /** بهترین پینگ یک سرور از بین همه‌ی کانفیگ‌هایش */
    fun nodePing(nodeId: String): Long? {
        val fromTests = (udpPings.entries + tcpPings.entries + pings.entries)
            .filter { it.key.startsWith("$nodeId/") && it.value > 0 }.minOfOrNull { it.value }
        return fromTests ?: smartRows.filter { it.node == nodeId }.minOfOrNull { it.ping }
    }

    // ---------------- تست سرعت (در محدوده‌ی خود برنامه، تا با عوض کردن تب قطع نشود)
    var speed by mutableStateOf(SpeedUi())
    /** سرعت لحظه‌ای جدا نگه داشته می‌شود چون از رشته‌ی پس‌زمینه بروز می‌شود */
    var speedLive by mutableStateOf(0.0)

    fun runSpeedTest() {
        if (speed.running) return
        speed = SpeedUi(running = true, phase = "server", viaVpn = vstate == VState.ON)
        speedLive = 0.0
        bg.launch {
            try {
                var srv = withContext(Dispatchers.IO) { SpeedEngine.pickServer() } ?: SpeedEngine.CLOUDFLARE
                speed = speed.copy(phase = "ping", server = srv.title)
                var lat = withContext(Dispatchers.IO) { SpeedEngine.latency(srv) }
                if (lat == null && srv.kind != "cf") {
                    srv = SpeedEngine.CLOUDFLARE
                    speed = speed.copy(server = srv.title)
                    lat = withContext(Dispatchers.IO) { SpeedEngine.latency(srv) }
                }
                speed = speed.copy(phase = "down", ping = lat?.first, jitter = lat?.second)
                // اگر سرور Ookla جواب نداد: یک بار با آدرس‌های قدیمی Ookla، بعد با Cloudflare
                suspend fun <T> withFallback(block: (SpeedEngine.Server) -> T): T = withContext(Dispatchers.IO) {
                    try { block(srv) } catch (e: java.io.IOException) {
                        if (srv.kind == "ookla" && !srv.legacy) {
                            srv.legacy = true
                            try { block(srv) } catch (e2: java.io.IOException) { srv = SpeedEngine.CLOUDFLARE; block(srv) }
                        } else if (srv.kind == "ookla") { srv = SpeedEngine.CLOUDFLARE; block(srv) } else throw e
                    }
                }
                val (down, db) = withFallback { s -> SpeedEngine.download(s) { speedLive = it } }
                speedLive = 0.0
                speed = speed.copy(phase = "up", down = down, server = srv.title)
                val (up, ub) = withFallback { s -> SpeedEngine.upload(s) { speedLive = it } }
                speedLive = 0.0
                speed = speed.copy(running = false, phase = "done", up = up, usedBytes = db + ub)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                speedLive = 0.0
                speed = speed.copy(running = false, phase = "", error = "تست ناموفق بود. اینترنت را بررسی کنید.")
            }
        }
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


    /** وضعیت «تست پینگ همه» در تب سرورها */
    var pingAllUi by mutableStateOf(PingAllUi())
        private set

    /** «تست پینگ همه» در محدوده‌ی خود برنامه (با عوض کردن تب قطع نمی‌شود) */
    fun startPingAll() { bg.launch { pingAll() } }

    /**
     * پینگ همه‌ی کانفیگ‌ها (نه فقط یکی برای هر سرور)، حداکثر ۶ تا همزمان:
     * UDP (AmneziaWG، WireGuard، Hysteria2) با پینگ ICMP خود سرور، TCP (Xray) با زمان اتصال به پورتش.
     */
    suspend fun pingAll() {
        val a = account ?: return
        if (pingAllUi.running) return
        val targets = a.configs.mapNotNull { l ->
            val k = "${l.node}/${l.protocol}"
            if (badCfg[k] == true) null else infos[k]?.takeIf { i -> i.host != "—" }?.let { k to it }
        }.distinctBy { it.first }
        val udp = targets.count { it.second.transport != "TCP" }
        pingAllUi = PingAllUi(running = true, done = 0, total = targets.size, udp = udp, tcp = targets.size - udp)
        val gate = kotlinx.coroutines.sync.Semaphore(6)
        try {
            kotlinx.coroutines.coroutineScope {
                targets.map { (k, info) ->
                    async {
                        gate.acquire()
                        try {
                            pinging[k] = true
                            val r = withContext(Dispatchers.IO) { runCatching { Pinger.test(info) }.getOrNull() }
                            pings[k] = r ?: -1L
                        } finally {
                            pinging[k] = false
                            gate.release()
                            pingAllUi = pingAllUi.copy(done = pingAllUi.done + 1)
                        }
                        Unit
                    }
                }.awaitAll()
            }
        } finally {
            pingAllUi = pingAllUi.copy(running = false, finished = true)
        }
    }

    /** چند پینگ آخر از داخل تونل (برای نمودار کوچک صفحه‌ی اول) */
    val pingHist = androidx.compose.runtime.mutableStateListOf<Long>()

    /** یک نمونه‌ی سبک پینگ (یک درخواست HTTP کوچک از داخل تونل) برای نمودار زنده */
    suspend fun samplePing() {
        val v = withContext(Dispatchers.IO) { RealPing.once() } ?: return
        if (vstate != VState.ON) return
        pingHist.add(v)
        while (pingHist.size > 24) pingHist.removeAt(0)
        // عدد نمایش داده‌شده: کمترین از چند نمونه‌ی آخر (مثل پینگ واقعی، بدون نوسان یک‌باره)
        ping = pingHist.takeLast(3).minOrNull()
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
        wantTry = false
        dnsActive = null
        tryStep = null
        exitIso = null
        withContext(Dispatchers.IO) { VpnEngine.down(ctx) }
        KankiNotif.hide(ctx)
        vstate = VState.OFF
        ping = null
        pingHist.clear()
    }

    /** پینگ واقعی از داخل تونل (HTTP روی اتصال زنده؛ نه HTTPS که چند رفت‌وبرگشت اضافه دارد) */
    suspend fun measurePing() {
        ping = withContext(Dispatchers.IO) { RealPing.measure() }
    }

    /** خروج کامل: اشتراک و همه‌ی چیزهایی که از آن ذخیره شده (کانفیگ‌ها و کلیدها) از گوشی پاک می‌شود */
    suspend fun logout() {
        disconnect()
        val e = prefs.edit().remove("base").remove("code").remove("node").remove("last_sync").remove("last_usage").remove("ugeo").remove("uiso")
        prefs.all.keys
            .filter { it.startsWith("cfg_") || it.startsWith("subhtml_") || it.startsWith("subhost_") || it.startsWith("ngeo_") || it.startsWith("niso_") || it.startsWith("ncity_") }
            .forEach { e.remove(it) }
        e.apply()
        LastConfig.clear(ctx)
        runCatching { java.io.File(ctx.filesDir, "hy2.yaml").delete() }
        // چت اضطراری این حساب (پیام‌ها، عکس‌ها، صف ارسال) از گوشی پاک می‌شود؛ روی سرور می‌ماند
        EmergencyStore.clearAll(ctx)
        dismissed.clear()
        speed = SpeedUi()
        base = null; code = null; account = null; node = null
        infos.clear(); badCfg.clear()
        pings.clear(); pinging.clear(); udpPings.clear(); tcpPings.clear(); busyTests.clear()
        nodeGeo.clear(); nodeIso.clear(); nodeCity.clear(); exitIso = null; userGeo = null; userIso = null
        smartRows.clear(); smartOpen = false
        pingHist.clear(); pingAllUi = PingAllUi()
        gameSheet = false; appsPicker = false; filterSheet = false; tab = KTab.HOME
        openNode = null; accountOpen = false
        error = null; errorDetail = null
    }
}
