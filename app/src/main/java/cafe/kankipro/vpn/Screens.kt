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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

    Box(Modifier.fillMaxSize()) {
    EmergencyBox(Modifier.align(Alignment.TopEnd).padding(12.dp))
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Logo3D(124.dp)
        Spacer(Modifier.height(14.dp))
        BrandTitle(32.sp)
        T("Secure · Fast · Low Ping", size = 13.sp, color = K.Sub, font = Poppins, weight = FontWeight.SemiBold, ltr = true)
        Spacer(Modifier.height(32.dp))

        KCard(Modifier.fillMaxWidth(), padding = 18.dp, border = K.Gold.copy(alpha = .45f)) {
            T("ورود به حساب", size = 22.sp, weight = FontWeight.Black)
            Spacer(Modifier.height(6.dp))
            T("لینک یا کد اشتراکی که از ربات گرفتید را وارد کنید", size = 13.sp, color = K.Sub, weight = FontWeight.Normal)
            Spacer(Modifier.height(18.dp))

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; state.error = null },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = K.Text),
                    placeholder = { T("kanki-4-84956546746714973", size = 14.sp, color = K.Sub.copy(alpha = .5f), font = Poppins, weight = FontWeight.SemiBold, ltr = true) },
                    shape = RoundedCornerShape(18.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = K.Gold, unfocusedBorderColor = K.Line,
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
                    scope.launch { state.login(text) }
                }
                LineButton("جای‌گذاری", Modifier.weight(1f)) {
                    clip.getText()?.text?.let { text = it.trim() }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        T("اشتراک ندارید؟", size = 13.sp, color = K.Sub, weight = FontWeight.Normal)
        Spacer(Modifier.height(8.dp))
        RobotMascot { openBot(ctx) }
    }
    }
}

/** کادر کوچک گوشه‌ی صفحه‌ی ورود: فقط برای وقتی که اینترنت قطع است */
@Composable
fun EmergencyBox(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .widthIn(max = 150.dp)
            .clip(shape)
            .background(Color(0xCC000000))
            .border(1.dp, K.Gold.copy(alpha = .6f), shape)
            .clickable { Emergency.open(ctx) }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Logo3D(34.dp, animate = false)
        T("اضطراری", size = 11.sp, color = K.Gold, weight = FontWeight.Black)
        T("فقط وقتی اینترنت قطع است از این بخش استفاده کنید", size = 9.sp, color = K.Sub, weight = FontWeight.Normal)
    }
}

// ================================================================== قاب اصلی

@Composable
fun MainScreen(state: AppState, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(KTab.HOME) }
    var sheet by remember { mutableStateOf(false) }
    var showSplit by remember { mutableStateOf(false) }
    var askNode by remember { mutableStateOf<String?>(null) }
    var askProto by remember { mutableStateOf<String?>(null) }

    // نقشه‌ی کشورها (یک بار از فایل خوانده می‌شود)
    var countries by remember { mutableStateOf(MapData.countries) }
    LaunchedEffect(Unit) { if (countries == null) countries = withContext(Dispatchers.Default) { MapData.load(ctx) } }

    // آموزش ربات: بار اول بعد از ورود خودکار شروع می‌شود
    LaunchedEffect(state.account != null) {
        if (state.account != null && !state.guideDone && !Guide.active) {
            delay(900)
            tab = KTab.HOME
            Guide.start()
        }
    }
    // وقتی آموزش در حال اجراست، صفحه‌ی خانه نشان داده شود
    LaunchedEffect(Guide.step) { if (Guide.active) { tab = KTab.HOME; sheet = false; state.accountOpen = false } }

    fun startGuide() { tab = KTab.HOME; Guide.start() }

    MainFrame(tab, onTab = { tab = it }, onRobot = { startGuide() }, onAccount = { state.accountOpen = true }) {
        when (tab) {
            KTab.HOME -> HomeTab(state, countries, onToggle, openSheet = { sheet = true }, askProto = { askProto = it })
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

    // انتخاب سریع سرور
    val a = state.account
    ServerSheet(
        visible = sheet && a != null,
        nodes = a?.configs?.distinctBy { it.node }?.map { n ->
            NodeUi(n.node, n.nodeName, flagOf(n.nodeName), a.configs.count { it.node == n.node && state.hasCfg(n.node, it.protocol) }, state.nodePing(n.node), n.node == state.node)
        } ?: emptyList(),
        smartBusy = state.smartBusy,
        onPick = { id ->
            sheet = false
            when {
                id == state.node -> {}
                state.vstate == VState.ON -> askNode = id
                state.vstate == VState.OFF -> scope.launch { state.switchNode(id) }
            }
        },
        onSmart = { sheet = false; state.smartConnect() },
        onAll = { sheet = false; tab = KTab.SERVERS },
        onClose = { sheet = false },
    )

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

    askNode?.let { target ->
        val name = a?.configs?.firstOrNull { it.node == target }?.nodeName ?: target
        ConfirmDialog(trf("سوییچ به %s", name), trf("اتصال فعلی قطع و به %s وصل می‌شود.", name),
            onOk = { askNode = null; scope.launch { state.switchNode(target) } }, onCancel = { askNode = null })
    }
    askProto?.let { target ->
        ConfirmDialog(trf("سوییچ به %s", protoTitle(target)), trf("اتصال فعلی قطع و با %s دوباره وصل می‌شود.", protoTitle(target)),
            onOk = { askProto = null; scope.launch { state.switchTo(target) } }, onCancel = { askProto = null })
    }

    GuideOverlay { state.guideDone = true }
}

@Composable
private fun ConfirmDialog(title: String, text: String, onOk: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = K.Panel,
        title = { T(title, size = 18.sp, weight = FontWeight.Black) },
        text = { T(text, size = 14.sp, color = K.Sub, weight = FontWeight.Normal) },
        confirmButton = { TextButton(onClick = onOk) { T("تأیید و اتصال", color = K.Gold) } },
        dismissButton = { TextButton(onClick = onCancel) { T("انصراف", color = K.Sub) } },
    )
}

