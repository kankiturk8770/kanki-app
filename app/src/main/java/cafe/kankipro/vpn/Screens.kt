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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun openBot(ctx: Context) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BOT_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun faStatus(s: String) = when (s.lowercase()) {
    "active" -> "فعال"
    "expired" -> "منقضی شده"
    "disabled", "inactive" -> "غیرفعال"
    "limited" -> "حجم تمام شده"
    "frozen", "suspended" -> "متوقف"
    else -> s
}

fun flagOf(name: String): String {
    val n = name.uppercase()
    return when {
        "GERMAN" in n || "DE-" in n -> "🇩🇪"
        "TURK" in n || "TR-" in n -> "🇹🇷"
        "NETHER" in n || "NL" in n -> "🇳🇱"
        "FINLAND" in n || "FI-" in n -> "🇫🇮"
        "FRANCE" in n -> "🇫🇷"
        "UK" in n || "LONDON" in n -> "🇬🇧"
        "US" in n || "AMERICA" in n -> "🇺🇸"
        else -> "🌐"
    }
}

fun protoTitle(p: String) = if (p == "amneziawg") "AmneziaWG" else "WireGuard"

// ================================================================== ورود

@Composable
fun LoginScreen(state: AppState) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(30.dp))
        ShieldLogo(120.dp)
        Spacer(Modifier.height(18.dp))
        BrandTitle(34.sp)
        T("Secure · Fast · Low Ping", size = 13.sp, color = K.Sub, font = Poppins, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(40.dp))

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
                    placeholder = { T("bub-1-xxxxxxxx", size = 14.sp, color = K.Sub.copy(alpha = .5f), font = Poppins, weight = FontWeight.SemiBold) },
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
                LineButton("جای‌گذاری 📋", Modifier.weight(1f)) {
                    clip.getText()?.text?.let { text = it.trim() }
                }
            }
        }

        Spacer(Modifier.height(26.dp))
        T("اشتراک ندارید؟", size = 13.sp, color = K.Sub, weight = FontWeight.Normal)
        Spacer(Modifier.height(8.dp))
        Pill("🤖  خرید اشتراک و تست رایگان از ربات", onClick = { openBot(ctx) })
    }
}

// ================================================================== صفحه‌ی اصلی

enum class KTab(val title: String, val icon: String) { HOME("خانه", "⌂"), SERVERS("سرورها", "☰"), SETTINGS("تنظیمات", "⚙") }

@Composable
fun MainScreen(state: AppState, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(KTab.HOME) }

    Column(Modifier.fillMaxSize()) {
        // سربرگ
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ShieldLogo(36.dp)
            Spacer(Modifier.width(10.dp))
            BrandTitle(22.sp)
            Spacer(Modifier.weight(1f))
            Pill("🤖 ربات", onClick = { openBot(ctx) })
        }

        Box(Modifier.weight(1f)) {
            when (tab) {
                KTab.HOME -> HomeTab(state, onToggle)
                KTab.SERVERS -> ServersTab(state)
                KTab.SETTINGS -> SettingsTab(state)
            }
        }

        BottomBar(tab) { tab = it }
    }
}

