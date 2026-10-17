package cafe.kankipro.vpn

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// اتصال صفحه‌ها (HomeUi، ServersUi، SpeedUi، SettingsUi) به وضعیت برنامه (AppState) و کارهای اندرویدی

/** نسخه‌ی برنامه؛ مستقیم از خود اپ خوانده می‌شود تا همیشه با build.gradle.kts یکی باشد */
@Suppress("DEPRECATION")
fun appVersion(ctx: Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun openBot(ctx: Context) = openUrl(ctx, BOT_URL)
fun openSupport(ctx: Context) = openUrl(ctx, SUPPORT_URL)

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, tr(msg), Toast.LENGTH_SHORT).show()

// ================================================================== ورود

@Composable
fun LoginScreen(state: AppState) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    // اگر لینک کامل جای‌گذاری شود، در کادر فقط کد دیده می‌شود و آدرس پنل همین‌جا نگه داشته می‌شود
    var baseHint by remember { mutableStateOf<String?>(null) }
    fun accept(raw: String) {
        val t = raw.trim()
        val n = if (t.contains("/sub/")) SubscriptionApi.normalize(t) else null
        if (n != null) { text = n.second; baseHint = n.first } else { text = raw; if (raw.isBlank()) baseHint = null }
        state.error = null
    }
    LaunchedEffect(Unit) { Sfx.preload(ctx, R.raw.kanki_switch) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        Logo3D(132.dp, animate = true, glow = 1f)
        Spacer(Modifier.height(18.dp))
        BrandTitle(32.sp)
        Spacer(Modifier.height(4.dp))
        T("Secure · Fast · Low Ping", size = 13.sp, color = K.Sub, font = Poppins, weight = FontWeight.SemiBold, ltr = true)
        Spacer(Modifier.height(30.dp))

        KCard(Modifier.fillMaxWidth(), padding = 18.dp, border = K.Gold.copy(alpha = .30f)) {
            T("ورود به حساب", size = 22.sp, weight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            T("لذت اینترنتی ایمن، سریع و آزاد", size = 13.sp, color = K.Sub, weight = FontWeight.Normal)
            Spacer(Modifier.height(16.dp))

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { accept(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = K.Text),
                    placeholder = { T("kanki-1-xY7q2", size = 16.sp, color = K.Sub.copy(alpha = .45f), font = Poppins, weight = FontWeight.SemiBold, ltr = true) },
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = K.Gold, unfocusedBorderColor = K.Gold.copy(alpha = .35f),
                        focusedContainerColor = Color(0x66000000), unfocusedContainerColor = Color(0x66000000),
                        cursorColor = K.Gold,
                    ),
                )
            }

            state.error?.let {
                Spacer(Modifier.height(10.dp))
                T(it, size = 13.sp, color = K.Red)
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GoldButton("ورود", Modifier.weight(1f), loading = state.loading) {
                    scope.launch { state.login(text, baseHint) }
                }
                LineButton("جای‌گذاری", Modifier.weight(1f)) {
                    clip.getText()?.text?.let { accept(it) }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        T(
            "اشتراک نداری؟ از ربات بگیر", size = 14.sp, color = K.Gold, weight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { openBot(ctx) }.padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Spacer(Modifier.height(18.dp))
        RobotSays("سلام رفیق! کدت رو بزن، مهمون ما شو 😉", robotSize = 70.dp, robotAtStart = false, onClick = { openBot(ctx) })
        Spacer(Modifier.height(12.dp))
    }
}

// ================================================================== قاب اصلی

@Composable
fun MainScreen(state: AppState, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val tab = state.tab
    var showSplit by remember { mutableStateOf(false) }

    // نقشه‌ی کشورها (یک بار از فایل خوانده می‌شود)
    var countries by remember { mutableStateOf(MapData.countries) }
    LaunchedEffect(Unit) { if (countries == null) countries = withContext(Dispatchers.Default) { LightsData.load(ctx); MapData.load(ctx) } }

    // آموزش ربات: بار اول بعد از ورود خودکار شروع می‌شود
    LaunchedEffect(state.account != null) {
        if (state.account != null && !state.guideDone && !Guide.active) {
            delay(900)
            state.tab = KTab.HOME
            Guide.start()
        }
    }
    // وقتی آموزش در حال اجراست، صفحه‌ی خانه نشان داده شود
    LaunchedEffect(Guide.step) {
        if (Guide.active) { state.tab = KTab.HOME; state.gameSheet = false; state.filterSheet = false; state.appsPicker = false; state.accountOpen = false; showSplit = false }
    }

    fun startGuide() { state.tab = KTab.HOME; Guide.start() }

    MainFrame(tab, onTab = { state.tab = it }, onRobot = { startGuide() }, onAccount = { state.accountOpen = true }, onEmergency = { state.emergencyOpen = true }) {
        when (tab) {
            KTab.HOME -> HomeTab(state, countries, onToggle)
            KTab.SERVERS -> ServersTab(state)
            KTab.TEST -> SpeedContent(
                state.speed.copy(live = state.speedLive),
                tip = if (state.tipsSeen["test"] == true) null else RobotAlert("tip_test", 0, "برای دیدن سرعت واقعی اینترنتت از داخل VPN، اول وصل شو. تست حجم مصرف می‌کنه، پس زیاد تکرارش نکن 😉"),
                onStart = { state.runSpeedTest() },
                onCloseTip = { state.markTip("test") },
            )
            KTab.SETTINGS -> SettingsContent(
                SettingsUi(state.dns, state.autoConnect, state.sounds, LangState.lang, state.excluded.size, appVersion(ctx)),
                tip = if (state.tipsSeen["settings"] == true) null else RobotAlert("tip_settings", 0, "اگه برنامه‌ای (مثل بانک) با VPN کار نکرد، از «تونل‌زنی انتخابی» بیرونش بذار. راهنمای کامل هم همین پایینه 👇"),
                act = SettingsActions(
                    setDns = { state.dns = it; state.save() },
                    setAuto = { state.autoConnect = it; state.save() },
                    setSounds = { state.sounds = it; state.save() },
                    setTheme = { state.setTheme(it) },
                    setLang = { state.setLang(it); scope.launch { state.refresh() } },
                    openSplit = { showSplit = true },
                    openKill = {
                        runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        toast(ctx, "Kanki VPN را انتخاب و «همیشه روشن» را فعال کنید")
                    },
                    guide = { startGuide() },
                    buy = { openBot(ctx) },
                    support = { openSupport(ctx) },
                    share = {
                        runCatching {
                            ctx.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, "کانکی وی‌پی‌ان 🔐 امنزیا، وایرگارد و هیستریا با یک اشتراک\nتست رایگان: $BOT_URL"),
                                    "معرفی کانکی",
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    logout = { scope.launch { state.logout() } },
                ),
                onCloseTip = { state.markTip("settings") },
            )
        }
    }

    val a = state.account

    // دکمه‌ی «برگشت» گوشی اول پنل باز را می‌بندد، بعد به تب خانه برمی‌گردد
    androidx.activity.compose.BackHandler(enabled = state.appsPicker || state.gameSheet || state.filterSheet || state.accountOpen || showSplit || tab != KTab.HOME) {
        when {
            state.appsPicker -> state.appsPicker = false
            showSplit -> showSplit = false
            state.gameSheet -> state.gameSheet = false
            state.filterSheet -> state.filterSheet = false
            state.accountOpen -> state.accountOpen = false
            else -> state.tab = KTab.HOME
        }
    }

    // پنل گیمینگ (روی همه‌ی صفحه، روی نقشه‌ی کم‌رنگ‌شده)
    if (tab == KTab.HOME) ModeSheets(state)
    AccountSheet(
        state.accountOpen, a,
        AccountActions(
            close = { state.accountOpen = false },
            refresh = { scope.launch { state.refresh(); toast(ctx, "اطلاعات بروز شد") } },
            copy = { clip.setText(AnnotatedString(state.subLink)); toast(ctx, "لینک اشتراک کپی شد") },
            renew = { openBot(ctx) },
            support = { openSupport(ctx) },
        ),
    )

    if (showSplit) SplitTunnelPanel(state) { showSplit = false }
    if (state.appsPicker) {
        AppPicker(
            detected = state.detectedGames,
            autoGames = state.autoGames,
            isOn = { state.isAllowedApp(it) },
            onToggle = { state.toggleApp(it) },
            onAutoGames = { state.changeAutoGames(it) },
            onClose = { state.appsPicker = false },
        )
    }

    GuideOverlay { state.guideDone = true }
}

// ================================================================== تب خانه

@Composable
private fun HomeTab(state: AppState, countries: List<Country>?, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val a = state.account

    // سرعت لحظه‌ای، زمان اتصال و پینگ زنده
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var rx by remember { mutableLongStateOf(0L) }
    var tx by remember { mutableLongStateOf(0L) }
    /** مصرف کل این اتصال (دریافت + ارسال) */
    var session by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.vstate) {
        var last: Pair<Long, Long>? = null
        var tick = 0
        while (state.vstate == VState.ON) {
            now = System.currentTimeMillis()
            val s = VpnEngine.stats(ctx)
            if (s != null) {
                last?.let { p -> rx = (s.first - p.first).coerceAtLeast(0); tx = (s.second - p.second).coerceAtLeast(0) }
                last = s
                session = s.first + s.second
            }
            if (tick % 3 == 0) launch { state.samplePing() }   // نمودار زنده‌ی پینگ (هر ۳ ثانیه یک نمونه‌ی سبک)
            if (state.mode == KMode.DNS) state.syncDnsActive()
            tick++
            if (tick % 30 == 0) launch { state.refreshUsage() }   // حجم مصرفی هر ۳۰ ثانیه (بدون نگه داشتن شمارنده)
            delay(1000)
        }
        rx = 0; tx = 0; session = 0
    }
    // هشدارهای زمانی (مثل روزهای باقی‌مانده) هر دقیقه دوباره بررسی شوند
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    // پیام ادمین وقتی VPN خاموش است هر ۵ دقیقه هم چک می‌شود (وقتی وصل است، بروزرسانی ۳۰ ثانیه‌ای بالا آن را می‌آورد)
    LaunchedEffect(Unit) { while (true) { delay(5 * 60_000L); if (state.vstate == VState.OFF) state.refreshUsage() } }
    // فقط بار اول (نه هر بار که به خانه برمی‌گردی): فهرست برنامه‌ها سنگین است
    LaunchedEffect(Unit) {
        if (!state.gamesLoaded) {
            state.netName = withContext(Dispatchers.IO) { Modes.networkName(ctx) }
            state.loadGames()
        }
    }
    val alert = robotAlertFor(a, now, state.error, state.errorDetail, state.closedNotice)?.takeIf { it.id !in state.dismissed }

    val dnsInfo = a?.dns
    // سروری که روی نقشه نشان داده می‌شود: وقتی وصل است همان سرور، وگرنه انتخاب همین حالت
    val shownNode = when {
        state.vstate != VState.OFF -> state.node
        state.mode == KMode.GAME -> state.gameNode ?: state.node
        state.mode == KMode.FILTER && !state.filterAuto -> if (state.filterProto.startsWith("xray-")) state.filterProto else state.filterNode
        else -> state.node
    }
    // چیپ بالای نقشه: قبل از اتصال (و در حالت DNS) کشور واقعی کاربر؛ بعد از اتصال کشور IP خروجی
    // (که از داخل تونل گرفته می‌شود؛ تا آن موقع کشور خروجی همان سرور)
    val viaExit = state.vstate == VState.ON && state.mode != KMode.DNS
    val locIso = if (viaExit) state.exitIso ?: state.node?.let { state.isoOfNode(it) } else state.userIso

    // نام چند برنامه‌ی مجاز برای کارت DNS

    val ui = HomeUi(
        vstate = state.vstate,
        hasAccount = a != null,
        loading = state.loading,
        mode = state.mode,
        livePing = state.ping,
        pingHist = state.pingHist.toList(),
        rx = rx, tx = tx,
        elapsedSec = if (state.vstate == VState.ON && VpnEngine.connectedAt > 0) ((now - VpnEngine.connectedAt) / 1000).coerceAtLeast(0) else null,
        sub = a?.let { subUi(it, now) },
        alert = alert,
        countries = countries,
        // یک سنجاق برای هر کشور خروجی؛ بدون سنجاق خالی (کشور نامعلوم) و بدون سنجاق ایران (ایران فقط «شما» است)
        pins = a?.configs?.map { it.node }?.distinct()?.mapNotNull { id ->
            val iso = state.nodeIso[id]?.takeIf { it != "IR" } ?: return@mapNotNull null
            state.nodeGeo[id]?.let { MapPin(it, iso, id == shownNode) }
        }?.sortedByDescending { it.selected }?.distinctBy { it.iso } ?: emptyList(),
        userGeo = state.userGeo,
        userIso = state.userIso,
        locationIso = locIso,
        locationIsExit = viaExit,
        sheetOpen = state.gameSheet || state.filterSheet || state.appsPicker,
        smart = SmartPanelUi(
            open = state.smartOpen,
            done = state.smartDone,
            total = state.smartTotal,
            status = state.smartStatus,
            rows = state.smartRows.map { SmartRowUi(it.node + "|" + it.proto, it.flag, it.title, it.ping) },
            current = state.smartCurrent,
        ),
        dns = DnsCardUi(
            hasDns = dnsInfo != null,
            servers = listOf(DnsRowUi("", tr("خودکار"), "auto", "✨")) + (dnsInfo?.servers ?: emptyList()).map { s ->
                val exitIso = if (s.role == "relay" && s.exit.isNotBlank()) state.isoOfNode(s.exit) else null
                DnsRowUi(
                    s.id, s.name.ifBlank { s.host.ifBlank { s.ip } }, s.role,
                    flag = flagEmoji(state.isoOfNode(s.id)) ?: "🌍",
                    exitFlag = flagEmoji(exitIso) ?: "🌍",
                    exitTitle = s.exitName.ifBlank { countryName(exitIso) ?: "" },
                )
            },
            pick = state.dnsPick,
            active = state.dnsActive?.let { (if (it.role == "relay") it.exitName else "").ifBlank { it.name.ifBlank { it.ip } } },
            activeRoute = state.dnsRoute(state.dnsActive),
            sessionBytes = if (state.vstate == VState.ON && state.mode == KMode.DNS) session else null,
        ),
        // رنگ اتصال: DNS فیروزه‌ای، بقیه رنگ پروتکل وصل‌شده؛ قطع: رنگ حالت انتخاب‌شده
        accent = when {
            state.vstate != VState.OFF && state.mode == KMode.DNS -> modeColor(KMode.DNS)
            state.vstate != VState.OFF -> protoColor(state.proto)
            else -> modeColor(state.mode)
        },
        server = if (state.vstate == VState.ON && state.mode != KMode.DNS) state.node?.let { n ->
            val iso = state.isoOfNode(n)
            val name = a?.configs?.firstOrNull { it.node == n }?.nodeName ?: n
            ServerChipUi(flagEmoji(iso) ?: "🌐", countryName(iso) ?: name, exitCityName(iso, state.nodeCity[n]), state.proto)
        } else null,
    )

    HomeContent(
        ui,
        HomeActions(
            toggle = onToggle,
            tapMode = { m -> state.tapMode(m) },
            longMode = { m ->
                when (m) {
                    KMode.GAME -> { state.changeMode(m); state.gameSheet = true }
                    // نگه‌داشتن ضد فیلتر: فقط سرورهای Xray و Amnezia (نه همه‌ی سرورها)
                    KMode.FILTER -> { state.changeMode(m); state.filterSheet = true }
                    else -> state.tapMode(m)
                }
            },
            openAccount = { state.accountOpen = true },
            dns = DnsActions(pickDns = { state.changeDnsPick(it) }),
            alertAction = { id ->
                when (id) {
                    "renew" -> openBot(ctx)
                    "support" -> openSupport(ctx)
                    "smart" -> { state.error = null; state.errorDetail = null; state.changeMode(KMode.SMART); state.smartConnect() }
                }
            },
            dismissAlert = {
                alert?.let { al ->
                    // خطای اتصال فقط پاک می‌شود (اگر همان خطا دوباره آمد، دوباره نشان داده شود)
                    if (al.id.startsWith("err:")) { state.error = null; state.errorDetail = null }
                    else state.dismissed.add(al.id)
                    if (al.id.startsWith("msg:")) state.closeNotice(al.id.removePrefix("msg:"))
                }
            },
            retry = { scope.launch { state.refresh() } },
        ),
    )
}

/** گیگ و روز باقی‌مانده (فقط عدد) */
private fun subUi(a: Account, now: Long): SubUi {
    val gb = if (a.limitGb != null && a.usedGb != null && a.limitGb > 0) {
        val left = (a.limitGb - a.usedGb).coerceAtLeast(0.0)
        if (left >= 10) String.format(java.util.Locale.US, "%.0f", left) else String.format(java.util.Locale.US, "%.1f", left)
    } else {
        parseSize(a.dataRemaining)?.let { b -> String.format(java.util.Locale.US, "%.1f", b / 1073741824.0) } ?: "∞"
    }
    val days = a.expiresAt?.let { e -> ((e - now).coerceAtLeast(0) / 86_400_000L).toString() } ?: "∞"
    val frac = if (a.limitGb != null && a.usedGb != null && a.limitGb > 0) (a.usedGb / a.limitGb).toFloat() else null
    return SubUi(gb, days, a.status, frac)
}

/** پنل‌های گیمینگ و ضد فیلتر */
@Composable
private fun ModeSheets(state: AppState) {
    val scope = rememberCoroutineScope()
    // بسته‌اند: هیچ چیز ساخته نمی‌شود (وگرنه با هر نتیجه‌ی پینگ کل صفحه‌ی خانه دوباره حساب می‌شد)
    if (!state.gameSheet && !state.filterSheet) return
    val a = state.account ?: return
    val nodes = a.configs.distinctBy { it.node }
    fun nodeOf(id: String) = nodes.firstOrNull { it.node == id }

    /** اسم کشور (و اگر یک کشور چند سرور دارد، اسم سرور) */
    fun place(id: String): Pair<String, String> {
        val iso = state.isoOfNode(id)
        val n = nodeOf(id)?.nodeName ?: id
        val c = countryName(iso)
        val name = when {
            c == null -> n
            nodes.count { state.isoOfNode(it.node) == iso } > 1 -> "$c · $n"
            else -> exitCityName(iso, state.nodeCity[id])?.let { "$c · $it" } ?: c
        }
        return (flagEmoji(iso) ?: "🌐") to name
    }
    fun server(id: String, p: String, tag: String?): SheetServer {
        val (flag, name) = place(id)
        return SheetServer(id, p, flag, name, tag, state.protoPing(id, p))
    }
    fun awgTag(p: String) = if (p == "amneziawg-direct") tr("مستقیم") else tr("تانل")

    // پینگ همه‌ی کانفیگ‌ها با باز شدن پنل
    LaunchedEffect(state.gameSheet) { if (state.gameSheet) state.pingAll() }

    val families = Modes.GAME_FAMILIES.map { (key, _) ->
        val list = state.gameConfigs(key).map { (id, p) -> server(id, p, if (key == "amnezia") awgTag(p) else null) }
            .sortedBy { it.ping?.takeIf { v -> v > 0 } ?: 99_999L }
        GameFamilyUi(
            key, state.familyTitle(key),
            when (key) {
                "kankimix" -> tr("پروتکل خود کانکی: بسته‌های بازی بدون صف و دوتایی، کمترین لگ و پکت‌لاس")
                "amnezia" -> tr("عبور از فیلتر؛ مستقیم یا از تانل ایران")
                else -> tr("سبک و سریع، روی اینترنت پایدار")
            },
            list,
        )
    }
    val busy = state.vstate == VState.CONNECTING || state.smartBusy
    GameSheet(
        visible = state.gameSheet,
        families = families,
        initFamily = Modes.familyOf(state.gameProto) ?: "kankimix",
        current = state.gameNode?.let { it + "|" + state.gameProto } ?: ("|" + state.gameProto),
        currentAuto = state.gameAuto,
        busy = busy,
        kmixDup = state.kmixDup,
        gamesOnly = state.gamesOnly,
        autoGames = state.autoGames,
        appCount = state.allowedCount(),
        canBlock = AppGate.supported,
        onKmixDup = { state.changeKmixDup(it) },
        onGamesOnly = { state.changeGamesOnly(it) },
        onAutoGames = { state.changeAutoGames(it) },
        onPickApps = { state.appsPicker = true },
        // پینگ همه با باز شدن پنل یک بار (با محدودیت همزمانی) گرفته می‌شود؛ اینجا دوباره پینگ نمی‌شود
        onFamilyShown = { },
        onConnect = { n, p ->
            state.setGame(false, p, n)
            state.gameSheet = false
            state.error = null; state.errorDetail = null
            state.launchConnect(reconnect = true) { state.connectGame() }
        },
        onFastest = { f ->
            state.setGame(true, Modes.familyProtos(f).first(), null)
            state.gameSheet = false
            state.error = null; state.errorDetail = null
            state.launchConnect(reconnect = true) { state.connectGame() }
        },
        onClose = { state.gameSheet = false },
    )

    // ضد فیلتر: فقط Xray و Amnezia
    val xrayRows = a.configs.filter { it.protocol == "xray" && state.hasCfg(it.node, "xray") }
        .map { SheetServer(it.node, "xray", "🛡", it.nodeName, null, state.protoPing(it.node, "xray")) }
        .sortedBy { it.ping?.takeIf { v -> v > 0 } ?: 99_999L }
    val awgRows = nodes.flatMap { n -> listOf("amneziawg-direct", "amneziawg").filter { state.hasCfg(n.node, it) }.map { p -> server(n.node, p, awgTag(p)) } }
        .sortedBy { it.ping?.takeIf { v -> v > 0 } ?: 99_999L }
    FilterSheet(
        visible = state.filterSheet,
        xray = xrayRows,
        amnezia = awgRows,
        useX = state.filterUseX,
        useAwg = !state.filterXray,
        auto = state.filterAuto,
        current = when {
            state.filterProto.startsWith("xray-") -> state.filterProto + "|xray"
            state.filterNode != null -> state.filterNode + "|" + state.filterProto.ifBlank { if (state.hasCfg(state.filterNode, "amneziawg-direct")) "amneziawg-direct" else "amneziawg" }
            else -> null
        },
        busy = busy,
        onTicks = { x, w -> state.changeFilterTicks(x, w) },
        onShown = { state.startPingAll() },
        onAuto = {
            state.filterSheet = false
            state.changeMode(KMode.FILTER)
            state.setFilter(true, "", null)
            state.error = null; state.errorDetail = null
            state.launchConnect(reconnect = true) { state.connectFilter() }
        },
        onConnect = { n, p ->
            state.filterSheet = false
            state.changeMode(KMode.FILTER)
            if (p == "xray") state.setFilter(false, n, null) else state.setFilter(false, p, n)
            state.error = null; state.errorDetail = null
            state.launchConnect(reconnect = true) { state.connectFilter() }
        },
        onClose = { state.filterSheet = false },
    )
}

// ================================================================== تب سرورها

/** عنوان یک کانفیگ در کارت کشور (مثل «AmneziaWG - تانل»)؛ Xray با اسم خودش در پنل */
private fun cfgTitle(l: ConfigLink, multi: Boolean): String {
    val base = when (l.protocol) {
        "amneziawg" -> "AmneziaWG - " + tr("تانل")
        "amneziawg-direct" -> "AmneziaWG - " + tr("مستقیم")
        "xray" -> l.nodeName
        else -> protoTitle(l.protocol)
    }
    return if (multi && l.protocol != "xray") base + " · " + l.nodeName else base
}

@Composable
private fun ServersTab(state: AppState) {
    val a = state.account
    val order = listOf("kankimix", "amneziawg", "amneziawg-direct", "wireguard", "hysteria2", "xray")
    val usable = a?.configs?.filter { it.protocol in order && state.hasCfg(it.node, it.protocol) } ?: emptyList()
    // کارت‌ها بر اساس کشور (Xray هم زیر کشور خودش می‌رود اگر اسمش کشور را بگوید)
    // کشور هر کانفیگ = کشور خروجی واقعی سرورش (از پنل یا از داخل تونل)، نه کشور Endpoint
    val groups = usable.groupBy { l -> state.isoOfNode(l.node) ?: ("node:" + l.node) }
    val selKey = "${state.node}/${state.protoKey()}"
    val cards = groups.map { (key, links) ->
        val iso = key.takeIf { !it.startsWith("node:") }
        val multi = links.filter { it.protocol != "xray" }.map { it.node }.distinct().size > 1
        val rows = links.sortedBy { order.indexOf(it.protocol) }.map { l ->
            val k = "${l.node}/${l.protocol}"
            CfgRowUi(
                node = l.node, proto = l.protocol, title = cfgTitle(l, multi),
                ping = state.protoPing(l.node, l.protocol), busy = state.pinging[k] == true,
                selected = k == selKey,
                host = state.infos[k]?.host?.takeIf { it.isNotBlank() && it != "—" },
            )
        }
        CountryCardUi(
            id = key,
            name = countryName(iso) ?: links.first().nodeName,
            city = exitCityName(iso, links.firstNotNullOfOrNull { state.nodeCity[it.node] }) ?: links.first().nodeName,
            flag = flagEmoji(iso) ?: "🌐",
            best = rows.mapNotNull { it.ping }.filter { it > 0 }.minOrNull() ?: rows.mapNotNull { it.ping }.firstOrNull(),
            expanded = state.openNode == key,
            selected = rows.any { it.selected },
            rows = rows,
        )
    }.sortedBy { c -> c.best?.takeIf { it > 0 } ?: 99_999L }

    ServersContent(
        cards, state.pingAllUi,
        tip = if (state.tipsSeen["servers"] == true) null else RobotAlert("tip_servers", 0, "روی هر کشور بزن تا کانفیگ‌هاش باز بشه؛ با یه لمس روی هر کانفیگ وصل می‌شی. «شروع» پینگ همه رو با هم می‌گیره."),
        act = ServersActions(
            toggle = { id -> state.openNode = if (state.openNode == id) null else id },
            pick = { id, p -> state.pickFromServers(id, p) },
            pingAll = { state.startPingAll() },
            mtu = { p -> state.mtuFor(p) },
            setMtu = { p, v -> state.setMtu(p, v) },
            closeTip = { state.markTip("servers") },
            filterAuto = { state.changeFilterAuto(it) },
            filterXray = { state.changeFilterXray(it) },
        ),
        filter = if (state.mode == KMode.FILTER) FilterOptsUi(state.filterAuto, state.filterXray) else null,
    )
}

// ================================================================== تونل‌زنی انتخابی

/** صفحه‌ی تونل‌زنی انتخابی: برنامه‌هایی که از VPN رد نمی‌شوند */
@Composable
fun SplitTunnelPanel(state: AppState, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppItem>?>(null) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { AppsRepo.load(ctx) } }
    Box(
        Modifier.fillMaxSize().background(K.Ink.copy(alpha = .97f))
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("تونل‌زنی انتخابی", size = 20.sp, color = K.Text, weight = FontWeight.Black)
                    T("برنامه‌های روشن‌شده مستقیم و بدون VPN وصل می‌شوند (مثل بانک و اسنپ)", size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
                }
                SmallLine("بستن", onClick = onClose)
            }
            Spacer(Modifier.height(10.dp))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { T("جستجو…", size = 13.sp, color = K.Sub) },
                    textStyle = TextStyle(fontFamily = Vazir, fontSize = 14.sp, color = K.Text),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = K.Gold, unfocusedBorderColor = K.Line, cursorColor = K.Gold),
                )
            }
            Spacer(Modifier.height(8.dp))
            val list = apps
            if (list == null) {
                Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = K.Gold) }
            } else {
                val shown = list.filter { q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true) }
                    .sortedByDescending { it.pkg in state.excluded }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
                    items(shown.size) { idx ->
                        val app = shown[idx]
                        val on = app.pkg in state.excluded
                        Row(
                            Modifier.fillMaxWidth().clickable { state.toggleExcluded(app.pkg) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            app.icon?.let { Image(it, null, Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))) }
                                ?: Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(K.Surface))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                T(app.label, size = 14.sp)
                                T(app.pkg, size = 10.sp, color = K.Sub, font = Poppins, ltr = true)
                            }
                            Switch(
                                checked = on, onCheckedChange = { state.toggleExcluded(app.pkg) },
                                colors = SwitchDefaults.colors(checkedThumbColor = K.OnGold, checkedTrackColor = K.Gold, uncheckedThumbColor = K.Sub, uncheckedTrackColor = Color(0x33FFFFFF)),
                            )
                        }
                        HDivider()
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            T("تغییرات از اتصال بعدی اعمال می‌شود", size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
        }
    }
}
