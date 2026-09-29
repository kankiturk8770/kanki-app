package cafe.kankipro.vpn

import android.app.Activity
import android.content.Context
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// رنگ‌های طلایی کانکی
val Gold = Color(0xFFFFD36B)
val GoldDark = Color(0xFFC98B1C)
val Cream = Color(0xFFFFF1C9)
val Bg1 = Color(0xFF2A1F0C)
val Bg2 = Color(0xFF0E0A04)
val CardBg = Color(0x1FFFE6B0)
val CardLine = Color(0x55FFD36B)
val Green = Color(0xFF4DFFA6)
val Red = Color(0xFFFF6B6B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(
                    colorScheme = darkColorScheme(primary = Gold, onPrimary = Bg2, background = Bg2, surface = Bg1)
                ) { App() }
            }
        }
    }
}

enum class VState { OFF, CONNECTING, ON }

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("kanki", Context.MODE_PRIVATE) }
    var saved by remember {
        mutableStateOf(prefs.getString("code", null)?.let { c ->
            (prefs.getString("base", null) ?: SubscriptionApi.DEFAULT_HOST) to c
        })
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Bg1, Bg2)))
            .systemBarsPadding()
    ) {
        val s = saved
        if (s == null) {
            LoginScreen { base, code ->
                prefs.edit().putString("base", base).putString("code", code).apply()
                saved = base to code
            }
        } else {
            HomeScreen(s.first, s.second) {
                VpnEngine.down(ctx)
                prefs.edit().clear().apply()
                saved = null
            }
        }
    }
}

// ---------------------------------------------------------------- ورود

@Composable
fun LoginScreen(onLogin: (String, String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Logo()
        Spacer(Modifier.height(40.dp))
        Text("لینک یا کد اشتراک خود را وارد کنید", color = Cream, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "همان لینکی که بعد از خرید از ربات دریافت کردید",
            color = Cream.copy(alpha = .6f), fontSize = 13.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("https://…/sub/bub-…", color = Cream.copy(alpha = .35f)) },
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Gold, unfocusedBorderColor = CardLine,
                focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Gold
            )
        )
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Red, fontSize = 13.sp)
        }
        Spacer(Modifier.height(22.dp))
        GoldButton("ورود", Modifier.fillMaxWidth()) {
            val r = SubscriptionApi.normalize(text)
            if (r == null) error = "لینک یا کد اشتراک معتبر نیست"
            else onLogin(r.first, r.second)
        }
    }
}

// ---------------------------------------------------------------- صفحه‌ی اصلی