// ================================================================== تب خانه

@Composable
private fun HomeTab(state: AppState, countries: List<Country>?, onToggle: () -> Unit, openSheet: () -> Unit, askProto: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val a = state.account

    // سرعت لحظه‌ای، نمودار و زمان اتصال
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var rx by remember { mutableLongStateOf(0L) }
    var tx by remember { mutableLongStateOf(0L) }
    val history = remember { mutableStateListOf<Float>() }
    LaunchedEffect(state.vstate) {
        var last: Pair<Long, Long>? = null
        var tick = 0
        while (state.vstate == VState.ON) {
            now = System.currentTimeMillis()
            val s = VpnEngine.stats(ctx)
            if (s != null) {
                last?.let { p -> rx = (s.first - p.first).coerceAtLeast(0); tx = (s.second - p.second).coerceAtLeast(0) }
                last = s
            }
            history.add((rx + tx).toFloat())
            if (history.size > 40) history.removeAt(0)
            tick++
            if (tick % 15 == 0) state.measurePing()
            if (tick % 30 == 0) state.refreshUsage()   // حجم مصرفی هر ۳۰ ثانیه بروز می‌شود
            delay(1000)
        }
        rx = 0; tx = 0
    }
    // هشدارهای زمانی (مثل روزهای باقی‌مانده) هر دقیقه دوباره بررسی شوند
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }

    val link = a?.configs?.firstOrNull { it.node == state.node }
    val direct = state.route == "direct"
    val protos = buildList {
        state.available(state.node, "tunnel").forEach { add(SegItem(it, protoShort(it))) }
        if (state.available(state.node, "direct").isNotEmpty()) add(SegItem("amneziawg-direct", "مستقیم", icon = "bolt"))
    }
    val used = a?.usedGb
    val limit = a?.limitGb
    // پیام ادمین وقتی VPN خاموش است هر ۵ دقیقه هم چک می‌شود (وقتی وصل است، بروزرسانی ۳۰ ثانیه‌ای بالا آن را می‌آورد)
    LaunchedEffect(Unit) { while (true) { delay(5 * 60_000L); if (state.vstate == VState.OFF) state.refreshUsage() } }
    val alert = robotAlertFor(a, now, state.error, state.errorDetail, state.closedNotice)?.takeIf { it.id !in state.dismissed }

    val ui = HomeUi(
        vstate = state.vstate,
        hasAccount = a != null,
        loading = state.loading,
        serverName = link?.nodeName ?: "—",
        serverFlag = link?.let { flagOf(it.nodeName) } ?: "🌐",
        serverSub = (if (direct) "AmneziaWG" else protoTitle(state.proto)) + " · " + tr(if (direct) "مستقیم" else "تانل"),
        serverPing = if (state.vstate == VState.ON) state.ping else state.protoPing(state.node, state.proto),
        livePing = state.ping,
        rx = rx, tx = tx, history = history,
        elapsedSec = if (state.vstate == VState.ON && VpnEngine.connectedAt > 0) ((now - VpnEngine.connectedAt) / 1000).coerceAtLeast(0) else null,
        protos = protos,
        selectedProto = state.proto,
        sub = a?.let {
            SubUi(
                dataLeft = it.dataRemaining,
                timeLeft = it.timeRemaining,
                status = it.status,
                leftFraction = if (limit != null && used != null && limit > 0) ((limit - used) / limit).toFloat() else null,
            )
        },
        smartBusy = state.smartBusy,
        smartOpen = state.smartOpen,
        smartRows = state.smartRows.map { SmartUi(it.flag, it.title, it.ping, it.jitter) },
        alert = alert,
        countries = countries,
        pins = a?.configs?.distinctBy { it.node }?.mapNotNull { l ->
            (state.nodeGeo[l.node] ?: geoOfName(l.nodeName))?.let { MapPin(it, state.nodeIso[l.node] ?: isoOfFlag(flagOf(l.nodeName)), l.node == state.node) }
        } ?: emptyList(),
        userGeo = state.userGeo,
        userIso = state.userIso,
    )

    HomeContent(
        ui,
        HomeActions(
            toggle = onToggle,
            openServers = openSheet,
            smart = { state.smartConnect() },
            closeSmart = { state.smartOpen = false },
            pickProto = { p ->
                when (state.vstate) {
                    VState.ON -> askProto(p)
                    VState.OFF -> state.selectProto(p)
                    VState.CONNECTING -> {}
                }
            },
            openAccount = { state.accountOpen = true },
            alertAction = { id ->
                when (id) {
                    "renew" -> openBot(ctx)
                    "support" -> openSupport(ctx)
                    "smart" -> { state.error = null; state.errorDetail = null; state.smartConnect() }
                }
            },
            dismissAlert = {
                alert?.let { al ->
                    state.dismissed.add(al.id)
                    if (al.id.startsWith("msg:")) state.closeNotice(al.id.removePrefix("msg:"))
                    if (al.id.startsWith("err:")) { state.error = null; state.errorDetail = null }
                }
            },
            retry = { scope.launch { state.refresh() } },
        ),
    )
}

