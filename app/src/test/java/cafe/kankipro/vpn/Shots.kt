package cafe.kankipro.vpn

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
abstract class ShotsBase {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    protected val out = File(System.getProperty("user.dir") ?: ".", "build/shots").apply { mkdirs() }

    protected fun shot(name: String, theme: String = "gold", content: @Composable () -> Unit) {
        ThemeState.set(theme)
        LangState.lang = "fa"
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalTextStyle provides TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Bold, color = K.Text),
            ) {
                ThemeState.animate() // در عکس‌ها تم بلافاصله و بدون درنگ کامل می‌شود
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

    protected fun capture(): Bitmap = runCatching { rule.onRoot().captureToImage().asAndroidBitmap() }.getOrElse {
        val v = rule.activity.window.decorView.rootView
        Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
    }

    // ------------------------------------------------------------------ داده‌ی نمونه
    protected val ctx get() = rule.activity

    protected fun countries() = runCatching { LightsData.load(ctx); MapData.load(ctx) }.getOrNull()

    protected val pins = listOf(
        MapPin(Geo(50.1, 8.7), "DE", false), MapPin(Geo(48.9, 2.35), "FR", false),
        MapPin(Geo(52.4, 4.9), "NL", false), MapPin(Geo(41.0, 29.0), "TR", false),
    )

    protected fun home(
        on: Boolean, smart: SmartPanelUi = SmartPanelUi(), mode: KMode = KMode.SMART,
        vs: VState = if (on) VState.ON else VState.OFF, hist: List<Long> = listOf(82, 77, 75, 79, 73, 74, 76, 74),
        alert: RobotAlert? = null, dns: DnsCardUi = DnsCardUi(),
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
        alert = alert,
        countries = countries(),
        pins = pins.map { if (vs != VState.OFF && it.iso == "DE") it.copy(selected = true) else it },
        userGeo = Geo(35.7, 51.4),
        userIso = "IR",
        locationIso = if (on) "DE" else "IR",
        locationIsExit = on,
        sheetOpen = false,
        smart = smart,
        dns = dns,
        accent = if (vs != VState.OFF) modeColor(mode) else K.Gold,
        server = if (on && mode != KMode.DNS) ServerChipUi("🇩🇪", "آلمان", "فرانکفورت", "amneziawg") else null,
    )

    protected val dnsCard = DnsCardUi(
        hasDns = true,
        servers = listOf(DnsRowUi("", "خودکار", "auto", ""), DnsRowUi("de", "آلمان", "direct", "🇩🇪")),
        pick = "", active = "آلمان", activeRoute = "مستقیم", sessionBytes = 4_200_000,
    )

    protected val robotAlert = RobotAlert("low", 1, "حجم اشتراک شما رو به اتمام است", action = "تمدید", actionId = "buy")

    @Composable
    protected fun Frame(tab: KTab, accent: androidx.compose.ui.graphics.Color = K.Gold, body: @Composable () -> Unit) {
        KankiBackground(accent = accent) {
            Box(Modifier) {
                MainFrame(tab, onTab = {}, onRobot = {}, onAccount = {}) { body() }
            }
        }
    }

    protected fun cards(): List<CountryCardUi> = listOf(
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

    protected val account = Account(
        username = "mahtab87", status = "Active", dataRemaining = "42.00 GB", used = "18.00 GB", connections = "2",
        timeRemaining = "23 روز و 4 ساعت", expiry = "24 آبان 1405، 18:20", configs = emptyList(),
        expiresAt = System.currentTimeMillis() + 23L * 86_400_000, limitGb = 60.0, usedGb = 18.0,
    )

    // ------------------------------------------------------------------ تعریف همه‌ی صفحه‌ها (یک بار، برای هر سه تم)

    protected fun pgHomeOff(): @Composable () -> Unit = { Frame(KTab.HOME) { HomeContent(home(false), HomeActions()) } }
    protected fun pgHomeOn(): @Composable () -> Unit = { Frame(KTab.HOME, modeColor(KMode.SMART)) { HomeContent(home(true), HomeActions()) } }
    protected fun pgHomeSmart(): @Composable () -> Unit = {
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
    protected fun pgHomeConnecting(): @Composable () -> Unit = {
        Frame(KTab.HOME) { HomeContent(home(false, vs = VState.CONNECTING), HomeActions()) }
    }
    protected fun pgHomeDns(): @Composable () -> Unit = {
        Frame(KTab.HOME, modeColor(KMode.DNS)) { HomeContent(home(true, mode = KMode.DNS, dns = dnsCard), HomeActions()) }
    }
    protected fun pgHomeAlert(): @Composable () -> Unit = {
        Frame(KTab.HOME) { HomeContent(home(false, alert = robotAlert), HomeActions()) }
    }
    protected fun pgHomeGameOn(): @Composable () -> Unit = {
        Frame(KTab.HOME, modeColor(KMode.GAME)) { HomeContent(home(true, mode = KMode.GAME), HomeActions()) }
    }
    protected fun pgServers(): @Composable () -> Unit = {
        Frame(KTab.SERVERS) { ServersContent(cards(), PingAllUi(finished = true, total = 9, done = 9, udp = 8, tcp = 1), null, ServersActions()) }
    }
    protected fun pgSpeed(): @Composable () -> Unit = {
        Frame(KTab.TEST) {
            SpeedContent(SpeedUi(phase = "done", down = 48.6, up = 12.3, ping = 74, jitter = 6, server = "Cloudflare", viaVpn = true, usedBytes = 96_000_000), null, {}, {})
        }
    }
    protected fun pgSettings(): @Composable () -> Unit = {
        Frame(KTab.SETTINGS) { SettingsContent(SettingsUi("", true, true, "fa", 2, "1.18.1"), null, SettingsActions(), {}) }
    }
    protected fun pgAccount(): @Composable () -> Unit = {
        Frame(KTab.HOME) { HomeContent(home(false), HomeActions()) }
        AccountSheet(true, account, AccountActions())
    }
    protected fun pgGame(): @Composable () -> Unit = {
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
    protected fun pgIntro(): @Composable () -> Unit = { KankiBackground { SplashVisual(.68f) } }
    protected fun pgEmergency(): @Composable () -> Unit = {
        KankiBackground { EmergencyScreen(AppState.from(ctx).apply { code = "kanki-4-demo" }, {}) }
    }

    /** همه‌ی صفحه‌ها، به ترتیب نمایش */
    protected fun pages(): List<Pair<String, @Composable () -> Unit>> = listOf(
        "01-home-off" to pgHomeOff(),
        "02-home-on" to pgHomeOn(),
        "03-home-smart" to pgHomeSmart(),
        "04-servers" to pgServers(),
        "05-speed" to pgSpeed(),
        "06-settings" to pgSettings(),
        "07-account" to pgAccount(),
        "08-gaming" to pgGame(),
        "09-intro" to pgIntro(),
        "10-emergency" to pgEmergency(),
    )

    // ------------------------------------------------------------------ تم طلایی کانکی (پیش‌فرض)
    // ------------------------------------------------------------------ فریم‌های انیمیشن (برای GIF)

    /**
     * صفحه‌ی اول در حالت گیمینگ، هر ۵۰ میلی‌ثانیه یک فریم (build/shots/frames/game-NNN.jpg):
     * خاموش با «هوشمند» ← انتخاب گیمینگ ← در حال اتصال ← وصل، و هر ۱.۵ ثانیه یک نمونه‌ی تازه‌ی پینگ (یکی با پرش بزرگ).
     */
    /**
     * فقط دکمه‌ی اتصال در حالت گیمینگ از نزدیک (build/shots/frames/orb-NNN.jpg، هر ۵۰ میلی‌ثانیه): انتخاب گیمینگ ←
     * در حال اتصال ← وصل؛ هر ۱.۵ ثانیه یک نمونه‌ی پینگ (با چند پرش بزرگ) و دیتای کم ← زیاد ← کم تا تند و کند شدن
     * تیرها دیده شود. سریع‌تر از z_game_frames (بدون نقشه). طولانی است، پس روی گیت‌هاب اجرا نمی‌شود؛ فقط با
     * KANKI_ORB=1 (مثلاً KANKI_ORB=1 gradle :app:testDebugUnitTest --tests "cafe.kankipro.vpn.Shots.z_orb_frames").
     */
    @Test fun z_orb_frames() {
        org.junit.Assume.assumeTrue(System.getenv("KANKI_ORB") != null)
        ThemeState.set("gold")
        LangState.lang = "fa"
        val mode = mutableStateOf(KMode.SMART)
        val vs = mutableStateOf(VState.OFF)
        val hist = mutableStateOf(listOf<Long>(41, 39, 44, 38, 40, 37, 42, 39))
        val traffic = mutableStateOf(60_000L)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalTextStyle provides TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Bold, color = K.Text),
            ) {
                val on = vs.value == VState.ON
                KankiBackground(accent = if (on) modeColor(mode.value) else K.Gold) {
                    Box(Modifier.align(Alignment.Center).size(300.dp).testTag("orb"), contentAlignment = Alignment.Center) {
                        val ui = home(on, mode = mode.value, vs = vs.value, hist = hist.value)
                        OrbButton(if (on) ui.copy(rx = traffic.value, tx = traffic.value / 10) else ui, K.Gold, enabled = true) {}
                    }
                }
            }
        }
        val dir = File(out, "frames").apply { mkdirs() }
        var n = 0
        val samples = listOf<Long>(39, 41, 92, 45, 150, 46, 40, 38)
        fun grab(count: Int, onFrame: (Int) -> Unit = {}) = repeat(count) { i ->
            onFrame(i)
            rule.mainClock.advanceTimeBy(50)
            val b = rule.onNodeWithTag("orb").fetchSemanticsNode().boundsInRoot
            val full = capture()
            val big = Bitmap.createBitmap(full, b.left.toInt(), b.top.toInt(), b.width.toInt(), b.height.toInt())
            File(dir, String.format(java.util.Locale.US, "orb-%03d.jpg", n++)).outputStream().use { big.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            listOf(big, full).distinct().forEach { it.recycle() }
        }
        rule.mainClock.advanceTimeBy(1500)
        grab(10)
        mode.value = KMode.GAME
        grab(24)
        vs.value = VState.CONNECTING
        grab(60)
        vs.value = VState.ON
        grab(260) { i ->
            if (i % 30 == 20) hist.value = (hist.value + samples[(i / 30) % samples.size]).takeLast(12)
            when (i) { 90 -> traffic.value = 3_000_000L; 190 -> traffic.value = 15_000L }
        }
    }

    @Test fun z_game_frames() {
        org.junit.Assume.assumeTrue(System.getenv("KANKI_ORB") != null)
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
        val samples = listOf<Long>(39, 92, 45, 38)
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

/**
 * کوچک‌ترین گوشی (۳۶۰×۶۴۰dp): صفحه‌ی خانه در هر شش حالت (خاموش، در حال اتصال، وصل، هوشمند با پنل باز، DNS، هشدار ربات)
 * و گیمینگ وصل باید بدون اسکرول جا شود و چیزی زیر نوار پایین نرود. عکس‌ها با پیشوند s640-.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
class ShotsSmall : ShotsBase() {
    @Test fun w01() = shot("s640-gold-01-home-off", "gold", pgHomeOff())
    @Test fun w02() = shot("s640-gold-02-home-on", "gold", pgHomeOn())
    @Test fun w03() = shot("s640-gold-03-home-smart", "gold", pgHomeSmart())
    @Test fun w04() = shot("s640-gold-11-home-connecting", "gold", pgHomeConnecting())
    @Test fun w05() = shot("s640-gold-12-home-dns", "gold", pgHomeDns())
    @Test fun w06() = shot("s640-gold-13-home-alert", "gold", pgHomeAlert())
    @Test fun w07() = shot("s640-gold-14-home-game-on", "gold", pgHomeGameOn())
    @Test fun w08() = shot("s640-mono-01-home-off", "mono", pgHomeOff())
    @Test fun w09() = shot("s640-mono-02-home-on", "mono", pgHomeOn())
    @Test fun w10() = shot("s640-neon-01-home-off", "neon", pgHomeOff())
    @Test fun w11() = shot("s640-neon-02-home-on", "neon", pgHomeOn())
    @Test fun w12() = shot("s640-gold-04-servers", "gold", pgServers())
}

/** گوشی‌های میانی (۳۶۰×۷۴۰ و ۳۹۳×۸۵۰): همان حالت‌های صفحه‌ی خانه، برای چک جا شدن بدون اسکرول */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xxhdpi")
class ShotsMid : ShotsBase() {
    @Test fun a01() = shot("s740-gold-01-home-off", "gold", pgHomeOff())
    @Test fun a02() = shot("s740-gold-02-home-on", "gold", pgHomeOn())
    @Test fun a03() = shot("s740-gold-03-home-smart", "gold", pgHomeSmart())
    @Test fun a04() = shot("s740-gold-12-home-dns", "gold", pgHomeDns())
    @Test fun a05() = shot("s740-gold-13-home-alert", "gold", pgHomeAlert())
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h850dp-xxhdpi")
class ShotsTall : ShotsBase() {
    @Test fun b01() = shot("s850-gold-01-home-off", "gold", pgHomeOff())
    @Test fun b02() = shot("s850-gold-02-home-on", "gold", pgHomeOn())
    @Test fun b03() = shot("s850-gold-03-home-smart", "gold", pgHomeSmart())
    @Test fun b04() = shot("s850-gold-12-home-dns", "gold", pgHomeDns())
    @Test fun b05() = shot("s850-gold-13-home-alert", "gold", pgHomeAlert())
}

// ------------------------------------------------------------------ عکس‌های اصلی: همه‌ی صفحه‌ها در هر سه تم (۴۱۱×۹۱۵dp)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h915dp-xxhdpi")
class Shots : ShotsBase() {
    @Test fun g01() = shot("gold-01-home-off", "gold", pgHomeOff())
    @Test fun g02() = shot("gold-02-home-on", "gold", pgHomeOn())
    @Test fun g03() = shot("gold-03-home-smart", "gold", pgHomeSmart())
    @Test fun g04() = shot("gold-04-servers", "gold", pgServers())
    @Test fun g05() = shot("gold-05-speed", "gold", pgSpeed())
    @Test fun g06() = shot("gold-06-settings", "gold", pgSettings())
    @Test fun g07() = shot("gold-07-account", "gold", pgAccount())
    @Test fun g08() = shot("gold-08-gaming", "gold", pgGame())
    @Test fun g09() = shot("gold-09-intro", "gold", pgIntro())
    @Test fun g10() = shot("gold-10-emergency", "gold", pgEmergency())

    // ------------------------------------------------------------------ تم گرافیت (تیره‌ی مینیمال)
    @Test fun m01() = shot("mono-01-home-off", "mono", pgHomeOff())
    @Test fun m02() = shot("mono-02-home-on", "mono", pgHomeOn())
    @Test fun m03() = shot("mono-03-home-smart", "mono", pgHomeSmart())
    @Test fun m04() = shot("mono-04-servers", "mono", pgServers())
    @Test fun m05() = shot("mono-05-speed", "mono", pgSpeed())
    @Test fun m06() = shot("mono-06-settings", "mono", pgSettings())
    @Test fun m07() = shot("mono-07-account", "mono", pgAccount())
    @Test fun m08() = shot("mono-08-gaming", "mono", pgGame())
    @Test fun m09() = shot("mono-09-intro", "mono", pgIntro())
    @Test fun m10() = shot("mono-10-emergency", "mono", pgEmergency())

    // ------------------------------------------------------------------ تم نئون (رنگی)
    @Test fun n01() = shot("neon-01-home-off", "neon", pgHomeOff())
    @Test fun n02() = shot("neon-02-home-on", "neon", pgHomeOn())
    @Test fun n03() = shot("neon-03-home-smart", "neon", pgHomeSmart())
    @Test fun n04() = shot("neon-04-servers", "neon", pgServers())
    @Test fun n05() = shot("neon-05-speed", "neon", pgSpeed())
    @Test fun n06() = shot("neon-06-settings", "neon", pgSettings())
    @Test fun n07() = shot("neon-07-account", "neon", pgAccount())
    @Test fun n08() = shot("neon-08-gaming", "neon", pgGame())
    @Test fun n09() = shot("neon-09-intro", "neon", pgIntro())
    @Test fun n10() = shot("neon-10-emergency", "neon", pgEmergency())

}
