package cafe.kankipro.vpn

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

const val APP_VERSION = "1.1.0"

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun openBot(ctx: Context) = openUrl(ctx, BOT_URL)
fun openSupport(ctx: Context) = openUrl(ctx, SUPPORT_URL)

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, tr(msg), Toast.LENGTH_SHORT).show()

fun faStatus(s: String) = when (s.lowercase()) {
    "active" -> "فعال"
    "expired" -> "منقضی شده"
    "disabled", "inactive" -> "غیرفعال"
    "limited" -> "حجم تمام شده"
    "frozen", "suspended" -> "متوقف"
    else -> s
}

fun isActive(s: String) = s.equals("Active", true)

private val flagTable = listOf(
    listOf("DE", "GER", "GERMANY", "DEUTSCHLAND", "FRANKFURT", "BERLIN", "آلمان") to "🇩🇪",
    listOf("TR", "TUR", "TURKEY", "TURKIYE", "ISTANBUL", "ترکیه") to "🇹🇷",
    listOf("IR", "IRAN", "TEHRAN", "ایران", "تهران") to "🇮🇷",
    listOf("NL", "NETHERLANDS", "HOLLAND", "AMSTERDAM", "هلند") to "🇳🇱",
    listOf("FI", "FINLAND", "HELSINKI", "فنلاند") to "🇫🇮",
    listOf("FR", "FRANCE", "PARIS", "فرانسه") to "🇫🇷",
    listOf("UK", "GB", "ENGLAND", "LONDON", "انگلیس") to "🇬🇧",
    listOf("US", "USA", "AMERICA", "آمریکا") to "🇺🇸",
    listOf("AE", "UAE", "DUBAI", "امارات") to "🇦🇪",
    listOf("RU", "RUSSIA", "MOSCOW", "روسیه") to "🇷🇺",
    listOf("SE", "SWEDEN", "سوئد") to "🇸🇪",
    listOf("CA", "CANADA", "کانادا") to "🇨🇦",
    listOf("PL", "POLAND", "لهستان") to "🇵🇱",
    listOf("AT", "AUSTRIA", "اتریش") to "🇦🇹",
    listOf("AM", "ARMENIA", "ارمنستان") to "🇦🇲",
    listOf("SG", "SINGAPORE", "سنگاپور") to "🇸🇬",
    listOf("JP", "JAPAN", "ژاپن") to "🇯🇵",
)

/** پرچم را از اسم سرور تشخیص می‌دهد (فقط کلمه‌های کامل) */
fun flagOf(name: String): String {
    val tokens = name.uppercase().split(Regex("[^A-Z\\u0600-\\u06FF]+")).filter { it.isNotBlank() }.toSet()
    return flagTable.firstOrNull { (keys, _) -> keys.any { it in tokens || (it.length > 3 && name.contains(it, ignoreCase = true)) } }?.second ?: "🌐"
}

fun protoTitle(p: String) = when (p) {
    "amneziawg" -> "AmneziaWG"
    "amneziawg-direct" -> "Amnezia Direct"
    "wireguard" -> "WireGuard"
    "ikev2" -> "IKEv2"
    "openvpn/udp" -> "OpenVPN UDP"
    "openvpn/tcp" -> "OpenVPN TCP"
    else -> "OpenVPN"
}

fun pingColor(ms: Long) = when {
    ms < 0 -> K.Red
    ms < 90 -> K.Green
    ms < 200 -> K.Gold
    else -> K.Red
}

fun dnsTitle(dns: String) = DnsOptions.list.firstOrNull { it.first == dns }?.second ?: "دلخواه · $dns"

// ================================================================== ورود

@Composable
fun LoginScreen(state: AppState) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Logo3D(130.dp)
        Spacer(Modifier.height(16.dp))
        BrandTitle(34.sp)
        T("Secure · Fast · Low Ping", size = 13.sp, color = K.Sub, font = Poppins, weight = FontWeight.SemiBold, ltr = true)
        Spacer(Modifier.height(36.dp))

        Glass(Modifier.fillMaxWidth()) {
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
                    placeholder = { T("bub-1-xxxxxxxx", size = 14.sp, color = K.Sub.copy(alpha = .5f), font = Poppins, weight = FontWeight.SemiBold, ltr = true) },
                    shape = RoundedCornerShape(20.dp),
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

// ================================================================== قاب اصلی

enum class KTab(val title: String, val icon: String) { HOME("خانه", "⌂"), SERVERS("سرورها", "☰"), TEST("تست", "⚡"), SETTINGS("تنظیمات", "⚙") }

@Composable
fun MainScreen(state: AppState, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(KTab.HOME) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo3D(42.dp)
            Spacer(Modifier.width(10.dp))
            BrandTitle(21.sp)
            Spacer(Modifier.weight(1f))
            LangButton(state)
            Spacer(Modifier.width(8.dp))
            IconCircle(42.dp, onClick = { state.accountOpen = true }) { c -> accountIcon(c) }
        }

        Box(Modifier.weight(1f)) {
            when (tab) {
                KTab.HOME -> HomeTab(state, onToggle)
                KTab.SERVERS -> ServersTab(state)
                KTab.TEST -> TestTab(state)
                KTab.SETTINGS -> SettingsTab(state)
            }
        }

        BottomBar(tab) { tab = it }
    }
    AccountPanel(state)
}

