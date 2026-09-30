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

const val APP_VERSION = "0.5.0"

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun openBot(ctx: Context) = openUrl(ctx, BOT_URL)
fun openSupport(ctx: Context) = openUrl(ctx, SUPPORT_URL)

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

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
    "wireguard" -> "WireGuard"
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
        Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                Logo3D(38.dp)
                Spacer(Modifier.width(8.dp))
                BrandTitle(20.sp)
            }
            Box(Modifier.align(Alignment.CenterEnd)) {
                IconCircle(40.dp, onClick = { state.accountOpen = true }) { c -> accountIcon(c) }
            }
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
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).clip(shape)
            .background(Color(0xF00E0B07)).border(1.dp, K.Line, shape).padding(5.dp),
    ) {
        KTab.entries.forEach { t ->
            val sel = t == current
            val s = RoundedCornerShape(22.dp)
            Column(
                Modifier.weight(1f).clip(s)
                    .background(if (sel) Color(0x55F5C451) else Color.Transparent)
                    .clickable { onSelect(t) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                T(t.icon, size = 19.sp, color = if (sel) K.Gold else K.Sub)
                T(t.title, size = 12.sp, color = if (sel) K.Gold else K.Sub)
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
    var session by remember { mutableLongStateOf(0L) }
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
                session = s.first + s.second
                last = s
            }
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
        ConnectButton(state.vstate) { if (a != null) onToggle() }

        val (label, color) = when (state.vstate) {
            VState.ON -> "متصل" to K.Green
            VState.CONNECTING -> "در حال اتصال…" to K.Gold
            VState.OFF -> "آماده‌ی اتصال" to K.Text
        }
        T(label, size = 28.sp, color = color, weight = FontWeight.Black, shadow = glow(color, 30f))

        if (state.vstate == VState.ON && VpnEngine.connectedAt > 0) {
            val sec = ((now - VpnEngine.connectedAt) / 1000).coerceAtLeast(0)
            T(
                String.format(java.util.Locale.US, "%02d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60),
                size = 34.sp, font = Poppins, weight = FontWeight.Bold, shadow = glow(K.Green, 20f), ltr = true,
            )
        }

        state.ping?.let {
            Spacer(Modifier.height(4.dp))
            Pill("پینگ ${it}ms", color = pingColor(it))
        }

        // سوییچ سریع پروتکل
        Spacer(Modifier.height(10.dp))
        var ask by remember { mutableStateOf<String?>(null) }
        ProtocolSwitcher(state.proto) { p ->
            if (p == state.proto) return@ProtocolSwitcher
            if (state.vstate == VState.CONNECTING) return@ProtocolSwitcher
            if (state.vstate == VState.ON || p == "openvpn") ask = p
            else { state.proto = p; state.save() }
        }
        ask?.let { target ->
            AlertDialog(
                onDismissRequest = { ask = null },
                containerColor = Color(0xFF16120B),
                title = { T("سوییچ به ${protoTitle(target)}", size = 18.sp, weight = FontWeight.Black) },
                text = {
                    T(
                        if (target == "openvpn") "پروفایل OpenVPN (${state.ovpnTransport.uppercase()}) در اپ OpenVPN باز می‌شود. اتصال فعلی قطع می‌شود."
                        else "اتصال فعلی قطع و با ${protoTitle(target)} دوباره وصل می‌شود.",
                        size = 14.sp, color = K.Sub, weight = FontWeight.Normal,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { ask = null; scope.launch { state.switchTo(target) } }) {
                        T("تأیید و اتصال", color = K.Gold)
                    }
                },
                dismissButton = { TextButton(onClick = { ask = null }) { T("انصراف", color = K.Sub) } },
            )
        }

        state.error?.let {
            Spacer(Modifier.height(8.dp))
            T(it, size = 13.sp, color = K.Red)
        }

        Spacer(Modifier.height(14.dp))
        if (a == null) {
            if (state.loading) CircularProgressIndicator(color = K.Gold)
            else GoldButton("تلاش دوباره", Modifier.fillMaxWidth()) { scope.launch { state.refresh() } }
        } else {
            HomeCard(state, a, rxSpeed, txSpeed, session)
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** یک کارت جمع‌وجور: سرور + اشتراک + ترافیک */
@Composable
fun HomeCard(state: AppState, a: Account, rxSpeed: Long, txSpeed: Long, session: Long) {
    val nodeName = a.configs.firstOrNull { it.node == state.node }?.nodeName ?: "—"
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
            val scope = rememberCoroutineScope()
            IconCircle(34.dp, onClick = { scope.launch { state.refresh(); toast(ctx, "اطلاعات اشتراک بروز شد") } }) { c -> refreshIcon(c) }
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

        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(K.Line))
        Spacer(Modifier.height(12.dp))
        Row {
            Stat("↓ دانلود", "${formatBytes(rxSpeed)}/s", Modifier.weight(1f))
            Stat("↑ آپلود", "${formatBytes(txSpeed)}/s", Modifier.weight(1f))
            Stat("این نشست", formatBytes(session), Modifier.weight(1f))
        }
    }
}

@Composable
fun MiniStat(label: String, value: String, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(Color(0x22F5C451)).border(1.dp, K.Line, shape).padding(horizontal = 10.dp, vertical = 6.dp)) {
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
fun ProtocolSwitcher(current: String, onPick: (String) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color(0xCC0E0B07)).border(1.dp, K.Line, shape).padding(4.dp),
    ) {
        listOf("amneziawg" to "Amnezia", "wireguard" to "WireGuard", "openvpn" to "OpenVPN").forEach { (p, title) ->
            val sel = p == current
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .background(if (sel) Brush.verticalGradient(listOf(Color(0xFFFFE08A), K.Gold, K.GoldDeep)) else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable { onPick(p) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                T(title, size = 13.sp, font = Poppins, weight = FontWeight.Bold, color = if (sel) Color(0xFF1A1206) else K.Sub, ltr = true)
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
        SectionTitle("سرورها")
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
                val cfgs = listOf("amneziawg", "wireguard", "openvpn/udp", "openvpn/tcp").filter { p -> links.any { it.protocol == p } }
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
                T("UDP = پینگ واقعی از داخل تونل (مثل v2rayNG) · TCP = دست‌دادن مستقیم با سرور", size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
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
                        Spacer(Modifier.height(8.dp))
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
            .background(Color(0xE60E0B07))
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
    val activeKey = state.protoKey()
    val sel = state.node == nodeId && activeKey == p
    val key = "$nodeId/$p"
    val info = state.infos[key]
    var details by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val isOvpn = p.startsWith("openvpn")

    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (sel) Color(0x33F5C451) else Color(0x14FFFFFF))
            .border(if (sel) 1.5.dp else 1.dp, if (sel) K.Gold else K.Line, shape)
            .clickable(onClick = onSelect).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(protoTitle(p), size = 16.sp, font = Poppins, weight = FontWeight.Bold, color = if (sel) K.Gold else K.Text, ltr = true)
                T(
                    when {
                        p == "amneziawg" -> "مخفی‌سازی ترافیک · عبور از فیلتر"
                        p == "wireguard" -> "سریع و سبک · مناسب گیم"
                        p == "openvpn/tcp" -> "پایدار روی شبکه‌های محدود"
                        else -> "سازگاری بالا · اجرا در اپ OpenVPN"
                    },
                    size = 11.sp, color = K.Sub, weight = FontWeight.Normal,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!isOvpn) {
                ProtoBadge("UDP", state.udpPings[key], state.busyTests["udp:$key"] == true, onUdp)
                Spacer(Modifier.width(6.dp))
            }
            ProtoBadge("TCP", state.tcpPings[key], state.busyTests["tcp:$key"] == true, onTcp)
            Spacer(Modifier.weight(1f))
            if (sel) { Pill("✓ فعال", color = K.Green); Spacer(Modifier.width(6.dp)) }
            // دکمه‌ی مداد برای نمایش جزئیات
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (details) K.Gold.copy(alpha = .25f) else Color(0x14FFFFFF))
                    .border(1.dp, K.Line, RoundedCornerShape(12.dp))
                    .clickable { details = !details },
                contentAlignment = Alignment.Center,
            ) { T("✎", size = 16.sp, color = K.Gold) }
        }
        AnimatedVisibility(visible = details) {
            Column(Modifier.padding(top = 10.dp)) {
                GoldDivider()
                Spacer(Modifier.height(6.dp))
                if (info == null) {
                    T("در حال دریافت اطلاعات…", size = 12.sp, color = K.Sub)
                } else {
                    InfoRow("سرور", info.host)
                    InfoRow("پورت", if (info.port > 0) info.port.toString() else "—")
                    InfoRow("پروتکل انتقال", info.transport)
                    InfoRow("آدرس داخلی", info.address)
                    InfoRow("DNS", info.dns)
                    InfoRow("MTU", info.mtu)
                    info.obfuscation?.let { InfoRow("مبهم‌سازی", it, K.Green) }
                    info.cipher?.let { InfoRow("رمزنگاری", it) }
                    if (!isOvpn) {
                        Spacer(Modifier.height(8.dp))
                        MtuEditor(state, p)
                    }
                }
            }
        }
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
    var provider by remember { mutableStateOf("Speedtest by Ookla") }

    fun runSpeed() {
        if (running) return
        running = true; err = null; down = null; up = null; lat = null; live = 0.0
        scope.launch {
            try {
                phase = "یافتن سرور"
                val picked = withContext(Dispatchers.IO) { Ookla.pickServer() }
                if (picked != null) {
                    val srv = picked.first
                    provider = "Speedtest by Ookla · ${srv.sponsor} (${srv.name})"
                    phase = "پینگ"
                    lat = withContext(Dispatchers.IO) { (Ookla.latency(srv) ?: picked.second) to (Ookla.jitter(srv) ?: 0L) }
                    phase = "دانلود"
                    down = withContext(Dispatchers.IO) { Ookla.download(srv) { live = it } }
                    live = 0.0
                    phase = "آپلود"
                    up = withContext(Dispatchers.IO) { Ookla.upload(srv) { live = it } }
                } else {
                    provider = "Cloudflare (سرور Ookla در دسترس نبود)"
                    phase = "پینگ"
                    lat = withContext(Dispatchers.IO) { SpeedTest.latency() }
                    phase = "دانلود"
                    down = withContext(Dispatchers.IO) { SpeedTest.download { live = it } }
                    live = 0.0
                    phase = "آپلود"
                    up = withContext(Dispatchers.IO) { SpeedTest.upload { live = it } }
                }
                phase = "تمام شد"
            } catch (e: Exception) {
                err = "تست ناموفق بود: " + (e.message ?: e.javaClass.simpleName)
                phase = ""
            }
            running = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        SectionTitle("تست سرعت واقعی")
        Glass(Modifier.fillMaxWidth()) {
            T(
                (if (state.vstate == VState.ON) "از مسیر VPN کانکی · " else "بدون VPN · ") + provider,
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
            T("حدود ۲۰ تا ۶۰ مگابایت اینترنت مصرف می‌کند", size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
        }

        Spacer(Modifier.height(12.dp))
        T("پینگ واقعی هر کانفیگ (UDP و TCP) در تب «سرورها» با باز کردن هر سرور", size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
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

        // خلاصه‌ی حساب
        Glass(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T(a?.username ?: "—", size = 18.sp, font = Poppins, weight = FontWeight.Bold, ltr = true)
                    T("حساب کاربری", size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
                }
                if (a != null) Pill(faStatus(a.status), color = if (isActive(a.status)) K.Green else K.Red)
            }
            if (a != null) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniStat("حجم باقی‌مانده", a.dataRemaining, Modifier.weight(1f))
                    MiniStat("زمان باقی‌مانده", a.timeRemaining, Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Glass(Modifier.fillMaxWidth()) {
            MenuRow("👤", "جزئیات حساب", "مصرف، انقضا، اتصال همزمان", open == "acc") { flip("acc") }
            AnimatedVisibility(visible = open == "acc") {
                Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    if (a != null) {
                        InfoRow("مصرف‌شده", a.used)
                        InfoRow("تاریخ انقضا", if (a.expiry == "—") "بدون محدودیت" else a.expiry)
                        InfoRow("اتصال همزمان", a.connections)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LineButton("بروزرسانی", Modifier.weight(1f)) { scope.launch { state.refresh(); toast(ctx, "اطلاعات بروز شد") } }
                        LineButton("کپی لینک", Modifier.weight(1f)) {
                            clip.setText(AnnotatedString(state.subLink))
                            toast(ctx, "لینک اشتراک کپی شد")
                        }
                    }
                }
            }
            GoldDivider()

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
                        checkedThumbColor = Color(0xFF1A1206), checkedTrackColor = K.Gold,
                        uncheckedThumbColor = K.Sub, uncheckedTrackColor = Color(0x33FFFFFF),
                    ),
                )
            }
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
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x1FF5C451)))
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
