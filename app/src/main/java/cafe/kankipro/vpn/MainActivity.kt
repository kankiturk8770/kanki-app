package cafe.kankipro.vpn

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
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

    // گذر نرم بین اسپلش، باز شدن قفل و صفحه‌ی اصلی (۱.۳۴.۲): محو متقاطع، و صفحه‌ی تازه از کمی تار و کوچک‌تر به واضح می‌رسد (بی پرش)
    val screen = when { splash -> 0; state.unlocking -> 1; else -> 2 }
    androidx.compose.animation.Crossfade(targetState = screen, animationSpec = tween(650, easing = FastOutSlowInEasing), label = "screen") { sc ->
        val intro = remember { Animatable(if (sc == 0) 1f else 0f) }
        LaunchedEffect(Unit) { intro.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { val k = .97f + .03f * intro.value; scaleX = k; scaleY = k }
                .then(if (intro.value < 1f) Modifier.blur((14f * (1f - intro.value)).dp) else Modifier),
        ) {
            when (sc) {
                0 -> SplashScreen { splash = false }
                1 -> UnlockScreen { state.unlocking = false }
                else -> KankiBackground(accent = if (state.vstate == VState.ON) modeColor(state.mode) else K.Gold, calm = !state.loggedIn) {
                    Box(Modifier.systemBarsPadding()) {
                        if (!state.loggedIn) LoginScreen(state) else MainScreen(state) { toggle() }
                    }
                }
            }
        }
    }
}