/** پنل اطلاعات حساب (از دکمه‌ی حساب در بالای صفحه) */
@Composable
fun AccountPanel(state: AppState) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val a = state.account
    AnimatedVisibility(visible = state.accountOpen) {
        Box(
            Modifier.fillMaxSize().background(Color(0xCC000000))
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { state.accountOpen = false },
            contentAlignment = Alignment.Center,
        ) {
            Glass(
                Modifier.padding(20.dp).fillMaxWidth()
                    .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconCircle(46.dp, onClick = {}) { c -> accountIcon(c) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        T(a?.username ?: "—", size = 18.sp, font = Poppins, weight = FontWeight.Bold, ltr = true)
                        T("اطلاعات حساب", size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
                    }
                    if (a != null) Pill(faStatus(a.status), color = if (isActive(a.status)) K.Green else K.Red)
                }
                Spacer(Modifier.height(12.dp))
                if (a != null) {
                    InfoRow("حجم باقی‌مانده", a.dataRemaining)
                    InfoRow("مصرف‌شده", a.used)
                    InfoRow("زمان باقی‌مانده", a.timeRemaining)
                    InfoRow("تاریخ انقضا", if (a.expiry == "—") "بدون محدودیت" else a.expiry)
                    InfoRow("اتصال همزمان", a.connections)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LineButton("بروزرسانی", Modifier.weight(1f)) { scope.launch { state.refresh(); toast(ctx, "اطلاعات بروز شد") } }
                    LineButton("کپی سابلینک", Modifier.weight(1f)) {
                        clip.setText(AnnotatedString(state.subLink)); toast(ctx, "لینک اشتراک کپی شد")
                    }
                }
                Spacer(Modifier.height(8.dp))
                GoldButton("تمدید سرویس", Modifier.fillMaxWidth()) { openBot(ctx) }
                Spacer(Modifier.height(8.dp))
                LineButton("💬 پشتیبانی", Modifier.fillMaxWidth()) { openSupport(ctx) }
                Spacer(Modifier.height(8.dp))
                T("بستن", size = 13.sp, color = K.Sub, modifier = Modifier.align(Alignment.CenterHorizontally).clickable { state.accountOpen = false }.padding(8.dp))
            }
        }
    }
}