@Composable
fun HomeScreen(base: String, code: String, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var account by remember { mutableStateOf<Account?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var vstate by remember { mutableStateOf(if (VpnEngine.isUp(ctx)) VState.ON else VState.OFF) }
    var proto by remember { mutableStateOf("amneziawg") }
    var node by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            loading = true; error = null
            try {
                val a = withContext(Dispatchers.IO) { SubscriptionApi.load(base, code) }
                account = a
                if (node == null) node = a.configs.firstOrNull()?.node
            } catch (e: Exception) {
                error = "دریافت اطلاعات اشتراک ناموفق بود. اینترنت را بررسی کنید."
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    fun connectNow() {
        val a = account ?: return
        val link = a.configs.firstOrNull { it.protocol == proto && it.node == node }
            ?: a.configs.firstOrNull { it.protocol == proto }
        if (link == null) { error = "این پروتکل برای اشتراک شما فعال نیست."; return }
        vstate = VState.CONNECTING; error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val cfg = SubscriptionApi.fetch(link.url)
                    VpnEngine.up(ctx, cfg)
                }
                vstate = VState.ON
            } catch (e: Exception) {
                vstate = VState.OFF
                error = "اتصال برقرار نشد: " + (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) connectNow()
        else error = "برای اتصال باید اجازه‌ی VPN را تأیید کنید."
    }

    fun toggle() {
        when (vstate) {
            VState.ON -> scope.launch {
                withContext(Dispatchers.IO) { VpnEngine.down(ctx) }
                vstate = VState.OFF
            }
            VState.OFF -> {
                val intent = VpnService.prepare(ctx)
                if (intent != null) permission.launch(intent) else connectNow()
            }
            VState.CONNECTING -> {}
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // سربرگ
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("KANKI VPN", color = Gold, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.weight(1f))
            SmallChip("بروزرسانی") { load() }
            Spacer(Modifier.width(8.dp))
            SmallChip("خروج") { onLogout() }
        }

        Spacer(Modifier.height(18.dp))
        val a = account
        if (a != null) {
            Text(a.username, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            StatusPill(a.status)
        } else if (loading) {
            CircularProgressIndicator(color = Gold)
        }

        Spacer(Modifier.height(30.dp))
        ConnectButton(vstate) { if (account != null) toggle() }
        Spacer(Modifier.height(14.dp))
        Text(
            when (vstate) {
                VState.ON -> "متصل هستید"
                VState.CONNECTING -> "در حال اتصال…"
                VState.OFF -> "قطع"
            },
            color = if (vstate == VState.ON) Green else Cream, fontSize = 18.sp, fontWeight = FontWeight.Bold
        )

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Red, fontSize = 13.sp, textAlign = TextAlign.Center)
        }

        if (a != null) {
            Spacer(Modifier.height(26.dp))
            SectionTitle("پروتکل")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Choice("امنزیا", proto == "amneziawg", vstate == VState.OFF, Modifier.weight(1f)) { proto = "amneziawg" }
                Choice("وایرگارد", proto == "wireguard", vstate == VState.OFF, Modifier.weight(1f)) { proto = "wireguard" }
            }

            val nodes = a.configs.distinctBy { it.node }
            if (nodes.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                SectionTitle("سرور")
                nodes.forEach { n ->
                    Choice(n.nodeName, node == n.node, vstate == VState.OFF, Modifier.fillMaxWidth()) { node = n.node }
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("اطلاعات اشتراک")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoCard("حجم باقی‌مانده", a.dataRemaining, Modifier.weight(1f))
                InfoCard("مصرف‌شده", a.used, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoCard("زمان باقی‌مانده", a.timeRemaining, Modifier.weight(1f))
                InfoCard("اتصال همزمان", a.connections, Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(30.dp))
        Text("Kanki VPN v0.1", color = Cream.copy(alpha = .4f), fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- اجزای ظاهری

@Composable
fun Logo() {
    Box(
        Modifier.size(110.dp).clip(RoundedCornerShape(32.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFFFE596), Gold, GoldDark))),
        contentAlignment = Alignment.Center
    ) { Text("K", color = Bg2, fontSize = 60.sp, fontWeight = FontWeight.Black) }
    Spacer(Modifier.height(16.dp))
    Text("KANKI VPN", color = Gold, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
    Text("کانکی وی‌پی‌ان", color = Cream, fontSize = 16.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun ConnectButton(state: VState, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "pulse")
    val pulse by t.animateFloat(
        1f, 1.08f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "p"
    )
    val ring = when (state) {
        VState.ON -> listOf(Green, Color(0xFF1FAF6E))
        else -> listOf(Color(0xFFFFE596), Gold, GoldDark)
    }
    Box(
        Modifier.size(200.dp).scale(if (state == VState.CONNECTING) pulse else 1f)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0x55FFD36B), Color(0x11000000))))
            .border(6.dp, Brush.linearGradient(ring), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⏻", color = if (state == VState.ON) Green else Gold, fontSize = 64.sp)
            Text(
                if (state == VState.ON) "قطع اتصال" else "اتصال",
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun GoldButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(58.dp).clip(RoundedCornerShape(30.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFFFFE596), Color(0xFFF2BC45), GoldDark)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Bg2, fontSize = 19.sp, fontWeight = FontWeight.Black) }
}

@Composable
fun SmallChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).border(1.dp, CardLine, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp)
    ) { Text(text, color = Cream, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun StatusPill(status: String) {
    val active = status.equals("Active", true)
    val fa = when (status.lowercase()) {
        "active" -> "فعال"
        "expired" -> "منقضی شده"
        "disabled", "inactive" -> "غیرفعال"
        "limited" -> "حجم تمام شده"
        "frozen", "suspended" -> "متوقف"
        else -> status
    }
    val c = if (active) Green else Red
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(c.copy(alpha = .15f))
            .border(1.dp, c, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 5.dp)
    ) { Text("● $fa", color = c, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, color = Gold, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    )
}

@Composable
fun Choice(text: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier.clip(shape)
            .background(if (selected) Gold.copy(alpha = .22f) else CardBg)
            .border(if (selected) 2.dp else 1.dp, if (selected) Gold else CardLine, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (selected) Gold else Cream, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun InfoCard(label: String, value: String, modifier: Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.clip(shape).background(CardBg).border(1.dp, CardLine, shape).padding(16.dp)
    ) {
        Text(label, color = Cream.copy(alpha = .7f), fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