@Composable
fun BottomBar(current: KTab, onSelect: (KTab) -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    Row(
        Modifier.fillMaxWidth().padding(16.dp).clip(shape)
            .background(Color(0xF00E0B07)).border(1.dp, K.Line, shape).padding(6.dp),
    ) {
        KTab.entries.forEach { t ->
            val sel = t == current
            val s = RoundedCornerShape(24.dp)
            Column(
                Modifier.weight(1f).clip(s)
                    .background(if (sel) Brush.verticalGradient(listOf(Color(0x66F5C451), Color(0x22F5C451))) else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable { onSelect(t) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                T(t.icon, size = 20.sp, color = if (sel) K.Gold else K.Sub)
                T(t.title, size = 13.sp, color = if (sel) K.Gold else K.Sub)
            }
        }
    }
}

// ------------------------------------------------------------------ تب خانه

@Composable
fun HomeTab(state: AppState, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val a = state.account

    // زمان‌سنج و سرعت
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
                if (last != null) { rxSpeed = s.first - last.first; txSpeed = s.second - last.second }
                session = s.first + s.second
                last = s
            }
            if (++tick % 15 == 0) state.measurePing()
            delay(1000)
        }
        rxSpeed = 0; txSpeed = 0
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(4.dp))
        ConnectButton(state.vstate) { if (a != null) onToggle() }

        val (label, color) = when (state.vstate) {
            VState.ON -> "متصل" to K.Green
            VState.CONNECTING -> "در حال اتصال…" to K.Gold
            VState.OFF -> "آماده‌ی اتصال" to K.Text
        }
        T(label, size = 30.sp, color = color, weight = FontWeight.Black, shadow = glow(color))

        if (state.vstate == VState.ON && VpnEngine.connectedAt > 0) {
            val sec = ((now - VpnEngine.connectedAt) / 1000).coerceAtLeast(0)
            T(
                String.format(java.util.Locale.US, "%02d : %02d : %02d", sec / 3600, sec / 60 % 60, sec % 60),
                size = 40.sp, font = Poppins, weight = FontWeight.Bold, shadow = glow(K.Gold, 16f),
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(protoTitle(state.proto))
            state.ping?.let { Pill("پینگ ${it}ms", color = K.Green) }
        }

        state.error?.let {
            Spacer(Modifier.height(10.dp))
            T(it, size = 13.sp, color = K.Red)
        }

        if (a == null) {
            Spacer(Modifier.height(24.dp))
            if (state.loading) CircularProgressIndicator(color = K.Gold)
            else GoldButton("تلاش دوباره", Modifier.fillMaxWidth()) { scope.launch { state.refresh() } }
        } else {
            AccountCards(state, a, rxSpeed, txSpeed, session)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun AccountCards(state: AppState, a: Account, rxSpeed: Long, txSpeed: Long, session: Long) {
        // کارت سرور و اشتراک
        Spacer(Modifier.height(18.dp))
        val nodeName = a.configs.firstOrNull { it.node == state.node }?.nodeName ?: "—"
        val info = state.infos["${state.node}/${state.proto}"]
        Glass(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(flagOf(nodeName), size = 30.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    T(nodeName, size = 18.sp, font = Poppins, weight = FontWeight.Bold)
                    T("${protoTitle(state.proto)} · ${info?.endpoint ?: "…"}", size = 12.sp, color = K.Sub, font = Poppins, weight = FontWeight.SemiBold)
                }
                Pill(faStatus(a.status), color = if (a.status.equals("Active", true)) K.Green else K.Red)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("⏳ ${a.timeRemaining}")
                Pill("📦 ${a.dataRemaining} مانده")
            }
            val used = parseSize(a.used)
            val left = parseSize(a.dataRemaining)
            if (used != null && left != null && used + left > 0) {
                Spacer(Modifier.height(14.dp))
                val f = (used / (used + left)).toFloat().coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x33FFFFFF))) {
                    Box(
                        Modifier.fillMaxWidth(f.coerceAtLeast(0.02f)).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                            .background(Brush.horizontalGradient(listOf(K.GoldDeep, K.Gold, K.GoldLight))),
                    )
                }
                Spacer(Modifier.height(8.dp))
                T("مصرف‌شده: ${a.used}", size = 13.sp, color = K.Sub)
            }
        }

        // کارت ترافیک
        Spacer(Modifier.height(14.dp))
        Glass(Modifier.fillMaxWidth()) {
            Row {
                Stat("↓ دانلود", "${formatBytes(rxSpeed)}/s", Modifier.weight(1f))
                Stat("↑ آپلود", "${formatBytes(txSpeed)}/s", Modifier.weight(1f))
                Stat("این نشست", formatBytes(session), Modifier.weight(1f))
            }
        }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        T(label, size = 12.sp, color = K.Sub)
        Spacer(Modifier.height(4.dp))
        T(value, size = 15.sp, font = Poppins, weight = FontWeight.Bold)
    }
}

// ------------------------------------------------------------------ تب سرورها

