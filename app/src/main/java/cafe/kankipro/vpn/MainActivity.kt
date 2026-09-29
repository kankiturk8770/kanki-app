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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
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
    val state = remember { AppState(ctx) }
    val scope = rememberCoroutineScope()

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) scope.launch { state.connect() }
        else state.error = "برای اتصال باید اجازه‌ی VPN را تأیید کنید."
    }

    fun toggle() {
        when (state.vstate) {
            VState.ON -> scope.launch { state.disconnect() }
            VState.OFF -> {
                val intent = VpnService.prepare(ctx)
                if (intent != null) permission.launch(intent) else scope.launch { state.connect() }
            }
            VState.CONNECTING -> {}
        }
    }

    // بارگذاری اطلاعات و اتصال خودکار
    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) {
            state.refresh()
            if (state.autoConnect && state.vstate == VState.OFF && state.account != null) toggle()
        }
    }

    KankiBackground(accent = if (state.vstate == VState.ON) K.Green else K.Gold) {
        Box(Modifier.systemBarsPadding()) {
            if (!state.loggedIn) LoginScreen(state) else MainScreen(state) { toggle() }
        }
    }
}