@Composable
fun BottomBar(current: KTab, onSelect: (KTab) -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(shape)
                .background(Brush.verticalGradient(listOf(K.Surface.copy(alpha = .96f), K.Panel.copy(alpha = .98f))))
                .border(1.dp, Brush.horizontalGradient(listOf(K.Gold.copy(alpha = .15f), K.Gold.copy(alpha = .55f), K.Gold.copy(alpha = .15f))), shape)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KTab.entries.forEachIndexed { i, t ->
                val sel = t == current
                val s = RoundedCornerShape(22.dp)
                Row(
                    Modifier.weight(if (sel) 1.6f else 1f).height(46.dp).clip(s)
                        .background(if (sel) Brush.verticalGradient(listOf(K.Shine, K.Gold, K.GoldDeep)) else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)))
                        .clickable { onSelect(t) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Canvas(Modifier.size(20.dp)) { navIcon(i, if (sel) K.OnGold else K.Sub) }
                    if (sel) {
                        Spacer(Modifier.width(6.dp))
                        T(t.title, size = 12.sp, color = K.OnGold, weight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

// ================================================================== تب خانه

@Composable
fun HomeTab(state: AppState, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val a = state.account

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var rxSpeed by remember { mutableLongStateOf(0L) }
    var txSpeed by remember { mutableLongStateOf(0L) }
    val history = remember { mutableStateListOf<Float>() }
    LaunchedEffect(state.vstate) {
        var last: Pair<Long, Long>? = null
        var tick = 0
        while (state.vstate == VState.ON) {
            now = System.currentTimeMillis()
            val s = VpnEngine.stats(ctx)
            if (s != null) {
                val prev = last
                if (prev != null) {
                    rxSpeed = (s.first - prev.first).coerceAtLeast(0)
                    txSpeed = (s.second - prev.second).coerceAtLeast(0)
                }
                last = s
            }
            history.add((rxSpeed + txSpeed).toFloat())
            if (history.size > 40) history.removeAt(0)
            tick++
            if (tick % 15 == 0) state.measurePing()
            delay(1000)
        }
        rxSpeed = 0; txSpeed = 0
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val on = state.vstate == VState.ON
        // ------------------------------------------------ نقشه
        val pins = a?.configs?.distinctBy { it.node }?.mapNotNull { l ->
            (state.nodeGeo[l.node] ?: geoOfName(l.nodeName))?.let { MapPin(it, state.nodeIso[l.node] ?: isoOfFlag(flagOf(l.nodeName)), l.node == state.node) }
        } ?: emptyList()
        val mapShape = RoundedCornerShape(26.dp)
        Box(
            Modifier.fillMaxWidth().padding(top = 4.dp).clip(mapShape)
                .background(K.Panel).border(1.2.dp, K.Line, mapShape),
        ) {
            WorldMap(pins, state.userGeo, state.userIso, on, Modifier.fillMaxWidth().aspectRatio(1.55f))
            // پایین نقشه: وضعیت امنیت · موج ترافیک با دانلود/آپلود · پینگ
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, K.Panel.copy(alpha = .85f))))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(K.Panel.copy(alpha = .7f))
                        .border(1.dp, (if (on) K.Ok else K.Red).copy(alpha = .7f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dot(if (on) K.Ok else K.Red, 6.dp)
                    Spacer(Modifier.width(5.dp))
                    T(if (on) "محافظت‌شده" else "محافظت‌نشده", size = 10.sp, color = if (on) K.Ok else K.Red)
                }
                Column(Modifier.weight(1f).padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    WaveRibbon(history, on, Modifier.fillMaxWidth().height(30.dp))
                    T(
                        if (on) "↓ ${formatBytes(rxSpeed)}/s   ↑ ${formatBytes(txSpeed)}/s" else "↓ —   ↑ —",
                        size = 9.sp, font = Poppins, weight = FontWeight.SemiBold, color = K.Text, ltr = true,
                    )
                }
                val pg = state.ping
                T(
                    if (on && pg != null) "${pg}ms" else "— ms",
                    size = 11.sp, font = Poppins, weight = FontWeight.Bold,
                    color = if (on && pg != null) pingColor(pg) else K.Sub, ltr = true,
                )
            }
        }

        // ------------------------------------------------ دکمه‌ی اتصال
        Spacer(Modifier.height(4.dp))
        ConnectButton(state.vstate, 168.dp) { if (a != null) onToggle() }
        val (label, color) = when (state.vstate) {
            VState.ON -> "متصل" to K.Ok
            VState.CONNECTING -> "در حال اتصال…" to K.Gold
            VState.OFF -> "آماده‌ی اتصال" to K.Text
        }
        T(label, size = 24.sp, color = color, weight = FontWeight.Black, shadow = glow(color, 26f))
        if (on && VpnEngine.connectedAt > 0) {
            val sec = ((now - VpnEngine.connectedAt) / 1000).coerceAtLeast(0)
            T(
                String.format(java.util.Locale.US, "%02d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60),
                size = 26.sp, font = Poppins, weight = FontWeight.Bold, shadow = glow(K.Ok, 18f), ltr = true,
            )
        }

        state.error?.let {
            Spacer(Modifier.height(6.dp))
            T(it, size = 12.sp, color = K.Red)
        }

        Spacer(Modifier.height(12.dp))
        if (a == null) {
            if (state.loading) CircularProgressIndicator(color = K.Gold)
            else GoldButton("تلاش دوباره", Modifier.fillMaxWidth()) { scope.launch { state.refresh() } }
        } else {
            ConnectPanel(state, a)
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** پنل جمع‌وجور صفحه‌ی اتصال: تانل/مستقیم، پروتکل، سرور و اطلاعات اشتراک */
@Composable
fun ConnectPanel(state: AppState, a: Account) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var askProto by remember { mutableStateOf<String?>(null) }
    var askNode by remember { mutableStateOf<ConfigLink?>(null) }
    val busy = state.vstate == VState.CONNECTING
    val on = state.vstate == VState.ON
    val nodes = a.configs.distinctBy { it.node }

    Glass(Modifier.fillMaxWidth()) {
        // تانل / مستقیم
        val shape = RoundedCornerShape(18.dp)
        Row(Modifier.fillMaxWidth().clip(shape).background(K.Panel.copy(alpha = .8f)).border(1.dp, K.Line, shape).padding(3.dp)) {
            listOf("tunnel" to "تانل", "direct" to "مستقیم").forEach { (r, title) ->
                val sel = state.route == r
                val has = state.available(state.node, r).isNotEmpty()
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(15.dp))
                        .background(if (sel) Brush.verticalGradient(listOf(K.Shine, K.Gold, K.GoldDeep)) else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)))
                        .clickable(enabled = has && !busy) {
                            if (sel) return@clickable
                            if (on) toast(ctx, "برای تغییر، اول اتصال را قطع کنید") else state.setRoute(r)
                        }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) { T(if (r == "direct") "⚡ $title" else "🛡 $title", size = 13.sp, color = if (sel) K.OnGold else if (has) K.Text else K.Sub.copy(alpha = .5f), weight = FontWeight.Black) }
            }
        }
        // پروتکل‌های این مسیر
        val av = state.available()
        if (av.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                av.forEach { p ->
                    val sel = state.proto == p
                    val cs = RoundedCornerShape(14.dp)
                    Box(
                        Modifier.weight(1f).clip(cs)
                            .background(if (sel) K.Gold.copy(alpha = .20f) else Color(0x12FFFFFF))
                            .border(if (sel) 1.5.dp else 1.dp, if (sel) K.Gold else K.Line, cs)
                            .clickable(enabled = !busy) {
                                if (sel) return@clickable
                                if (on) askProto = p else { state.proto = p; state.save() }
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) { T(protoShort(p), size = 12.sp, font = Poppins, weight = FontWeight.Bold, color = if (sel) K.Gold else K.Text, ltr = true) }
                }
            }
        }
        // سرورها
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            nodes.forEach { n ->
                val sel = n.node == state.node
                val cs = RoundedCornerShape(14.dp)
                Row(
                    Modifier.clip(cs)
                        .background(if (sel) K.Gold.copy(alpha = .20f) else Color(0x12FFFFFF))
                        .border(if (sel) 1.5.dp else 1.dp, if (sel) K.Gold else K.Line, cs)
                        .clickable(enabled = !busy) {
                            if (sel) return@clickable
                            if (on) askNode = n else scope.launch { state.switchNode(n.node) }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T(flagOf(n.nodeName), size = 15.sp)
                    Spacer(Modifier.width(6.dp))
                    T(n.nodeName, size = 11.sp, font = Poppins, weight = FontWeight.Bold, color = if (sel) K.Gold else K.Text, ltr = true)
                }
            }
        }
        // اطلاعات اشتراک با آیکون (جمع‌وجور)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            InfoChip("📦", a.dataRemaining, Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            InfoChip("⏳", a.timeRemaining, Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            InfoChip(if (isActive(a.status)) "🟢" else "🔴", faStatus(a.status), Modifier.weight(1f))
        }
        val used = parseSize(a.used)
        val left = parseSize(a.dataRemaining)
        if (used != null && left != null && used + left > 0) {
            Spacer(Modifier.height(8.dp))
            val f = (used / (used + left)).toFloat().coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x22FFFFFF))) {
                Box(Modifier.fillMaxWidth(f.coerceAtLeast(0.02f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(listOf(K.GoldDeep, K.Gold, K.GoldLight))))
            }
        }
    }

    askProto?.let { target ->
        AlertDialog(
            onDismissRequest = { askProto = null },
            containerColor = K.Panel,
            title = { T("سوییچ به ${protoTitle(target)}", size = 18.sp, weight = FontWeight.Black) },
            text = { T("اتصال فعلی قطع و با ${protoTitle(target)} دوباره وصل می‌شود.", size = 14.sp, color = K.Sub, weight = FontWeight.Normal) },
            confirmButton = { TextButton(onClick = { askProto = null; scope.launch { state.switchTo(target) } }) { T("تأیید و اتصال", color = K.Gold) } },
            dismissButton = { TextButton(onClick = { askProto = null }) { T("انصراف", color = K.Sub) } },
        )
    }
    askNode?.let { target ->
        AlertDialog(
            onDismissRequest = { askNode = null },
            containerColor = K.Panel,
            title = { T("سوییچ به ${target.nodeName}", size = 18.sp, weight = FontWeight.Black) },
            text = { T("اتصال فعلی قطع و به ${target.nodeName} وصل می‌شود.", size = 14.sp, color = K.Sub, weight = FontWeight.Normal) },
            confirmButton = { TextButton(onClick = { askNode = null; scope.launch { state.switchNode(target.node) } }) { T("تأیید و اتصال", color = K.Gold) } },
            dismissButton = { TextButton(onClick = { askNode = null }) { T("انصراف", color = K.Sub) } },
        )
    }
}

