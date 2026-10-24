package cafe.kankipro.vpn

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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // فقط بار اول (نه با چرخیدن صفحه، که اندروید همان intent قدیمی را دوباره می‌دهد)
        if (savedInstanceState == null) openFromNotification(intent)
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

    override fun onStop() {
        super.onStop()
        // برنامه رفت پس‌زمینه: ربات راهنما ساکت می‌شود
        GuideVoice.stop()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openFromNotification(intent)
    }

    /** لمس اعلان «پیام تازه از پشتیبانی»: چت اضطراری باز می‌شود */
    private fun openFromNotification(i: android.content.Intent?) {
        if (i?.getBooleanExtra(EmergencyStore.EXTRA_OPEN, false) == true) {
            AppState.from(this).emergencyOpen = true
            i.removeExtra(EmergencyStore.EXTRA_OPEN)
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

    // آدرس اضطراری را نگه می‌داریم تا وقتی اینترنت قطع است هم باز شود
    LaunchedEffect(state.account) { Emergency.remember(ctx, state.account?.emergency, state.account?.emergencyKey) }

    // بارگذاری اطلاعات و اتصال خودکار
    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) {
            state.startupLoad()
            if (state.autoConnect && state.vstate == VState.OFF && state.account != null) toggle()
        }
    }

    when {
        splash -> SplashScreen { splash = false }
        state.unlocking -> UnlockScreen { state.unlocking = false }
        state.emergencyOpen -> KankiBackground(accent = K.Red) {
            Box(Modifier.systemBarsPadding()) { EmergencyScreen(state) { state.emergencyOpen = false } }
        }
        else -> KankiBackground(accent = if (state.vstate == VState.ON) modeColor(state.mode) else K.Gold, calm = !state.loggedIn) {
            Box(Modifier.systemBarsPadding()) {
                if (!state.loggedIn) LoginScreen(state) else MainScreen(state) { toggle() }
            }
        }
    }
}