// ================================================================== تب سرورها

@Composable
private fun ServersTab(state: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val a = state.account
    val locked = state.vstate != VState.OFF
    val all = listOf("amneziawg", "amneziawg-direct", "wireguard", "hysteria2")
    val cards = a?.configs?.groupBy { it.node }?.map { (nodeId, links) ->
        val name = links.first().nodeName
        val protos = all.filter { p -> state.hasCfg(nodeId, p) && links.any { it.protocol == p } }.map { p ->
            val k = "$nodeId/$p"
            ProtoRowUi(
                key = p,
                selected = state.node == nodeId && state.protoKey() == p,
                udp = state.udpPings[k], udpBusy = state.busyTests["udp:$k"] == true,
                tcp = state.tcpPings[k], tcpBusy = state.busyTests["tcp:$k"] == true,
                host = state.infos[k]?.host?.takeIf { it.isNotBlank() && it != "—" },
            )
        }
        ServerCardUi(nodeId, name, flagOf(name), state.node == nodeId, state.openNode == nodeId, state.nodePing(nodeId), protos)
    } ?: emptyList()

    Column(Modifier.fillMaxSize()) {
        LineButton(
            "🆘 حالت اضطراری (وقتی اینترنت قطع است)",
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            color = K.Red,
        ) { Emergency.open(ctx) }
        Box(Modifier.weight(1f)) {
    ServersContent(
        cards, locked,
        tip = if (state.tipsSeen["servers"] == true) null else RobotAlert("tip_servers", 0, "روی هر سرور بزن تا پروتکل‌هاش باز بشه. UDP پینگ واقعی داخل تونله و TCP پینگ خود سرور. با ⚙ هم MTU رو تنظیم می‌کنی."),
        act = ServersActions(
            toggle = { id -> state.openNode = if (state.openNode == id) null else id },
            select = { id, p ->
                if (locked) toast(ctx, "برای تغییر، اول اتصال را قطع کنید")
                else { state.node = id; state.selectProto(p) }
            },
            udp = { id, p ->
                if (state.vstate != VState.OFF) toast(ctx, "برای تست واقعی UDP، اول اتصال را قطع کنید")
                else state.requestVpn?.invoke { scope.launch { state.realTest(id, p) } }
            },
            tcp = { id, p -> scope.launch { state.tcpTest(id, p) } },
            testAll = { id ->
                val list = cards.firstOrNull { it.id == id }?.protos?.map { it.key } ?: emptyList()
                if (state.vstate != VState.OFF) scope.launch { list.forEach { state.tcpTest(id, it) } }
                else state.requestVpn?.invoke { scope.launch { list.forEach { state.realTest(id, it) } } }
            },
            refresh = { id -> scope.launch { state.refreshNode(id); toast(ctx, "کانفیگ‌ها بروز شد") } },
            mtu = { p -> state.mtuFor(p) },
            setMtu = { p, v -> state.setMtu(p, v) },
            closeTip = { state.markTip("servers") },
        ),
    )
        }
    }
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