fun protoShort(p: String) = when (p) {
    "amneziawg" -> "Amnezia"
    "amneziawg-direct" -> "Amnezia"
    "wireguard" -> "WireGuard"
    "ikev2" -> "IKEv2"
    else -> p
}

@Composable
fun InfoChip(icon: String, value: String, modifier: Modifier) {
    val cs = RoundedCornerShape(12.dp)
    Row(
        modifier.clip(cs).background(Color(0x10FFFFFF)).border(1.dp, K.Line, cs).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        T(icon, size = 12.sp)
        Spacer(Modifier.width(4.dp))
        val latin = value.any { it.isDigit() } && value.none { it in '\u0600'..'\u06FF' }
        T(value, size = 11.sp, font = if (latin) Poppins else Vazir, weight = FontWeight.Bold, ltr = latin)
    }
}

/** موج ترافیک (چند منحنی سینوسی درهم + خط نبض)، بدون قاب */
@Composable
fun WaveRibbon(samples: List<Float>, active: Boolean, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "wave")
    val ph by t.animateFloat(0f, 6.2832f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "ph")
    val max = (samples.maxOrNull() ?: 1f).coerceAtLeast(1f)
    val level = if (active) (.25f + .75f * ((samples.lastOrNull() ?: 0f) / max)) else .12f
    val c1 = if (active) K.Ok else K.Gold
    val c2 = K.Accent
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val mid = h / 2
        // منحنی‌های درهم (مثل روبان)
        for (i in 0 until 12) {
            val f = i / 11f
            val path = androidx.compose.ui.graphics.Path()
            val steps = 48
            for (k in 0..steps) {
                val x = k / steps.toFloat()
                val env = kotlin.math.sin(x * Math.PI).toFloat()
                val y = mid + h * .42f * level * env * kotlin.math.sin(x * 9f + ph + f * 1.6f).toFloat() * (.35f + .65f * f)
                if (k == 0) path.moveTo(x * w, y) else path.lineTo(x * w, y)
            }
            val col = if (i % 3 == 0) c2 else c1
            drawPath(path, col.copy(alpha = .10f + .25f * f), style = androidx.compose.ui.graphics.drawscope.Stroke(0.8.dp.toPx()))
        }
        // خط نبض روشن از ترافیک واقعی
        val n = samples.size.coerceAtLeast(2)
        val pulse = androidx.compose.ui.graphics.Path()
        for (i in 0 until 40) {
            val sIdx = samples.size - 40 + i
            val v = if (sIdx >= 0) samples[sIdx] / max else 0f
            val x = i / 39f * w
            val spike = if (i % 2 == 0) -1f else 1f
            val y = mid + spike * h * .45f * (if (active) v * .9f else .04f)
            if (i == 0) pulse.moveTo(x, y) else pulse.lineTo(x, y)
        }
        drawPath(pulse, Color.White.copy(alpha = if (active) .7f else .25f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .6f), Color.Transparent)), androidx.compose.ui.geometry.Offset(0f, mid), androidx.compose.ui.geometry.Offset(w, mid), 1.2.dp.toPx())
        // ذرات
        for (i in 0 until 14) {
            val x = ((i * 0.071f + ph / 6.2832f * .15f) % 1f) * w
            val y = mid + kotlin.math.sin(i * 1.7f + ph).toFloat() * h * .35f * level
            drawCircle(Color.White.copy(alpha = .5f), 1.dp.toPx(), androidx.compose.ui.geometry.Offset(x, y))
        }
        if (n < 0) return@Canvas
    }
}


/** یک کارت جمع‌وجور: سرور + اشتراک + ترافیک */
@Composable
fun ServerInfoCard(state: AppState, a: Account) {
    val nodeName = a.configs.firstOrNull { it.node == state.node }?.nodeName ?: "—"
    val nodes = a.configs.distinctBy { it.node }
    var ask by remember { mutableStateOf<ConfigLink?>(null) }
    val hscope = rememberCoroutineScope()
    Glass(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(flagOf(nodeName), size = 26.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                T(nodeName, size = 16.sp, font = Poppins, weight = FontWeight.Bold, ltr = true)
                T(protoTitle(state.protoKey()), size = 11.sp, color = K.Sub, font = Poppins, weight = FontWeight.SemiBold, ltr = true)
            }
            Pill(faStatus(a.status), color = if (isActive(a.status)) K.Green else K.Red)
            Spacer(Modifier.width(6.dp))
            val ctx = LocalContext.current
            IconCircle(34.dp, onClick = { hscope.launch { state.refresh(); toast(ctx, "اطلاعات اشتراک بروز شد") } }) { c -> refreshIcon(c) }
        }
        // سوییچ سریع لوکیشن با یک لمس
        if (nodes.size > 1) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                nodes.forEach { n ->
                    val sel = n.node == state.node
                    val shape = RoundedCornerShape(16.dp)
                    Row(
                        Modifier.clip(shape)
                            .background(if (sel) K.Gold.copy(alpha = 0.22f) else Color(0x12FFFFFF))
                            .border(if (sel) 1.5.dp else 1.dp, if (sel) K.Gold else K.Line, shape)
                            .clickable {
                                if (sel || state.vstate == VState.CONNECTING) return@clickable
                                if (state.vstate == VState.ON) ask = n
                                else hscope.launch { state.switchNode(n.node) }
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        T(flagOf(n.nodeName), size = 16.sp)
                        Spacer(Modifier.width(6.dp))
                        T(n.nodeName, size = 12.sp, font = Poppins, weight = FontWeight.Bold, color = if (sel) K.Gold else K.Text, ltr = true)
                    }
                }
            }
        }
        ask?.let { target ->
            AlertDialog(
                onDismissRequest = { ask = null },
                containerColor = K.Panel,
                title = { T("سوییچ به ${target.nodeName}", size = 18.sp, weight = FontWeight.Black) },
                text = { T("اتصال فعلی قطع و به ${target.nodeName} وصل می‌شود.", size = 14.sp, color = K.Sub, weight = FontWeight.Normal) },
                confirmButton = { TextButton(onClick = { ask = null; hscope.launch { state.switchNode(target.node) } }) { T("تأیید و اتصال", color = K.Gold) } },
                dismissButton = { TextButton(onClick = { ask = null }) { T("انصراف", color = K.Sub) } },
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStat("حجم باقی‌مانده", a.dataRemaining, Modifier.weight(1f))
            MiniStat("زمان باقی‌مانده", a.timeRemaining, Modifier.weight(1f))
        }

        val used = parseSize(a.used)
        val left = parseSize(a.dataRemaining)
        if (used != null && left != null && used + left > 0) {
            Spacer(Modifier.height(10.dp))
            val f = (used / (used + left)).toFloat().coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x33FFFFFF))) {
                Box(
                    Modifier.fillMaxWidth(f.coerceAtLeast(0.02f)).fillMaxHeight().clip(RoundedCornerShape(6.dp))
                        .background(Brush.horizontalGradient(listOf(K.GoldDeep, K.Gold, K.GoldLight))),
                )
            }
            Spacer(Modifier.height(4.dp))
            Row {
                T("مصرف‌شده", size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
                Spacer(Modifier.weight(1f))
                T(a.used, size = 11.sp, color = K.Sub, font = Poppins, weight = FontWeight.SemiBold, ltr = true)
            }
        }
    }
}

