package cafe.kankipro.vpn

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/*
 * عکس صفحه‌های برنامه، بدون گوشی و شبیه‌ساز: همان صفحه‌های واقعی (HomeContent، ServersContent، …) با
 * داده‌ی نمونه روی Robolectric کشیده می‌شوند و PNG در app/build/shots ذخیره می‌شود.
 * روی گیت‌هاب: .github/workflows/shots.yml (عکس‌ها در شاخه‌ی shots). فقط برای دیدن ظاهر است؛ به
 * کد برنامه دست نمی‌زند.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class Shots {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("user.dir") ?: ".", "build/shots").apply { mkdirs() }

    private fun shot(name: String, theme: String = "gold", content: @Composable () -> Unit) {
        ThemeState.set(theme)
        LangState.lang = "fa"
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalTextStyle provides TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Bold, color = K.Text),
            ) {
                MaterialTheme(colorScheme = darkColorScheme(primary = K.Gold, background = K.Ink, surface = K.Ink)) {
                    content()
                }
            }
        }
        // انیمیشن‌ها (ستاره‌ها، حلقه‌ی دکمه) چند لحظه جلو می‌روند تا عکس حالت معمول را نشان دهد
        rule.mainClock.advanceTimeBy(2400)
        rule.waitForIdle()
        File(out, "$name.png").outputStream().use { capture().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun capture(): Bitmap = runCatching { rule.onRoot().captureToImage().asAndroidBitmap() }.getOrElse {
        val v = rule.activity.window.decorView.rootView
        Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
    }

    // ------------------------------------------------------------------ داده‌ی نمونه
    private val ctx get() = rule.activity

    private fun countries() = runCatching { LightsData.load(ctx); MapData.load(ctx) }.getOrNull()

    private val pins = listOf(
        MapPin(Geo(50.1, 8.7), "DE", false), MapPin(Geo(48.9, 2.35), "FR", false),
        MapPin(Geo(52.4, 4.9), "NL", false), MapPin(Geo(41.0, 29.0), "TR", false),
    )

    private fun home(
        on: Boolean, smart: SmartPanelUi = SmartPanelUi(), mode: KMode = KMode.SMART,
        vs: VState = if (on) VState.ON else VState.OFF, hist: List<Long> = listOf(82, 77, 75, 79, 73, 74, 76, 74),
    ) = HomeUi(
        vstate = vs,
        hasAccount = true,
        loading = false,
        mode = mode,
        livePing = if (on) hist.takeLast(3).min() else null,
        pingHist = if (on) hist else emptyList(),
        rx = if (on) 1_350_000 else 0,
        tx = if (on) 124_000 else 0,
        elapsedSec = if (on) 754 else null,
        sub = SubUi("42", "23", "Active", 0.35f),
        alert = null,
        countries = countries(),
        pins = pins.map { if (on && it.iso == "DE") it.copy(selected = true) else it },
        userGeo = Geo(35.7, 51.4),
        userIso = "IR",
        locationIso = if (on) "DE" else "IR",
        locationIsExit = on,
        sheetOpen = false,
        smart = smart,
        dns = DnsCardUi(),
        accent = if (on) modeColor(mode) else K.Gold,
        server = if (on) ServerChipUi("🇩🇪", "آلمان", "فرانکفورت", "amneziawg") else null,
    )

    @Composable
    private fun Frame(tab: KTab, accent: androidx.compose.ui.graphics.Color = K.Gold, body: @Composable () -> Unit) {
        KankiBackground(accent = accent) {
            Box(Modifier) {
                MainFrame(tab, onTab = {}, onRobot = {}, onAccount = {}) { body() }
            }
        }
    }

    private fun cards(): List<CountryCardUi> = listOf(
        CountryCardUi(
            "de", "آلمان", "فرانکفورت", "🇩🇪", 74, expanded = true, selected = true,
            rows = listOf(
                CfgRowUi("de", "kankimix", "KANKI MIX", 68, busy = false, selected = false, host = null),
                CfgRowUi("de", "amneziawg", "AmneziaWG · تانل", 74, busy = false, selected = true, host = null),
                CfgRowUi("de", "amneziawg-direct", "AmneziaWG · مستقیم", 92, busy = false, selected = false, host = null),
                CfgRowUi("de", "wireguard", "WireGuard", -1, busy = false, selected = false, host = null),
            ),
        ),
        CountryCardUi("fr", "فرانسه", "پاریس", "🇫🇷", 88, expanded = false, selected = false, rows = emptyList()),
        CountryCardUi("nl", "هلند", "آمستردام", "🇳🇱", 95, expanded = false, selected = false, rows = emptyList()),
        CountryCardUi("tr", "ترکیه", "استانبول", "🇹🇷", 61, expanded = false, selected = false, rows = emptyList()),
    )

    private val account = Account(
        username = "mahtab87", status = "Active", dataRemaining = "42.00 GB", used = "18.00 GB", connections = "2",
        timeRemaining = "23 روز و 4 ساعت", expiry = "24 آبان 1405، 18:20", configs = emptyList(),
        expiresAt = System.currentTimeMillis() + 23L * 86_400_000, limitGb = 60.0, usedGb = 18.0,
    )

    // ------------------------------------------------------------------ صفحه‌ها (تم طلایی)
    @Test fun s1_home_off() = shot("1-home-off") { Frame(KTab.HOME) { HomeContent(home(false), HomeActions()) } }

    @Test fun s2_home_on() = shot("2-home-on") { Frame(KTab.HOME, modeColor(KMode.SMART)) { HomeContent(home(true), HomeActions()) } }

    @Test fun s3_home_smart() = shot("3-home-smart") {
        Frame(KTab.HOME) {
            HomeContent(
                home(false, SmartPanelUi(
                    open = true, done = 6, total = 8, status = "سنجش پینگ همه‌ی سرورها…",
                    rows = listOf(
                        SmartRowUi("tr|kankimix", "🇹🇷", "ترکیه · استانبول · KANKI MIX", 61),
                        SmartRowUi("de|amneziawg", "🇩🇪", "آلمان · فرانکفورت · AmneziaWG", 74),
                        SmartRowUi("fr|hysteria2", "🇫🇷", "فرانسه · پاریس · Hysteria2", 88),
                    ),
                    current = "tr|kankimix",
                )),
                HomeActions(),
            )
        }
    }

    @Test fun s4_servers() = shot("4-servers") {
        Frame(KTab.SERVERS) { ServersContent(cards(), PingAllUi(finished = true, total = 9, done = 9, udp = 8, tcp = 1), null, ServersActions()) }
    }

    @Test fun s5_speed() = shot("5-speed") {
        Frame(KTab.TEST) {
            SpeedContent(SpeedUi(phase = "done", down = 48.6, up = 12.3, ping = 74, jitter = 6, server = "Cloudflare", viaVpn = true, usedBytes = 96_000_000), null, {}, {})
        }
    }

    @Test fun s6_settings() = shot("6-settings") {
        Frame(KTab.SETTINGS) { SettingsContent(SettingsUi("", true, true, "fa", 2, "1.17.0"), null, SettingsActions(), {}) }
    }

    @Test fun s7_account() = shot("7-account") {
        Frame(KTab.HOME) { HomeContent(home(false), HomeActions()) }
        AccountSheet(true, account, AccountActions())
    }

    @Test fun s8_game() = shot("8-gaming") {
        Frame(KTab.HOME) { HomeContent(home(false, mode = KMode.GAME), HomeActions()) }
        val servers = listOf(
            SheetServer("tr", "kankimix", "🇹🇷", "ترکیه · استانبول", null, 61),
            SheetServer("de", "kankimix", "🇩🇪", "آلمان · فرانکفورت", "مستقیم", 68),
            SheetServer("de@tunnel", "kankimix", "🇩🇪", "آلمان · فرانکفورت", "تانل", 81),
        )
        GameSheet(
            visible = true,
            families = listOf(
                GameFamilyUi("kankimix", "KANKI MIX", "مخصوص بازی", servers),
                GameFamilyUi("amnezia", "Amnezia", "ضد فیلتر", servers.map { it.copy(proto = "amneziawg") }),
                GameFamilyUi("wireguard", "WireGuard", "ساده", emptyList()),
            ),
            initFamily = "kankimix", current = "tr|kankimix", currentAuto = false, busy = false, kmixDup = true,
            gamesOnly = false, autoGames = true, appCount = 3, canBlock = true,
            onKmixDup = {}, onGamesOnly = {}, onAutoGames = {}, onPickApps = {}, onFamilyShown = {},
            onConnect = { _, _ -> }, onFastest = {}, onClose = {},
        )
    }

    // ------------------------------------------------------------------ صفحه‌ی اول، وصل، با هر تم موجود
    @Test fun t_mono() = shot("theme-mono", "mono") { Frame(KTab.HOME, modeColor(KMode.SMART)) { HomeContent(home(true), HomeActions()) } }

    @Test fun t_crimson() = shot("theme-crimson", "crimson") { Frame(KTab.HOME, modeColor(KMode.SMART)) { HomeContent(home(true), HomeActions()) } }

    @Test fun t_cyber() = shot("theme-cyber", "cyber") { Frame(KTab.HOME, modeColor(KMode.SMART)) { HomeContent(home(true), HomeActions()) } }

    @Test fun t_violet() = shot("theme-violet", "violet") { Frame(KTab.HOME, modeColor(KMode.SMART)) { HomeContent(home(true), HomeActions()) } }

    // ------------------------------------------------------------------ فریم‌های انیمیشن (برای GIF)

    /**
     * صفحه‌ی اول در حالت گیمینگ، هر ۵۰ میلی‌ثانیه یک فریم (build/shots/frames/game-NNN.jpg):
     * خاموش با «هوشمند» ← انتخاب گیمینگ ← در حال اتصال ← وصل، و هر ۳ ثانیه یک نمونه‌ی تازه‌ی پینگ.
     */
    @Test fun z_game_frames() {
        ThemeState.set("gold")
        LangState.lang = "fa"
        val mode = mutableStateOf(KMode.SMART)
        val vs = mutableStateOf(VState.OFF)
        val hist = mutableStateOf(listOf<Long>(41, 39, 44, 38, 40, 37, 42, 39))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalTextStyle provides TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Bold, color = K.Text),
            ) {
                MaterialTheme(colorScheme = darkColorScheme(primary = K.Gold, background = K.Ink, surface = K.Ink)) {
                    val on = vs.value == VState.ON
                    Frame(KTab.HOME, if (on) modeColor(mode.value) else K.Gold) {
                        HomeContent(home(on, mode = mode.value, vs = vs.value, hist = hist.value), HomeActions())
                    }
                }
            }
        }
        val dir = File(out, "frames").apply { mkdirs() }
        var n = 0
        val samples = listOf<Long>(36, 43, 38, 35)
        fun grab(count: Int, onFrame: (Int) -> Unit = {}) = repeat(count) { i ->
            onFrame(i)
            rule.mainClock.advanceTimeBy(50)
            val big = capture()
            val small = Bitmap.createScaledBitmap(big, big.width * 2 / 3, big.height * 2 / 3, true)
            File(dir, String.format(java.util.Locale.US, "game-%03d.jpg", n++)).outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (small !== big) small.recycle()
            big.recycle()
        }
        rule.mainClock.advanceTimeBy(1500)
        grab(12)
        mode.value = KMode.GAME
        grab(18)
        vs.value = VState.CONNECTING
        grab(56)
        vs.value = VState.ON
        grab(100) { i ->
            if (i % 30 == 20) hist.value = (hist.value + samples[(i / 30) % samples.size]).takeLast(12)
        }
    }
}