@Composable
fun ServersTab(state: AppState) {
    val ctx = LocalContext.current
    val a = state.account
    val locked = state.vstate != VState.OFF
    fun blocked() = Toast.makeText(ctx, "برای تغییر، اول اتصال را قطع کنید", Toast.LENGTH_SHORT).show()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        SectionTitle("انتخاب سرور")
        val nodes = a?.configs?.distinctBy { it.node } ?: emptyList()
        if (nodes.isEmpty()) T("سروری پیدا نشد", color = K.Sub)
        nodes.forEach { n ->
            val sel = n.node == state.node
            SelectCard(sel, onClick = { if (locked) blocked() else { state.node = n.node; state.save() } }) {
                T(flagOf(n.nodeName), size = 28.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    T(n.nodeName, size = 17.sp, font = Poppins, weight = FontWeight.Bold)
                    T(if (n.node == "local") "سرور اصلی" else n.node, size = 12.sp, color = K.Sub)
                }
                if (sel) Pill("انتخاب شده", color = K.Green)
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(4.dp))
        Pill("🇹🇷 سرور ترکیه · به‌زودی", color = K.Sub)

        SectionTitle("پروتکل و اطلاعات کانفیگ")
        listOf("amneziawg", "wireguard").forEach { p ->
            val sel = state.proto == p
            val info = state.infos["${state.node}/$p"]
            SelectCard(sel, onClick = { if (locked) blocked() else { state.proto = p; state.save() } }) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(protoTitle(p), size = 19.sp, font = Poppins, weight = FontWeight.Bold, color = if (sel) K.Gold else K.Text)
                        Spacer(Modifier.weight(1f))
                        if (sel) Pill("فعال", color = K.Green)
                    }
                    T(
                        if (p == "amneziawg") "مخفی‌سازی ترافیک · مناسب برای عبور از فیلتر" else "سریع و سبک · مناسب برای گیم",
                        size = 12.sp, color = K.Sub, weight = FontWeight.Normal,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (info == null) T("در حال دریافت اطلاعات…", size = 13.sp, color = K.Sub)
                    else {
                        InfoRow("سرور", info.endpoint)
                        InfoRow("آدرس داخلی", info.address)
                        InfoRow("DNS کانفیگ", info.dns)
                        InfoRow("MTU", info.mtu)
                        info.obfuscation?.let { InfoRow("مبهم‌سازی", it, K.Green) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        SelectCard(false, onClick = { Toast.makeText(ctx, "OpenVPN در نسخه‌ی بعدی اضافه می‌شود", Toast.LENGTH_SHORT).show() }) {
            T("OpenVPN", size = 19.sp, font = Poppins, weight = FontWeight.Bold, color = K.Sub)
            Spacer(Modifier.weight(1f))
            Pill("به‌زودی", color = K.Sub)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun SelectCard(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) Color(0x33F5C451) else Color(0xCC0E0B07))
            .border(if (selected) 2.dp else 1.dp, if (selected) K.Gold else K.Line, shape)
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

// ------------------------------------------------------------------ تب تنظیمات

@Composable
fun SettingsTab(state: AppState) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val a = state.account
    val keys = DnsOptions.list.map { it.first }
    var custom by remember { mutableStateOf(if (state.dns !in keys) state.dns else "") }
    var customMode by remember { mutableStateOf(state.dns !in keys) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        SectionTitle("تنظیم DNS")
        Glass(Modifier.fillMaxWidth()) {
            DnsOptions.list.forEach { (value, title) ->
                DnsRow(title, value.ifBlank { "از کانفیگ سرور" }, !customMode && state.dns == value) {
                    customMode = false; state.dns = value; state.save()
                }
            }
            DnsRow("DNS دلخواه", "آدرس را خودتان وارد کنید", customMode) { customMode = true }
            if (customMode) {
                Spacer(Modifier.height(8.dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it; state.dns = it.trim(); state.save() },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { T("1.1.1.1, 8.8.8.8", size = 14.sp, color = K.Sub, font = Poppins) },
                        textStyle = TextStyle(fontFamily = Poppins, fontSize = 14.sp, color = K.Text),
                        shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = K.Gold, unfocusedBorderColor = K.Line, cursorColor = K.Gold),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            T("تغییر DNS از اتصال بعدی اعمال می‌شود", size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
        }

        SectionTitle("اتصال")
        Glass(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("اتصال خودکار", size = 16.sp)
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
        }

        SectionTitle("حساب کاربری")
        Glass(Modifier.fillMaxWidth()) {
            if (a != null) {
                InfoRow("نام کاربری", a.username)
                InfoRow("وضعیت", faStatus(a.status), if (a.status.equals("Active", true)) K.Green else K.Red)
                InfoRow("حجم باقی‌مانده", a.dataRemaining)
                InfoRow("مصرف‌شده", a.used)
                InfoRow("زمان باقی‌مانده", a.timeRemaining)
                InfoRow("تاریخ انقضا", if (a.expiry == "—") "بدون محدودیت" else a.expiry)
                InfoRow("اتصال همزمان", a.connections)
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LineButton("بروزرسانی", Modifier.weight(1f)) { scope.launch { state.refresh() } }
                LineButton("کپی لینک", Modifier.weight(1f)) {
                    clip.setText(AnnotatedString(state.subLink))
                    Toast.makeText(ctx, "لینک اشتراک کپی شد", Toast.LENGTH_SHORT).show()
                }
            }
            Spacer(Modifier.height(10.dp))
            GoldButton("🤖 تمدید و خرید اشتراک از ربات", Modifier.fillMaxWidth()) { openBot(ctx) }
            Spacer(Modifier.height(10.dp))
            LineButton("خروج از حساب", Modifier.fillMaxWidth(), color = K.Red) { scope.launch { state.logout() } }
        }

        Spacer(Modifier.height(18.dp))
        T("Kanki VPN · v0.2.1", size = 12.sp, color = K.Sub, font = Poppins, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun DnsRow(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected, onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = K.Gold, unselectedColor = K.Sub),
        )
        Column {
            T(title, size = 15.sp)
            T(sub, size = 12.sp, color = K.Sub, weight = FontWeight.Normal, font = if (sub.first().isDigit()) Poppins else Vazir)
        }
    }
}