/** صفحه‌ی اتصال: فقط خط حجم مصرفی */
@Composable
fun UsageLine(a: Account) {
    val used = parseSize(a.used)
    val left = parseSize(a.dataRemaining)
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(K.Panel.copy(alpha = .7f)).border(1.dp, K.Line, shape).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("حجم باقی‌مانده", size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
            Spacer(Modifier.weight(1f))
            T(a.dataRemaining, size = 13.sp, font = Poppins, weight = FontWeight.Bold, ltr = true)
        }
        if (used != null && left != null && used + left > 0) {
            Spacer(Modifier.height(7.dp))
            val f = (used / (used + left)).toFloat().coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x33FFFFFF))) {
                Box(
                    Modifier.fillMaxWidth(f.coerceAtLeast(0.02f)).fillMaxHeight().clip(RoundedCornerShape(6.dp))
                        .background(Brush.horizontalGradient(listOf(K.GoldDeep, K.Gold, K.GoldLight))),
                )
            }
        }
    }
}

/** نمودار ضربان (مثل دستگاه نوار قلب) برای ترافیک لحظه‌ای */
@Composable
fun PulseGraph(samples: List<Float>, active: Boolean, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "pulse")
    val scan by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "scan")
    val col = if (active) K.Ok else K.Sub
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val n = 16
        val max = (samples.maxOrNull() ?: 1f).coerceAtLeast(1f)
        val base = h * .62f
        val path = androidx.compose.ui.graphics.Path().apply { moveTo(0f, base) }
        val seg = w / n
        for (i in 0 until n) {
            val sIdx = samples.size - n + i
            val v = if (sIdx >= 0) samples[sIdx] / max else 0f
            val amp = if (active) (.18f + .8f * v) else .10f
            val x0 = i * seg
            path.lineTo(x0 + seg * .35f, base)
            path.lineTo(x0 + seg * .45f, base + h * .10f)
            path.lineTo(x0 + seg * .55f, base - h * .62f * amp)
            path.lineTo(x0 + seg * .65f, base + h * .22f * amp)
            path.lineTo(x0 + seg * .75f, base)
            path.lineTo(x0 + seg, base)
        }
        for ((m, al) in listOf(4f to .10f, 2.2f to .25f, 1f to 1f)) {
            drawPath(path, col.copy(alpha = al), style = androidx.compose.ui.graphics.drawscope.Stroke(1.6.dp.toPx() * m, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        // خط اسکن متحرک
        val x = scan * w
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, col.copy(alpha = .35f), Color.Transparent), startX = x - w * .12f, endX = x + w * .02f),
            topLeft = androidx.compose.ui.geometry.Offset(x - w * .12f, 0f), size = androidx.compose.ui.geometry.Size(w * .14f, h),
        )
    }
}

@Composable
fun MiniStat(label: String, value: String, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(K.Gold.copy(alpha = 0.13f)).border(1.dp, K.Line, shape).padding(horizontal = 10.dp, vertical = 6.dp)) {
        T(label, size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
        val latin = value.any { it.isDigit() || it == '∞' } && value.none { it in '\u0600'..'\u06FF' }
        T(value, size = 14.sp, font = if (latin) Poppins else Vazir, weight = FontWeight.Bold, ltr = latin)
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        T(label, size = 11.sp, color = K.Sub)
        Spacer(Modifier.height(3.dp))
        T(value, size = 14.sp, font = Poppins, weight = FontWeight.Bold, ltr = true)
    }
}

// ================================================================== سوییچ پروتکل

@Composable
fun ProtocolSwitcher(current: String, available: List<String>, onPick: (String) -> Unit) {
    if (available.size <= 1) return
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(K.Panel.copy(alpha = .8f)).border(1.dp, K.Line, shape).padding(4.dp),
    ) {
        listOf("amneziawg" to "Amnezia", "amneziawg-direct" to "Direct ⚡", "wireguard" to "WireGuard", "ikev2" to "IKEv2").filter { it.first in available }.forEach { (p, title) ->
            val sel = p == current
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .background(if (sel) Brush.verticalGradient(listOf(K.Shine, K.Gold, K.GoldDeep)) else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable { onPick(p) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                T(title, size = 13.sp, font = Poppins, weight = FontWeight.Bold, color = if (sel) K.OnGold else K.Sub, ltr = true)
            }
        }
    }
}

// ================================================================== تب سرورها

