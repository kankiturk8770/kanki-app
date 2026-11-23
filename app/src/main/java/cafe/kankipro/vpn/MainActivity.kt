package cafe.kankipro.vpn

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // همه‌ی تم‌ها تیره‌اند: آیکن‌های نوار بالا (ساعت، آنتن، باتری) و پایین همیشه روشن، حتی وقتی گوشی در حالت روشن است
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        LiveTest.take(this, intent)
        // فقط بار اول (نه با چرخیدن صفحه، که اندروید همان intent قدیمی را دوباره می‌دهد)
        setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (LangState.en) LayoutDirection.Ltr else LayoutDirection.Rtl,
                LocalTextStyle provides TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Bold, color = K.Text),
            ) {
                MaterialTheme(colorScheme = darkColorScheme(primary = K.Gold, background = K.Ink, surface = K.Ink)) {
                    App()
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        LiveTest.take(this, intent)
    }
}

/**
 * فقط نسخه‌ی تست (debuggable، که منتشر نمی‌شود؛ نسخه‌ی release این را نادیده می‌گیرد): تست زنده‌ی شبیه‌ساز
 * (.github/live/android.sh) با یک intent یک کانفیگ مشخص را در یک حالت وصل می‌کند و وضعیت را در logcat (KankiLive) می‌نویسد.
 *   am start -n cafe.kankipro.vpn/.MainActivity --es kk_pick "node|protocol" --es kk_mode game --es kk_games "pkg1,pkg2" [--es kk_nogate 1]
 */
object LiveTest {
    @Volatile var pick: String? = null
    @Volatile var mode: String? = null
    @Volatile var games: String? = null
    /** kk_nogate=1: گیمینگ بی دروازه (موتور خود کتابخانه‌ی امنزیا برای WireGuard/AmneziaWG)، فقط برای تست همان موتور */
    @Volatile var noGate = false
    @Volatile var gen = 0

    fun on(ctx: android.content.Context) = (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun take(ctx: android.content.Context, i: android.content.Intent?) {
        if (i == null || !on(ctx)) return
        log("intent ${i.getStringExtra("kk_pick")} ${i.getStringExtra("kk_mode")}")
        i.getStringExtra("kk_pick")?.let { pick = it; mode = i.getStringExtra("kk_mode"); games = i.getStringExtra("kk_games"); noGate = i.getStringExtra("kk_nogate") == "1"; gen++ }
    }

    fun log(m: String) = android.util.Log.i("KankiLive", m)
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val state = remember { AppState.from(ctx) }

    var afterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) afterGrant?.invoke()
        else state.error = "برای اتصال باید اجازه‌ی VPN را تأیید کنید."
        afterGrant = null
    }
    // درخواست مجوز VPN: با بسته‌شدن صفحه آزاد می‌شود تا صفحه‌ی قدیمی در حافظه نماند
    DisposableEffect(state, permission) {
        state.requestVpn = { onGranted ->
            val intent = VpnService.prepare(ctx)
            if (intent == null) onGranted() else { afterGrant = onGranted; permission.launch(intent) }
        }
        onDispose { state.requestVpn = null }
    }

    // دکمه‌ی اصلی: وصل/قطع بر اساس حالت فعال؛ هنگام اتصال، لمس یعنی لغو
    fun toggle() = state.toggleMain()

    // اجازه‌ی نوتیفیکیشن (اندروید ۱۳ به بالا) برای نمایش سرعت در نوار اعلان
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    var splash by rememberSaveable { mutableStateOf(true) }

    // بارگذاری اطلاعات و اتصال خودکار
    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) {
            state.startupLoad()
            if (state.autoConnect && state.vstate == VState.OFF && state.account != null) toggle()
        }
    }

    // تست زنده (فقط نسخه‌ی تست): فهرست کانفیگ‌ها، وصل شدن به کانفیگ خواسته‌شده و وضعیت هر ۲ ثانیه در logcat
    if (LiveTest.on(ctx)) LaunchedEffect(state.loggedIn) {
        var done = -1
        while (true) {
            val a = state.account
            if (a != null && LiveTest.gen != done && LiveTest.pick != null) {
                done = LiveTest.gen
                a.configs.forEach { c -> LiveTest.log("cfg ${c.node}|${c.protocol} side=${state.cfgSide(c.node, c.protocol)} tag=${state.cfgTag(c.node, c.protocol)}") }
                val (n, p) = LiveTest.pick!!.split("|").let { it[0] to it.getOrElse(1) { "" } }
                // «PINGALL|»: پینگ همه‌ی کانفیگ‌ها با همان «تست پینگ همه» و سنجش گیمینگ، نتیجه‌ی هر کدام در logcat
                if (n == "PINGALL") {
                    state.pingAll()
                    listOf("kankimix", "kping", "mtun", "amnezia", "wireguard").forEach { state.measureFamily(it) }
                    kotlinx.coroutines.delay(45_000)
                    a.configs.forEach { c ->
                        val k = "${c.node}/${c.protocol}"
                        LiveTest.log("ping ${c.node}|${c.protocol} side=${state.cfgSide(c.node, c.protocol)} vpn=${state.vstate} ping=${state.protoPing(c.node, c.protocol)} q=${state.gameQ[k]} info=${state.infos.containsKey(k)}")
                    }
                    LiveTest.log("pingall done")
                    continue
                }
                LiveTest.games?.takeIf { it != "-" }?.let { g -> state.gameApps.clear(); state.gameApps.addAll(g.split(",").map { it.trim() }.filter { it.isNotEmpty() }) }
                state.changeMode(KMode.of(LiveTest.mode ?: "game"))
                LiveTest.log("pick $n|$p mode=${state.mode.key} games=${state.gameApps.toList()}")
                state.pickFromServers(n, p)
            }
            LiveTest.log("state ${state.vstate} mode=${state.mode.key} err=${state.error ?: ""} detail=${state.errorDetail ?: ""}")
            kotlinx.coroutines.delay(2000)
        }
    }

    // گذر نرم بین اسپلش، صفحه‌ی ورود کد، باز شدن قفل و صفحه‌ی اصلی: فقط محو متقاطع روی یک پس‌زمینه‌ی ثابت تم (۱.۴۱: بی تار شدن، که پرش دیده می‌شد)
    val screen = when { splash -> 0; state.unlocking -> 1; else -> 2 }
    Box(Modifier.fillMaxSize().background(K.Ink)) {
    androidx.compose.animation.Crossfade(targetState = screen, animationSpec = tween(700, easing = FastOutSlowInEasing), label = "screen") { sc ->
        Box(Modifier.fillMaxSize()) {
            when (sc) {
                0 -> SplashScreen { splash = false }
                1 -> UnlockScreen { state.unlocking = false }
                else -> KankiBackground(accent = if (state.vstate == VState.ON) modeColor(state.mode) else K.Gold, calm = !state.loggedIn) {
                    Box(Modifier.systemBarsPadding()) {
                        androidx.compose.animation.Crossfade(targetState = state.loggedIn, animationSpec = tween(600, easing = FastOutSlowInEasing), label = "login") { inside ->
                            if (!inside) LoginScreen(state) else MainScreen(state) { toggle() }
                        }
                    }
                }
            }
        }
    }
    }
}