@Composable
fun ServersTab(state: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val a = state.account
    val locked = state.vstate != VState.OFF
    val groups = a?.configs?.groupBy { it.node } ?: emptyMap()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        // ربات بالا سمت راست، عنوان سمت چپ
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RobotMascot { openBot(ctx) }
            Spacer(Modifier.weight(1f))
            T("سرورها", size = 17.sp, color = K.Gold, weight = FontWeight.Black)
        }
        Spacer(Modifier.height(8.dp))
        if (locked) {
            T("برای تغییر سرور یا پروتکل، اول اتصال را قطع کنید", size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
            Spacer(Modifier.height(8.dp))
        }
        if (groups.isEmpty()) T("سروری پیدا نشد", color = K.Sub)

        groups.forEach { (nodeId, links) ->
            val name = links.first().nodeName
            ServerGroup(
                flag = flagOf(name), name = name,
                sub = "",
                selected = state.node == nodeId,
                expanded = state.openNode == nodeId,
                onHeader = { state.openNode = if (state.openNode == nodeId) null else nodeId },
            ) {
                val cfgs = listOf("amneziawg", "amneziawg-direct", "wireguard").filter { p -> state.hasCfg(nodeId, p) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LineButton("⚡ تست واقعی همه", Modifier.weight(1f)) {
                        state.requestVpn?.invoke {
                            scope.launch {
                                cfgs.forEach { p -> if (p.startsWith("openvpn")) state.tcpTest(nodeId, p) else state.realTest(nodeId, p) }
                            }
                        }
                    }
                    IconCircle(48.dp, onClick = { scope.launch { state.refreshNode(nodeId); toast(ctx, "کانفیگ‌ها بروز شد") } }) { c -> refreshIcon(c) }
                }
                Spacer(Modifier.height(8.dp))
                cfgs.forEach { p ->
                    if (links.any { it.protocol == p }) {
                        ProtoCard(state, nodeId, p,
                            onSelect = {
                                if (locked) toast(ctx, "برای تغییر، اول اتصال را قطع کنید")
                                else {
                                    state.node = nodeId
                                    if (p.startsWith("openvpn")) {
                                        state.proto = "openvpn"; state.ovpnTransport = p.substringAfter("/")
                                    } else state.proto = p
                                    state.save()
                                }
                            },
                            onUdp = { state.requestVpn?.invoke { scope.launch { state.realTest(nodeId, p) } } },
                            onTcp = { scope.launch { state.tcpTest(nodeId, p) } },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun ServerGroup(
    flag: String, name: String, sub: String, selected: Boolean, expanded: Boolean,
    soon: Boolean = false, onHeader: () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(K.Panel.copy(alpha = .9f))
            .border(if (selected) 2.dp else 1.dp, if (selected) K.Gold else K.Line, shape),
    ) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onHeader).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            T(flag, size = 28.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                T(name, size = 17.sp, font = Poppins, weight = FontWeight.Bold, color = if (soon) K.Sub else K.Text, ltr = true)
                if (sub.isNotBlank()) T(sub, size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
            }
            when {
                soon -> Pill("به‌زودی", color = K.Sub)
                selected -> Pill("انتخاب شده", color = K.Green)
            }
            if (!soon) {
                Spacer(Modifier.width(8.dp))
                T(if (expanded) "▲" else "▼", size = 14.sp, color = K.Gold)
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), content = content)
        }
    }
}

@Composable
fun ProtoCard(state: AppState, nodeId: String, p: String, onSelect: () -> Unit, onUdp: () -> Unit, onTcp: () -> Unit) {
    val sel = state.node == nodeId && state.protoKey() == p
    val key = "$nodeId/$p"
    var details by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val isOvpn = p.startsWith("openvpn")

    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (sel) K.Gold.copy(alpha = 0.18f) else Color(0x12FFFFFF))
            .border(if (sel) 1.5.dp else 1.dp, if (sel) K.Gold else K.Line, shape)
            .clickable(onClick = onSelect).padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sel) { Dot(K.Green, 7.dp); Spacer(Modifier.width(6.dp)) }
            Column(Modifier.weight(1f)) {
                T(protoTitle(p), size = 13.sp, font = Poppins, weight = FontWeight.Bold, color = if (sel) K.Gold else K.Text, ltr = true)
                T(
                    when (p) {
                        "amneziawg" -> "عبور از فیلتر"
                        "amneziawg-direct" -> "اتصال مستقیم · بدون تونل"
                        "wireguard" -> "سریع · مناسب گیم"
                        "openvpn/tcp" -> "پایدار"
                        else -> "سازگاری بالا"
                    },
                    size = 9.sp, color = K.Sub, weight = FontWeight.Normal,
                )
            }
            if (!isOvpn) {
                ProtoBadge("UDP", state.udpPings[key], state.busyTests["udp:$key"] == true, onUdp)
                Spacer(Modifier.width(4.dp))
            }
            ProtoBadge("TCP", state.tcpPings[key], state.busyTests["tcp:$key"] == true, onTcp)
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (details) K.Gold.copy(alpha = .25f) else Color(0x14FFFFFF))
                    .border(1.dp, K.Line, RoundedCornerShape(10.dp))
                    .clickable { details = !details },
                contentAlignment = Alignment.Center,
            ) { T("✎", size = 13.sp, color = K.Gold) }
        }
        AnimatedVisibility(visible = details) {
            Column(Modifier.padding(top = 6.dp)) {
                if (isOvpn) {
                    T("تنظیمات OpenVPN در اپ OpenVPN انجام می‌شود", size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
                } else {
                    MtuEditor(state, p)
                    T("DNS از بخش تنظیمات تغییر می‌کند", size = 10.sp, color = K.Sub, weight = FontWeight.Normal, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
fun ToggleRow(icon: String, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        T(icon, size = 20.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(title, size = 15.sp)
            T(sub, size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = K.OnGold, checkedTrackColor = K.Gold,
                uncheckedThumbColor = K.Sub, uncheckedTrackColor = Color(0x33FFFFFF),
            ),
        )
    }
}

/** ربات شناور: با انگشت جابه‌جا می‌شود و خودش به نزدیک‌ترین گوشه می‌چسبد */
@Composable
fun FloatingRobot(onClick: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("kanki", Context.MODE_PRIVATE) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val sz = with(density) { 62.dp.toPx() }
        val m = with(density) { 8.dp.toPx() }
        val corners = listOf(
            androidx.compose.ui.geometry.Offset(m, m),
            androidx.compose.ui.geometry.Offset(wPx - sz - m, m),
            androidx.compose.ui.geometry.Offset(m, hPx - sz - m),
            androidx.compose.ui.geometry.Offset(wPx - sz - m, hPx - sz - m),
        )
        var corner by remember { mutableIntStateOf(prefs.getInt("robotCorner", 2).coerceIn(0, 3)) }
        val pos = remember { androidx.compose.animation.core.Animatable(corners[corner], androidx.compose.ui.geometry.Offset.VectorConverter) }
        var active by remember { mutableStateOf(false) }
        val alpha by androidx.compose.animation.core.animateFloatAsState(if (active) 1f else .5f, label = "ra")
        LaunchedEffect(wPx, hPx) { pos.snapTo(corners[corner]) }
        fun settle() {
            active = false
            val cur = pos.value
            val i = corners.indices.minBy { (corners[it] - cur).getDistance() }
            corner = i
            prefs.edit().putInt("robotCorner", i).apply()
            scope.launch { pos.animateTo(corners[i], androidx.compose.animation.core.spring(dampingRatio = .6f, stiffness = 300f)) }
        }
        Box(
            Modifier
                .absoluteOffset { androidx.compose.ui.unit.IntOffset(pos.value.x.toInt(), pos.value.y.toInt()) }
                .size(62.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { active = true },
                        onDragEnd = { settle() },
                        onDragCancel = { settle() },
                    ) { change, drag ->
                        change.consume()
                        scope.launch { pos.snapTo(pos.value + drag) }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { active = true; tryAwaitRelease(); active = false },
                        onTap = { onClick() },
                    )
                },
        ) { RobotFace(62.dp, alpha) }
    }
}

@Composable
fun MtuEditor(state: AppState, p: String) {
    var v by remember { mutableStateOf(state.mtuFor(p)) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        OutlinedTextField(
            value = v,
            onValueChange = { t -> v = t.filter { it.isDigit() }.take(4); state.setMtu(p, v) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { T("MTU دلخواه (خالی = پیش‌فرض)", size = 12.sp, color = K.Sub, weight = FontWeight.Normal) },
            textStyle = TextStyle(fontFamily = Poppins, fontSize = 14.sp, color = K.Text),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = K.Gold, unfocusedBorderColor = K.Line, cursorColor = K.Gold),
        )
    }
}

// ================================================================== تب تست

@Composable
fun TestTab(state: AppState) {
    val scope = rememberCoroutineScope()
    val a = state.account
    var running by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("") }
    var live by remember { mutableStateOf(0.0) }
    var down by remember { mutableStateOf<Double?>(null) }
    var up by remember { mutableStateOf<Double?>(null) }
    var lat by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var provider by remember { mutableStateOf("Cloudflare") }

    fun runSpeed() {
        if (running) return
        running = true; err = null; down = null; up = null; lat = null; live = 0.0
        scope.launch {
            try {
                provider = "Cloudflare · ${if (state.vstate == VState.ON) "از داخل تونل کانکی" else "بدون VPN"}"
                phase = "پینگ"
                lat = withContext(Dispatchers.IO) { RealPing.latency() }
                phase = "دانلود"
                down = withContext(Dispatchers.IO) { SpeedTest.downloadParallel { live = it } }
                live = 0.0
                phase = "آپلود"
                up = withContext(Dispatchers.IO) { SpeedTest.upload(4.0) { live = it } }
                phase = "تمام شد"
            } catch (e: Exception) {
                err = "تست ناموفق بود. اینترنت را بررسی کنید."
                phase = ""
            }
            running = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        SectionTitle("تست سرعت واقعی")
        Glass(Modifier.fillMaxWidth()) {
            T(
                provider,
                size = 12.sp, color = K.Sub, weight = FontWeight.Normal,
            )
            Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.BottomCenter) {
                val shown = if (running && (phase == "دانلود" || phase == "آپلود")) live else (down ?: 0.0)
                val gColor = if (phase == "آپلود") K.Green else K.Gold
                SpeedGauge(shown, gColor, Modifier.fillMaxSize())
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 4.dp)) {
                    T(String.format(java.util.Locale.US, "%.1f", shown), size = 36.sp, font = Poppins, weight = FontWeight.Bold, shadow = glow(gColor, 18f), ltr = true)
                    T(if (phase.isBlank()) "Mbps" else "$phase · Mbps", size = 12.sp, color = K.Gold)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row {
                Stat("↓ دانلود", down?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "–", Modifier.weight(1f))
                Stat("↑ آپلود", up?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "–", Modifier.weight(1f))
                Stat("پینگ", lat?.let { "${it.first} ms" } ?: "–", Modifier.weight(1f))
                Stat("جیتر", lat?.let { "${it.second} ms" } ?: "–", Modifier.weight(1f))
            }
            err?.let { Spacer(Modifier.height(8.dp)); T(it, size = 12.sp, color = K.Red) }
            Spacer(Modifier.height(12.dp))
            GoldButton(if (running) "در حال تست…" else "شروع تست سرعت", Modifier.fillMaxWidth(), loading = running) { runSpeed() }
            Spacer(Modifier.height(6.dp))
            T("این تست حدود ۱۵ تا ۴۰ مگابایت اینترنت مصرف می‌کند", size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
        }

        Spacer(Modifier.height(12.dp))
        T("برای دیدن پینگ هر کانفیگ، در تب «سرورها» سرور را باز کنید.", size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
        Spacer(Modifier.height(16.dp))
    }
}

// ================================================================== تب تنظیمات

@Composable
fun SettingsTab(state: AppState) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val a = state.account
    var open by remember { mutableStateOf<String?>(null) }
    fun flip(k: String) { open = if (open == k) null else k }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(6.dp))
        RobotMascot { openBot(ctx) }
        Spacer(Modifier.height(8.dp))

        Spacer(Modifier.height(12.dp))
        Glass(Modifier.fillMaxWidth()) {
            MenuRow("🌐", "DNS", dnsTitle(state.dns), open == "dns") { flip("dns") }
            AnimatedVisibility(visible = open == "dns") { DnsPicker(state) }
            GoldDivider()

            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                T("⚡", size = 20.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    T("اتصال خودکار", size = 15.sp)
                    T("با باز کردن اپ، خودکار وصل شود", size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
                }
                Switch(
                    checked = state.autoConnect,
                    onCheckedChange = { state.autoConnect = it; state.save() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = K.OnGold, checkedTrackColor = K.Gold,
                        uncheckedThumbColor = K.Sub, uncheckedTrackColor = Color(0x33FFFFFF),
                    ),
                )
            }
            GoldDivider()
            ToggleRow("🔊", "صداهای اپ", "صدای ورود و دکمه‌ها", state.sounds) { state.sounds = it; state.save() }
            GoldDivider()
            ThemePicker(state)
            GoldDivider()

            MenuRow("🛡", "کیل‌سوییچ و همیشه‌روشن", "جلوگیری از نشت اینترنت هنگام قطعی", false, arrow = "‹") {
                runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                toast(ctx, "Kanki VPN را انتخاب و «همیشه روشن» را فعال کنید")
            }
            GoldDivider()
            MenuRow("🛒", "خرید و تمدید اشتراک", "از طریق ربات تلگرام", false, arrow = "‹") { openBot(ctx) }
            GoldDivider()
            MenuRow("💬", "پشتیبانی", "@KANKI_VPN_ADMIN", false, arrow = "‹") { openSupport(ctx) }
            GoldDivider()
            MenuRow("📤", "معرفی به دوستان", "ارسال لینک ربات کانکی", false, arrow = "‹") {
                runCatching {
                    ctx.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, "کانکی وی‌پی‌ان 🔐 امنزیا، وایرگارد و اوپن‌وی‌پی‌ان با یک اشتراک\nتست رایگان: $BOT_URL"),
                            "معرفی کانکی",
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            GoldDivider()
            MenuRow("🚪", "خروج از حساب", "حذف اشتراک از این گوشی", false, arrow = "", danger = true) {
                scope.launch { state.logout() }
            }
        }

        Spacer(Modifier.height(14.dp))
        T("Kanki VPN · v$APP_VERSION", size = 12.sp, color = K.Sub, font = Poppins, ltr = true, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun GoldDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(K.Gold.copy(alpha = 0.12f)))
}

@Composable
fun MenuRow(
    icon: String, title: String, sub: String, expanded: Boolean,
    arrow: String? = null, danger: Boolean = false, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(icon, size = 20.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(title, size = 15.sp, color = if (danger) K.Red else K.Text)
            T(sub, size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
        }
        T(arrow ?: if (expanded) "▲" else "▼", size = 14.sp, color = K.Gold)
    }
}

@Composable
fun DnsPicker(state: AppState) {
    val keys = DnsOptions.list.map { it.first }
    var custom by remember { mutableStateOf(if (state.dns !in keys) state.dns else "") }
    var customMode by remember { mutableStateOf(state.dns !in keys) }
    Column(Modifier.padding(bottom = 6.dp)) {
        DnsOptions.list.forEach { (value, title) ->
            DnsRow(title, value.ifBlank { "از کانفیگ سرور" }, !customMode && state.dns == value) {
                customMode = false; state.dns = value; state.save()
            }
        }
        DnsRow("DNS دلخواه", "آدرس را خودتان وارد کنید", customMode) { customMode = true }
        if (customMode) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it; state.dns = it.trim(); state.save() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { T("1.1.1.1, 8.8.8.8", size = 14.sp, color = K.Sub, font = Poppins, ltr = true) },
                    textStyle = TextStyle(fontFamily = Poppins, fontSize = 14.sp, color = K.Text),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = K.Gold, unfocusedBorderColor = K.Line, cursorColor = K.Gold),
                )
            }
        }
        T("تغییر DNS از اتصال بعدی اعمال می‌شود", size = 11.sp, color = K.Sub, weight = FontWeight.Normal, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun DnsRow(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected, onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = K.Gold, unselectedColor = K.Sub),
        )
        Column {
            T(title, size = 14.sp)
            val latin = sub.first().isDigit()
            T(sub, size = 12.sp, color = K.Sub, weight = FontWeight.Normal, font = if (latin) Poppins else Vazir, ltr = latin)
        }
    }
}


/** دکمه‌ی تغییر زبان کنار آیکون حساب */
@Composable
fun LangButton(state: AppState) {
    val scope = rememberCoroutineScope()
    Box(
        Modifier.size(42.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(Brush.verticalGradient(listOf(K.Surface.copy(alpha = 1f), K.Panel)))
            .border(1.2.dp, K.Gold.copy(alpha = .7f), androidx.compose.foundation.shape.CircleShape)
            .clickable {
                state.setLang(if (LangState.en) "fa" else "en")
                scope.launch { state.refresh() }
            },
        contentAlignment = Alignment.Center,
    ) { T(if (LangState.en) "FA" else "EN", size = 13.sp, color = K.Gold, font = Poppins, weight = FontWeight.Bold, ltr = true) }
}

/** انتخاب تم: طلایی، سیاه و سفید، زرشکی و آبی */
@Composable
fun ThemePicker(state: AppState) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("🎨", size = 20.sp)
            Spacer(Modifier.width(12.dp))
            T("تم برنامه", size = 15.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("gold" to "طلایی", "mono" to "سیاه و سفید", "crimson" to "زرشکی و آبی").forEach { (id, name) ->
                val p = Themes.first { it.id == id }
                val sel = ThemeState.current.id == id
                val shape = RoundedCornerShape(16.dp)
                Column(
                    Modifier.weight(1f).clip(shape)
                        .background(Brush.verticalGradient(listOf(p.bgMid, p.bgBottom)))
                        .border(if (sel) 2.dp else 1.dp, if (sel) p.gold else p.gold.copy(alpha = .3f), shape)
                        .clickable { state.setTheme(id) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(14.dp).clip(androidx.compose.foundation.shape.CircleShape).background(p.gold))
                        Box(Modifier.size(14.dp).clip(androidx.compose.foundation.shape.CircleShape).background(p.accent))
                    }
                    Spacer(Modifier.height(6.dp))
                    T(name, size = 11.sp, color = if (sel) p.gold else p.sub)
                }
            }
        }
    }
}
