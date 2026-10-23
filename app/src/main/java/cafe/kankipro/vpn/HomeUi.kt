package cafe.kankipro.vpn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// صفحه‌ی اتصال (خانه) با طراحی طلایی. فقط ظاهر است؛ داده‌ها از AppState در Screens.kt می‌آیند.

enum class KTab(val title: String) { HOME("خانه"), SERVERS("سرورها"), TEST("تست سرعت"), SETTINGS("تنظیمات") }

/** اطلاعات خلاصه‌ی اشتراک برای نوار پایین صفحه‌ی اتصال (فقط عدد)؛ usedFrac: سهم مصرف‌شده از حجم (null = نامحدود) */
data class SubUi(val gbLeft: String, val daysLeft: String, val status: String, val usedFrac: Float? = null)

/** سروری که الان به آن وصل است (کارت زیر دکمه‌ی اتصال) */
data class ServerChipUi(val flag: String, val country: String, val city: String?, val proto: String)

/** همه‌ی چیزهایی که صفحه‌ی اتصال نشان می‌دهد */
data class HomeUi(
    val vstate: VState,
    val hasAccount: Boolean,
    val loading: Boolean,
    val mode: KMode,
    /** پینگ زنده و چند پینگ آخر (برای نمودار کوچک) */
    val livePing: Long?,
    val pingHist: List<Long>,
    /** سرعت لحظه‌ای (بایت بر ثانیه) */
    val rx: Long,
    val tx: Long,
    val elapsedSec: Long?,
    val sub: SubUi?,
    val alert: RobotAlert?,
    val countries: List<Country>?,
    val pins: List<MapPin>,
    val userGeo: Geo?,
    val userIso: String?,
    /** کشوری که چیپ بالای نقشه نشان می‌دهد: کشور کاربر، یا وقتی وصل است کشور IP خروجی */
    val locationIso: String?,
    val locationIsExit: Boolean = false,
    /** یک پنل (گیمینگ/برنامه‌ها) باز است؛ دکمه‌ی اصلی کاری نمی‌کند */
    val sheetOpen: Boolean,
    val smart: SmartPanelUi,
    val dns: DnsCardUi,
    /** رنگ اتصال: پروتکل وصل‌شده (یا DNS)، وقتی قطع است رنگ حالت */
    val accent: Color = K.Gold,
    /** سرور وصل‌شده (null وقتی قطع است یا حالت DNS) */
    val server: ServerChipUi? = null,
)

/** کارهایی که از صفحه‌ی اتصال انجام می‌شود */
class HomeActions(
    val toggle: () -> Unit = {},
    val tapMode: (KMode) -> Unit = {},
    val longMode: (KMode) -> Unit = {},
    val openAccount: () -> Unit = {},
    val alertAction: (String) -> Unit = {},
    val dismissAlert: () -> Unit = {},
    val retry: () -> Unit = {},
    val dns: DnsActions = DnsActions(),
)

// ================================================================== قاب اصلی، سربرگ و نوار پایین

/** قاب همه‌ی تب‌ها: سربرگ بالا، محتوا، نوار تب‌ها پایین */
@Composable
fun MainFrame(tab: KTab, onTab: (KTab) -> Unit, onRobot: () -> Unit, onAccount: () -> Unit, onEmergency: () -> Unit = {}, content: @Composable BoxScope.() -> Unit) {
    ThemeState.animate() // گذر نرم بین تم‌ها
    // ورود محو + لغزش ملایم محتوا هنگام تعویض تب (سبک و ۶۰فریمی)
    val enter = remember { Animatable(1f) }
    LaunchedEffect(tab) {
        enter.snapTo(0f)
        enter.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
    }
    Column(Modifier.fillMaxSize()) {
        AppHeader(onRobot, onAccount, onEmergency)
        Box(
            Modifier.weight(1f).graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 14.dp.toPx()
            },
            content = content,
        )
        BottomBar(tab, onTab)
    }
}

/** سربرگ: لوگوی سپر K و KANKI VPN در یک طرف، سه دکمه‌ی کوچک مربعی (لینک، ربات، گفتگو) در طرف دیگر */
@Composable
fun AppHeader(onRobot: () -> Unit, onAccount: () -> Unit, onEmergency: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = D.PagePad, end = D.PagePad, top = D.S2, bottom = D.S1), verticalAlignment = Alignment.CenterVertically) {
        Logo3D(30.dp)
        Spacer(Modifier.width(D.S2))
        BrandTitle(15.sp)
        Spacer(Modifier.weight(1f))
        Box(Modifier.guideTarget("emergency")) { HeaderIcon(K.Accent, onEmergency) { emergencyChatIcon(it) } }
        Spacer(Modifier.width(D.S2))
        Box(Modifier.guideTarget("robot")) { HeaderIcon(K.Gold, onRobot) { drawRobot(body = false) } }
        Spacer(Modifier.width(D.S2))
        Box(Modifier.guideTarget("account")) { HeaderIcon(K.Gold, onAccount) { linkIcon(it) } }
    }
}

/** دکمه‌ی کوچک مربعی شیشه‌ای سربرگ با لبه‌ی طلایی */
@Composable
fun HeaderIcon(tint: Color, onClick: () -> Unit, draw: DrawScope.(Color) -> Unit) {
    val shape = RoundedCornerShape(D.RS)
    Box(
        Modifier.size(D.IconBox).goldGlass(shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(D.Icon)) { draw(tint) } }
}

/** حباب گفتگو با علامت تعجب (چت اضطراری) */
fun DrawScope.emergencyChatIcon(c: Color) {
    val w = size.width
    val st = Stroke(w * .085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val bubble = Path().apply {
        moveTo(w * .14f, w * .20f)
        lineTo(w * .86f, w * .20f)
        lineTo(w * .86f, w * .68f)
        lineTo(w * .40f, w * .68f)
        lineTo(w * .20f, w * .86f)
        lineTo(w * .22f, w * .68f)
        lineTo(w * .14f, w * .68f)
        close()
    }
    drawPath(bubble, c, style = st)
    drawLine(c, Offset(w * .5f, w * .32f), Offset(w * .5f, w * .47f), w * .09f, StrokeCap.Round)
    drawCircle(c, w * .05f, Offset(w * .5f, w * .58f))
}

/** دو حلقه‌ی زنجیر (لینک اشتراک) */
fun DrawScope.linkIcon(c: Color) {
    val w = size.width
    val st = Stroke(w * .095f, cap = StrokeCap.Round)
    rotate(-45f) {
        drawRoundRect(c, Offset(w * .06f, w * .36f), Size(w * .48f, w * .28f), CornerRadius(w * .14f), style = st)
        drawRoundRect(c, Offset(w * .46f, w * .36f), Size(w * .48f, w * .28f), CornerRadius(w * .14f), style = st)
    }
}

@Composable
fun BottomBar(current: KTab, onSelect: (KTab) -> Unit) {
    val shape = RoundedCornerShape(D.RL)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = D.S3, vertical = D.S2).goldGlass(shape)
            .padding(D.S1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(D.S1),
    ) {
        KTab.entries.forEachIndexed { i, t ->
            val sel = t == current
            val c = if (sel) K.Gold else K.Sub
            val ids = listOf("nav_home", "nav_servers", "nav_test", "nav_settings")
            val ts = RoundedCornerShape(D.RM)
            Column(
                Modifier.weight(1f).then(if (sel) Modifier.goldGlass(ts, selected = true) else Modifier.clip(ts))
                    .clickable { onSelect(t) }
                    .guideTarget(ids[i])
                    .padding(vertical = D.S2),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Canvas(Modifier.size(D.Icon)) { navIcon(i, c) }
                Spacer(Modifier.height(D.S1))
                T(t.title, size = 11.sp, color = c, weight = if (sel) FontWeight.Black else FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

// ================================================================== صفحه‌ی اتصال

/**
 * گوشی کوتاه (فضای صفحه‌ی خانه کمتر از ۵۶۰dp): کارت‌ها و کاشی‌ها یک پله کوتاه‌تر و فاصله‌ها یک پله کمتر می‌شوند تا
 * صفحه‌ی خانه بدون اسکرول جا شود
 */
val LocalCompact = compositionLocalOf { false }

/**
 * صفحه‌ی اتصال. هرگز اسکرول نمی‌خورد: کارت‌های پایین اندازه‌ی ثابت دارند و هرچه بماند بین نقشه و دکمه‌ی اتصال
 * تقسیم می‌شود (هر دو با حداقل و حداکثر). پنل هوشمند و هشدار ربات جای جدا نمی‌گیرند و روی نقشه می‌آیند.
 */
@Composable
fun HomeContent(ui: HomeUi, act: HomeActions, modifier: Modifier = Modifier) {
    val mc = ui.accent
    val on = ui.vstate == VState.ON
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxHeight < 560.dp
        val gap = if (compact) D.S1 else D.S2
        CompositionLocalProvider(LocalCompact provides compact) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = D.PagePad)) {
                    LocationChip(ui.locationIso, ui.locationIsExit)
                }
                // نقشه و پنل‌های روی آن (هوشمند، هشدار ربات)؛ ارتفاعش با فضای مانده (۷۶ تا ۲۵۰dp)
                BoxWithConstraints(Modifier.fillMaxWidth().weight(.44f)) {
                    val mapH = maxHeight.coerceIn(76.dp, 250.dp)
                    // روی گوشی کوتاه پنل هوشمند فقط تعداد ردیفی که روی نقشه جا می‌شود (سرتیتر و نوار پیشرفت همیشه)
                    val rows = ((maxHeight - gap * 2 - 64.dp) / (SmartRowH + D.S1)).toInt().coerceAtLeast(1)
                    WorldMap(
                        ui.countries, ui.pins, ui.userGeo, ui.userIso, on,
                        Modifier.fillMaxWidth().height(mapH).align(Alignment.Center).guideTarget("map"),
                        accent = if (ui.vstate == VState.OFF) K.Gold else mc,
                        dns = ui.mode == KMode.DNS,
                    )
                    Column(
                        Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = D.PagePad, vertical = gap),
                        verticalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        AnimatedVisibility(ui.hasAccount && ui.smart.open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                            SmartPanel(ui.smart.copy(rows = ui.smart.rows.take(rows)))
                        }
                        AnimatedVisibility(visible = ui.alert != null) {
                            ui.alert?.let { al -> RobotBanner(al, onAction = act.alertAction, onClose = act.dismissAlert) }
                        }
                    }
                }
                // دکمه‌ی اتصال: قطرش با فضای مانده (۹۶ تا ۱۴۲dp)، انیمیشن‌ها و نسبت‌های داخلش همان است
                BoxWithConstraints(Modifier.fillMaxWidth().weight(.56f), contentAlignment = Alignment.Center) {
                    val d = ((maxHeight - D.S2) / OrbFootprint).coerceIn(96.dp, 142.dp)
                    Box(Modifier.guideTarget("connect")) {
                        OrbButton(ui, mc, enabled = ui.hasAccount && !ui.sheetOpen, diameter = d, onClick = act.toggle)
                    }
                }
                Column(
                    Modifier.fillMaxWidth().padding(start = D.PagePad, end = D.PagePad, top = gap, bottom = gap),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    AnimatedVisibility(on && ui.server != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        ui.server?.let { ConnectedCard(it, mc) }
                    }
                    if (!ui.hasAccount) {
                        if (ui.loading) Spinner(28.dp) else GoldButton("تلاش دوباره", Modifier.fillMaxWidth(), onClick = act.retry)
                    } else {
                        ModeRow(ui.mode, enabled = ui.vstate != VState.CONNECTING || ui.mode == KMode.GAME, modifier = Modifier.guideTarget("protocols"), onTap = act.tapMode, onLong = act.longMode)
                        ModeHint(ui.mode)
                        if (ui.mode == KMode.DNS) DnsCard(ui.dns, ui.vstate, act.dns)
                        StatsRow(ui)
                    }
                    ui.sub?.let { UsageStrip(it, Modifier.guideTarget("sub"), act.openAccount) }
                }
            }
        }
    }
}

/** «موقعیت شما : ایران» بالای نقشه؛ وقتی وصل است «IP شما : آلمان» (کشور IP خروجی) */
@Composable
private fun LocationChip(iso: String?, on: Boolean) {
    val c = if (on) K.Green else K.Gold
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.goldGlass(shape, tint = c)
            .padding(horizontal = D.S3, vertical = D.S1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(c, 6.dp)
        Spacer(Modifier.width(7.dp))
        T(tr(if (on) "IP شما" else "موقعیت شما") + " : " + (countryName(iso) ?: iso ?: "—"), size = 11.sp, color = K.Text, weight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.width(7.dp))
        Dot(c.copy(alpha = .5f), 4.dp)
    }
}

/** ستاره‌ی تک دور دکمه: آرام دور دکمه می‌چرخد و چشمک می‌زند؛ بزرگ‌ترها برق چهارپر دارند */
private class OrbStar(val orbit: Float, val start: Float, val size: Float, val twinkle: Float, val white: Boolean)

/** ستاره‌های ثابت (با بذر ثابت)، تا با هر بار ساخته شدن صفحه جایشان نپرد */
private val orbStars: List<OrbStar> = run {
    val rnd = java.util.Random(2077)
    List(26) {
        OrbStar(
            orbit = 1.55f + rnd.nextFloat() * rnd.nextFloat() * .55f,
            start = rnd.nextFloat() * 360f,
            size = .5f + rnd.nextFloat() * rnd.nextFloat() * 1.3f,
            twinkle = .8f + rnd.nextFloat() * 2f,
            white = rnd.nextFloat() < .5f,
        )
    }
}

/**
 * دکمه‌ی گرد اتصال (مثل طرح): پشتش پرتوهای نور طلایی آرام می‌چرخند و چند ستاره‌ی تک دورش می‌گردند؛
 * دورش حلقه‌ی نئونی طلایی با دو نور دنباله‌دار که دور آن می‌چرخند و یک حلقه‌ی درجه‌دار ظریف که برعکس می‌چرخد؛
 * خود دکمه شیشه‌ی مشکی با لبه‌ی طلایی ضخیم و درخشان است و وسطش لوگو، عنوان و یک خط توضیح.
 * موقع اتصال همه‌چیز تند می‌چرخد و نور سفیدی دور لبه می‌دود؛ وقتی وصل است موج‌های طلایی از دکمه بیرون می‌زند.
 * حالت گیمینگ (پایین‌تر: Chase، chaseScene، connectBlast، heartbeat): نورها و حلقه به رنگ گیمینگ تم در می‌آیند و روی حلقه دو آدمک نئونی
 * اندازه‌ی مورچه می‌دوند؛ یکی فرار می‌کند و دیگری با اسلحه دنبالش است. خاموش آرام راه می‌روند، در حال اتصال می‌دوند و
 * وقتی وصل است تیرانداز تیر می‌زند: هرچه دیتای بیشتری رد و بدل شود تندتر شلیک می‌کند، هرچه پینگ کمتر تیرها
 * تندتر می‌روند، و با هر نمونه‌ی تازه‌ی پینگ یک رگبار زیر پای فراری می‌رسد که از رویش می‌پرد.
 */
@Composable
fun OrbButton(ui: HomeUi, color: Color, enabled: Boolean, diameter: Dp = 138.dp, onClick: () -> Unit) {
    val on = ui.vstate == VState.ON
    val connecting = ui.vstate == VState.CONNECTING
    // سرعت چرخش نرم عوض می‌شود تا با تغییر وضعیت نپرد
    val speed by animateFloatAsState(if (connecting) 4.5f else if (on) 1.3f else .6f, tween(900), label = "spd")
    val game = ui.mode == KMode.GAME
    val gameA by animateFloatAsState(if (game) 1f else 0f, tween(500), label = "game")
    val gCon by animateFloatAsState(if (game && connecting) 1f else 0f, tween(350), label = "gcon")
    val gOn by animateFloatAsState(if (game && on) 1f else 0f, tween(700), label = "gon")
    val period by rememberUpdatedState(beatPeriod(ui.livePing))
    // شدت تپش گیمینگ: پایه از نوسان چند پینگ آخر، و هر نمونه‌ی تازه که با قبلی فرق دارد یک «ضربه» می‌دهد که آرام فروکش می‌کند
    val hist = ui.pingHist
    val calm = remember(hist, ui.livePing) { pingBase(hist, ui.livePing) }
    val kick = remember { Animatable(0f) }
    LaunchedEffect(hist.size, hist.lastOrNull()) {
        if (hist.size < 2) return@LaunchedEffect
        val k = (abs(hist[hist.size - 1] - hist[hist.size - 2]) / 20f).coerceAtMost(1f)
        if (k < .05f) return@LaunchedEffect
        kick.snapTo(max(kick.value, k))
        kick.animateTo(0f, tween(3000, easing = LinearOutSlowInEasing))
    }
    // لحظه‌ی وصل شدن در گیمینگ: یک بار انفجار نور
    val blast = remember { Animatable(1f) }
    LaunchedEffect(game && on) {
        if (game && on) { blast.snapTo(0f); blast.animateTo(1f, tween(1400, easing = LinearEasing)) }
    }
    var spin by remember { mutableFloatStateOf(0f) }
    var tick by remember { mutableFloatStateOf(0f) }
    var time by remember { mutableFloatStateOf(0f) }
    var beat by remember { mutableFloatStateOf(0f) }
    // تعقیب گیمینگ روی حلقه: تیرها از دیتا (چند تیر در ثانیه) و پینگ (سرعت تیر)
    val chase = remember { Chase() }
    val rate by rememberUpdatedState(fireRate(ui.rx + ui.tx))
    val flight by rememberUpdatedState(bulletFlight(hist.lastOrNull() ?: ui.livePing))
    // هر نمونه‌ی تازه‌ی پینگ: یک رگبار سه‌تیری زیر پای فراری که از رویش می‌پرد
    LaunchedEffect(hist.size, hist.lastOrNull()) {
        if (game && on && hist.isNotEmpty()) { chase.burst = 3; chase.jumpAt = time + flight - .07f }
    }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1e9f).coerceAtMost(.1f)
                    spin += dt * speed
                    tick += dt * speed
                    time += dt
                    beat += dt / period
                    // آدمک‌ها: خاموش راه می‌روند، در حال اتصال تند می‌دوند، وصل می‌دوند (پینگ که ناگهان عوض شود تندتر و نزدیک‌تر)
                    val run = if (gCon > .5f) 1.7f else if (gOn > .5f) .9f + .7f * kick.value else .32f
                    chase.step(dt, time, run, kick.value, gOn > .5f, rate, flight)
                }
                last = now
            }
        }
    }
    val lit by animateFloatAsState(if (on) 1f else if (connecting) .85f else .6f, tween(700), label = "lit")
    val onA by animateFloatAsState(if (on) 1f else 0f, tween(700), label = "on")
    val conA by animateFloatAsState(if (connecting) 1f else 0f, tween(400), label = "con")
    val halo = 78.dp
    // دکمه همیشه در اندازه‌ی طرح (OrbBase) کشیده می‌شود و فقط کل آن با diameter بزرگ و کوچک می‌شود، تا نسبت‌ها و
    // انیمیشن‌های داخلش دست نخورند. جایی که در صفحه می‌گیرد diameter × OrbFootprint است (حلقه و آدمک‌ها)؛
    // پرتوهای کم‌رنگ پشتش می‌توانند کمی بیرون از آن بیایند.
    val scale = diameter / OrbBase

    Box(
        Modifier.size(diameter * OrbFootprint).alpha(if (enabled) 1f else .55f).clickable(
            enabled = enabled,
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.requiredSize(OrbBase + halo).graphicsLayer { scaleX = scale; scaleY = scale }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = center
                val r = OrbBase.toPx() / 2
                val gold = K.Gold
                val breathe = .5f + .5f * sin(time * 2.1f)
                val deg = Math.toDegrees(spin.toDouble()).toFloat()
                val u = beat % 1f
                val env = beatEnv(u)
                // گیمینگ: پرتوها و هاله به رنگ گیمینگ می‌روند؛ amp شدت تپش از پینگ (۰.۲۵ آرام تا ۱.۳ خیلی ناآرام)
                val amp = (calm + .8f * kick.value).coerceIn(.25f, 1.3f)
                val rayC = lerp(gold, Game, .75f * gameA)
                val warm = lerp(K.Gold, Game, .8f * gameA)
                val pump = 1f + .7f * env * amp * gOn

                // ---- پرتوهای نور که آرام می‌چرخند
                rotate(deg * .12f, c) {
                    for (i in 0 until 16) {
                        rotate(i * 22.5f, c) {
                            val len = r * (if (i % 2 == 1) 1.5f else 1.78f)
                            val ray = Path().apply {
                                moveTo(c.x - 1.5.dp.toPx(), c.y)
                                lineTo(c.x - r * .13f, c.y - len)
                                lineTo(c.x + r * .13f, c.y - len)
                                lineTo(c.x + 1.5.dp.toPx(), c.y)
                                close()
                            }
                            val a = (.10f + .08f * lit) * (.7f + .3f * breathe) * pump
                            drawPath(ray, Brush.verticalGradient(0f to rayC.copy(alpha = 0f), .45f to rayC.copy(alpha = a), 1f to rayC.copy(alpha = 0f), startY = c.y - len, endY = c.y))
                        }
                    }
                }
                // ---- هاله‌ی گرم پشت دکمه
                drawCircle(
                    Brush.radialGradient(
                        0f to warm.copy(alpha = 0f), .45f to warm.copy(alpha = ((.30f + .2f * lit) * (.75f + .25f * breathe) * pump).coerceAtMost(1f)),
                        .72f to warm.copy(alpha = .07f * pump), 1f to Color.Transparent,
                        center = c, radius = r * 2f,
                    ),
                    r * 2f, c,
                )
                // ---- ستاره‌های تک
                orbStars.forEach { s ->
                    val rad = Math.toRadians((s.start + deg * .07f / s.orbit).toDouble())
                    val o = Offset(c.x + r * s.orbit * cos(rad).toFloat(), c.y + r * s.orbit * sin(rad).toFloat() * .8f)
                    val tw = .5f + .5f * sin(time * s.twinkle + s.start)
                    val col = if (s.white) Color.White else lerp(K.GoldLight, GameLight, gameA)
                    val sz = s.size.dp.toPx()
                    val a = .35f + .65f * tw
                    drawCircle(col.copy(alpha = .16f * a), sz * 4f, o)
                    drawCircle(col.copy(alpha = a), sz * .8f, o)
                    if (s.size > 1.1f) {
                        val l = sz * 6f
                        drawLine(col.copy(alpha = .7f * a), Offset(o.x - l, o.y), Offset(o.x + l, o.y), .7.dp.toPx(), StrokeCap.Round)
                        drawLine(col.copy(alpha = .7f * a), Offset(o.x, o.y - l), Offset(o.x, o.y + l), .7.dp.toPx(), StrokeCap.Round)
                    }
                }

                // ---- حلقه‌ی نئونی بیرونی با دو نور دنباله‌دار (گیمینگ: نئون گیمینگ تم، بی‌نورِ دنباله‌دار، جای دویدن آدمک‌ها)
                val r2 = r * 1.42f
                val goldA = 1f - gameA
                if (goldA > .01f) neonRing(gold, c, r2, 1.2.dp.toPx(), strength = (.45f + .25f * lit) * goldA)
                if (gameA > .01f) rgbRing(c, r2, 1.2.dp.toPx(), gameA, -deg * .6f)
                if (goldA > .01f) {
                    neonComet(K.GoldLight, deg, 80f, c, r2, 1.6.dp.toPx(), goldA)
                    neonComet(K.GoldLight, deg + 180f, 80f, c, r2, 1.6.dp.toPx(), goldA)
                }
                // ---- حلقه‌ی درجه‌دار ظریف که برعکس می‌چرخد
                rotate(-Math.toDegrees(tick.toDouble()).toFloat() * .35f, c) {
                    val r1 = r * 1.2f
                    for (i in 0 until 72) {
                        val a = Math.toRadians(i * 5.0)
                        val major = i % 6 == 0
                        val h = (if (major) 5f else 3f).dp.toPx()
                        val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
                        // گیمینگ: هر درجه رنگ همان جای حلقه‌ی نئونی
                        val base = if (major) K.GoldLight else gold
                        val tc = if (gameA > .01f) lerp(base, rgbAt(i / 72f), .85f * gameA) else base
                        drawLine(
                            tc.copy(alpha = if (major) .85f else .4f),
                            Offset(c.x + (r1 - h) * ca, c.y + (r1 - h) * sa), Offset(c.x + (r1 + h) * ca, c.y + (r1 + h) * sa),
                            (if (major) 2f else 1.1f).dp.toPx(), StrokeCap.Round,
                        )
                    }
                }
                if (gameA > .01f) chaseScene(chase, c, r2, time, gameA, gOn)

                // ---- دکمه: شیشه‌ی مشکی با لبه‌ی طلایی ضخیم و درخشان (گیمینگ: شیشه‌ی تیره به رنگ گیمینگ و لبه‌ی نئونی چرخان)
                val glowA = (.35f + .25f * lit * breathe) * (1f + .4f * env * amp * gOn)
                drawCircle(Brush.radialGradient(listOf(lerp(K.Gold, Game, .6f * gameA).copy(alpha = glowA.coerceAtMost(1f)), Color.Transparent), c, r * 1.32f), r * 1.32f, c)
                drawCircle(lerp(K.Ink, K.GoldDeep, .06f), r + 4.dp.toPx(), c)
                val glass = listOf(
                    lerp(lerp(K.Ink, K.GoldDeep, .24f), lerp(K.Ink, Game, .2f), gameA), lerp(lerp(K.Ink, K.GoldDeep, .08f), lerp(K.Ink, Game, .08f), gameA), K.Ink,
                )
                drawCircle(Brush.radialGradient(glass, Offset(c.x, c.y - r * .3f), r * 1.1f), r, c)
                if (goldA > .01f) drawCircle(
                    Brush.verticalGradient(listOf(K.Shine, gold, K.GoldDeep, K.GoldLight), startY = c.y - r, endY = c.y + r),
                    r + 1.dp.toPx(), c, alpha = goldA, style = Stroke(5.dp.toPx()),
                )
                if (gameA > .01f) {
                    // لبه‌ی نئونی: با چرخش دکمه می‌چرخد (موقع اتصال تند) و وصل با هر ضربان پینگ پررنگ‌تر می‌درخشد
                    val rgb = Brush.sweepGradient(GameRgb, c)
                    val glowUp = 1f + env * gOn * (.6f + .5f * amp)
                    rotate(deg * 1.6f, c) {
                        drawCircle(rgb, r + 1.dp.toPx(), c, alpha = (.16f * glowUp * gameA).coerceAtMost(1f), style = Stroke(15.dp.toPx()))
                        drawCircle(rgb, r + 1.dp.toPx(), c, alpha = (.32f * glowUp * gameA).coerceAtMost(1f), style = Stroke(9.dp.toPx()))
                        drawCircle(rgb, r + 1.dp.toPx(), c, alpha = gameA, style = Stroke(5.dp.toPx()))
                    }
                    drawCircle(Color.White.copy(alpha = .45f * gameA), r + 1.dp.toPx(), c, style = Stroke(1.dp.toPx()))
                }
                drawCircle(lerp(K.GoldLight, GameCyan, gameA).copy(alpha = .5f), r - 6.dp.toPx(), c, style = Stroke(1.dp.toPx()))
                drawOval(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = .20f), Color.Transparent), startY = c.y - r, endY = c.y - r * .1f),
                    Offset(c.x - r * .7f, c.y - r * .9f), Size(r * 1.4f, r * .8f),
                )
                // در حال اتصال: نور سفید دور لبه می‌دود
                if (conA > .01f) {
                    val rr = r + 1.dp.toPx()
                    drawArc(
                        lerp(K.Shine, GameLight, gCon).copy(alpha = conA), time * 344f, 69f, false,
                        Offset(c.x - rr, c.y - rr), Size(rr * 2, rr * 2), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
                // وصل: دو موج طلایی که از دکمه بیرون می‌زنند
                if (onA > .01f) for (k in 0 until 2) {
                    val p = ((time / 2.6f) + k * .5f) % 1f
                    drawCircle(K.GoldLight.copy(alpha = (1f - p) * .55f * onA * (1f - gOn)), r * (1.05f + p * .75f), c, style = Stroke(1.6.dp.toPx()))
                }
                if (gOn > .01f) heartbeat(env * (.6f + .5f * amp), gOn, c, r)
                if (game && blast.value < 1f) connectBlast(blast.value, c, r)
            }
            // ---- وسط دکمه: لوگو، عنوان و یک خط توضیح، همه وسط‌چین و در اندازه‌ی دکمه
            Column(Modifier.width(OrbBase * .74f), horizontalAlignment = Alignment.CenterHorizontally) {
                Logo3D(30.dp, animate = on)
                Spacer(Modifier.height(3.dp))
                val dnsOn = ui.mode == KMode.DNS
                val (title, sub) = when (ui.vstate) {
                    VState.ON -> (if (dnsOn) "DNS فعال" else "متصل") to
                        (if (dnsOn) (ui.dns.activeRoute ?: "DNS") else elapsed(ui.elapsedSec))
                    VState.CONNECTING -> "در حال اتصال" to "لمس برای لغو"
                    VState.OFF -> "اتصال" to ui.mode.title
                }
                val tc = if (on) K.Green else lerp(K.Gold, GameLight, gameA)
                T(title, size = if (ui.vstate == VState.OFF) 22.sp else 16.sp, color = tc, weight = FontWeight.Black, shadow = glow(tc, 16f), maxLines = 1)
                val num = on && !dnsOn
                T(sub, size = if (num) 14.sp else 11.sp, color = if (num) Color.White else K.Text.copy(alpha = .85f), font = if (num) Poppins else Vazir,
                    weight = FontWeight.Bold, ltr = num, maxLines = 1)
            }
        }
    }
}

/** قطر دکمه‌ی اتصال در طرح اصلی؛ اندازه‌ی واقعی با diameter از همین مقیاس می‌شود */
val OrbBase = 138.dp

/** جای عمودی دکمه نسبت به قطرش (حلقه‌ی بیرونی ۱.۴۲ برابر و آدمک‌های گیمینگ روی آن، تا روی کارت‌ها نیفتند) */
const val OrbFootprint = 1.7f

// ------------------------------------------------------------------ لایه‌ی گیمینگ دکمه‌ی اتصال

/** رنگ گیمینگ همان تم (یاقوت سرخ، سرخ پاستلی یا سرخابی) و نسخه‌ی روشنش به سمت gameAlt (برای آدمک‌ها و سرِ نورها) */
private val Game get() = modeColor(KMode.GAME)
private val GameLight get() = lerp(lerp(Game, ThemeState.current.gameAlt, .3f), Color.White, .15f)

/**
 * رنگ‌های نئونی حالت گیمینگ (لبه‌ی دکمه و حلقه‌ها): فقط رنگ گیمینگ تم، نئون دومِ هم‌خانواده‌اش (gameAlt) و نسخه‌ی روشنش،
 * تا کنار لوگو و تیرها یکدست بماند
 */
private val GameCyan get() = ThemeState.current.gameAlt
private val GameRgb get() = listOf(Game, GameCyan, Game, GameLight, Game)

/** رنگ نئونی گیمینگ در جای f (۰..۱) دور حلقه */
private fun rgbAt(f: Float): Color {
    val p = GameRgb
    val x = (((f % 1f) + 1f) % 1f) * (p.size - 1)
    val i = x.toInt().coerceAtMost(p.size - 2)
    return lerp(p[i], p[i + 1], x - i)
}

/** حلقه‌ی نئونی گیمینگ (رنگ گیمینگ، gameAlt و نسخه‌ی روشن) که rot درجه چرخیده؛ w ضخامت خط اصلی */
private fun DrawScope.rgbRing(c: Offset, r: Float, w: Float, a: Float, rot: Float) {
    val b = Brush.sweepGradient(GameRgb, c)
    rotate(rot, c) {
        for ((m, al) in listOf(4.5f to .07f, 3f to .15f, 1.9f to .32f)) drawCircle(b, r, c, alpha = al * a, style = Stroke(w * m))
        drawCircle(b, r, c, alpha = a, style = Stroke(w))
    }
    drawCircle(Color.White.copy(alpha = .4f * a), r, c, style = Stroke(w * .35f))
}

/** دوره‌ی هر ضربان (ثانیه) از پینگ: پینگ کم آرام و منظم، پینگ بالا تند و نگران */
private fun beatPeriod(ping: Long?): Float = if (ping == null || ping < 0) 1.15f else (1.25f - ping / 400f).coerceIn(.55f, 1.15f)

/** شدت تپش در یک دوره (۰..۱): «تاپ» قوی و پشتش «توپ» ضعیف‌تر، مثل ضربان قلب */
private fun beatEnv(u: Float): Float {
    fun bump(c: Float, w: Float) = (1f - abs(u - c) / w).coerceIn(0f, 1f).let { it * it }
    return max(bump(.05f, .06f), .6f * bump(.22f, .07f))
}

/** نرم شدن آغاز و پایان (۰..۱) */
private fun smooth(x: Float) = x * x * (3f - 2f * x)

/** یک دور کامل (رادیان) */
private const val TAU = (2 * PI).toFloat()

/** شدت پایه‌ی تپش گیمینگ از پینگ (حدود ۰.۳ تا ۰.۸): نوسان چند نمونه‌ی آخر و خود عدد پینگ */
private fun pingBase(hist: List<Long>, ping: Long?): Float {
    val t = hist.takeLast(6)
    val jit = if (t.size < 2) 0f else t.zipWithNext { a, b -> abs(a - b).toFloat() }.average().toFloat()
    val lvl = (((ping ?: 0L) - 40L).coerceAtLeast(0L) / 300f).coerceAtMost(.2f)
    return .3f + (jit / 30f).coerceAtMost(.3f) + lvl
}

/** عدد شبه‌تصادفی ثابت (۰..۱) */
private fun hash(a: Int, b: Int): Float {
    var x = a * 374761393 + b * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0xFFFFFF) / 16777215f
}

/** چند تیر در ثانیه از دیتای رد و بدل شده (بایت بر ثانیه): بی‌دیتا نیم تیر، چند مگ در ثانیه چهار تیر */
private fun fireRate(bps: Long): Float = .5f + 3.5f * (kotlin.math.log10(1f + bps / 8_000f) / 2.5f).coerceIn(0f, 1f)

/** زمان پرواز هر تیر (ثانیه) از پینگ: پینگ کم تیر تند، پینگ بالا تیر کند */
private fun bulletFlight(ping: Long?): Float = (.28f + (ping ?: 60L) / 280f).coerceIn(.3f, 1.1f)

/** رنگ تیرها (از تم، جدا از رنگ آدمک‌ها) و سرِ روشنشان */
private val Tracer get() = ThemeState.current.tracer
private val TracerHot get() = lerp(Tracer, Color.White, .5f)

/**
 * تعقیب گیمینگ روی حلقه: جای دو آدمک (رادیان، صفر بالا و ساعت‌گرد)، فاز قدم‌ها، تیرهای در راه و پرش فراری.
 * هر فریم یک بار step صدا می‌شود؛ کشیدنش با chaseScene است.
 */
private class Chase {
    /** یک تیر: کی و از کجا شلیک شد، کجا به زمین حلقه می‌خورد و چند ثانیه در راه است */
    class Shot(val t0: Float, val a0: Float, val a1: Float, val dur: Float)

    var pos = 0f
    var speed = .3f
    var stride = 0f
    var gap = .8f
    var lastShot = -9f
    var jumpAt = -9f
    var burst = 0
    private var fireAcc = 0f
    private var burstAcc = 0f
    private var count = 0
    val shots = ArrayList<Shot>()

    /** target: سرعت دویدن (رادیان بر ثانیه)؛ closer: تیرانداز چقدر نزدیک‌تر شود (۰..۱)؛ firing: وصل است و تیر می‌زند */
    fun step(dt: Float, time: Float, target: Float, closer: Float, firing: Boolean, rate: Float, flight: Float) {
        speed += (target - speed) * min(1f, dt * 2.5f)
        pos = (pos + dt * speed) % TAU
        stride += dt * (speed * 14f + 4f)
        gap += (.8f + .08f * sin(time * .6f) - .22f * closer - gap) * min(1f, dt * 2f)
        if (firing) {
            fireAcc += dt * rate
            if (fireAcc >= 1f) { fireAcc -= 1f; fire(time, flight, aimed = false) }
            if (burst > 0) {
                burstAcc += dt
                if (burstAcc >= .09f) { burstAcc = 0f; burst--; fire(time, flight, aimed = true) }
            }
        } else {
            fireAcc = 0f; burst = 0
        }
        shots.removeAll { time - it.t0 > it.dur + .3f }
    }

    /** تیرهای معمولی کمی پشت پای فراری به زمین می‌خورند؛ تیرهای رگبار پینگ درست زیر پایش (او می‌پرد) */
    private fun fire(time: Float, flight: Float, aimed: Boolean) {
        val miss = if (aimed) 0f else .06f + .1f * hash(count++, 3)
        shots.add(Shot(time, pos, pos + gap + speed * flight - miss, flight))
        lastShot = time
    }
}

/** جهت «بالا» (بیرون حلقه) و «جلو» (ساعت‌گرد) در زاویه‌ی th */
private fun upAt(th: Float) = Offset(sin(th), -cos(th))
private fun fwdAt(th: Float) = Offset(cos(th), sin(th))

/**
 * یک آدمک نئونی خطی روی حلقه (پا روی حلقه، سر رو به بیرون، رو به جلوی حرکت): run ۰ راه رفتن تا ۱ دویدن؛ lift: پرش (px)؛
 * gun: اسلحه در دست (تیرانداز) با لگد شلیک recoil (۰..۱). سر لوله‌ی اسلحه را برمی‌گرداند.
 */
private fun DrawScope.stickman(
    c: Offset, rr: Float, th: Float, h: Float, step: Float, run: Float, lift: Float, gun: Boolean, recoil: Float, a: Float,
): Offset {
    val up = upAt(th)
    val fw = fwdAt(th)
    val base = c + up * rr
    fun w(p: Offset) = base + fw * (p.x * h) + up * (p.y * h)
    fun dn(ang: Float) = Offset(sin(ang), -cos(ang))
    val jump = (lift / (.55f * h)).coerceIn(0f, 1f)
    val lean = .08f + .22f * run
    val spine = Offset(sin(lean), cos(lean))
    val hip = Offset(0f, .43f + .035f * run * cos(2 * step) + lift / h)
    val neck = hip + spine * .3f
    val head = neck + spine * .17f + Offset(.02f, 0f)
    val shoulder = hip + spine * .27f
    val body = Path()
    fun seg(p0: Offset, p1: Offset, p2: Offset? = null) {
        body.moveTo(w(p0).x, w(p0).y)
        body.lineTo(w(p1).x, w(p1).y)
        if (p2 != null) body.lineTo(w(p2).x, w(p2).y)
    }
    seg(hip, neck)
    for (k in 0..1) {
        val p = step + k * PI.toFloat()
        val thigh = (.25f + .45f * run) * sin(p) + .5f * jump
        val bend = .15f + (.25f + run) * max(0f, cos(p)) + 1.1f * jump
        val knee = hip + dn(thigh) * .23f
        seg(hip, knee, knee + dn(thigh - bend) * .23f)
    }
    var muzzle = Offset.Zero
    val gunPath = Path()
    if (gun) {
        val grip = shoulder + Offset(.2f - .06f * recoil, -.04f)
        val fore = grip + Offset(.13f, .01f)
        seg(shoulder, shoulder + Offset(.06f, -.11f), grip)
        seg(shoulder, shoulder + Offset(.12f, -.08f), fore)
        val stock = grip + Offset(-.11f, .02f)
        val tip = grip + Offset(.36f, .035f)
        muzzle = w(tip)
        gunPath.moveTo(w(stock).x, w(stock).y); gunPath.lineTo(muzzle.x, muzzle.y)
        val mag = grip + Offset(.06f, 0f)
        gunPath.moveTo(w(mag).x, w(mag).y); w(mag + Offset(.025f, -.09f)).let { gunPath.lineTo(it.x, it.y) }
    } else {
        for (k in 0..1) {
            val q = step + k * PI.toFloat()
            val upper = -(.2f + .6f * run) * sin(q) - .4f * jump
            val elbow = shoulder + dn(upper) * .19f
            seg(shoulder, elbow, elbow + dn(upper + .5f + run) * .17f)
        }
    }
    val hc = w(head)
    val hr = .13f * h
    drawPath(body, Game.copy(alpha = .16f * a), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(Game.copy(alpha = .2f * a), hr + 2.dp.toPx(), hc)
    drawPath(body, GameLight.copy(alpha = a), style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(body, Color.White.copy(alpha = .5f * a), style = Stroke(.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(GameLight.copy(alpha = a), hr, hc)
    drawCircle(Color.White.copy(alpha = .55f * a), hr * .5f, hc)
    if (gun) {
        drawPath(gunPath, Color.White.copy(alpha = .22f * a), style = Stroke(3.4.dp.toPx(), cap = StrokeCap.Round))
        drawPath(gunPath, lerp(K.Sub, Color.White, .7f).copy(alpha = a), style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round))
    }
    return muzzle
}

/**
 * حالت گیمینگ: دو آدمک نئونی اندازه‌ی مورچه روی حلقه؛ یکی فرار می‌کند و دیگری با اسلحه دنبالش می‌دود و تیر
 * می‌زند. تیرها دنباله دارند، کمی پشت پای فراری به زمین حلقه می‌خورند و جرقه می‌زنند؛ تیرهای رگبار پینگ زیر پای او
 * می‌رسند و او از رویشان می‌پرد. a: پیدا بودن؛ firing: تیر زدن (وصل)
 */
private fun DrawScope.chaseScene(ch: Chase, c: Offset, ring: Float, time: Float, a: Float, firing: Float) {
    val h = 18.dp.toPx()
    val rr = ring + 1.dp.toPx()
    val run = ((ch.speed - .3f) / .6f).coerceIn(0f, 1f)
    val muzzleAng = .62f * h / rr
    val gunH = .74f * h

    // تیرها و جرقه‌ی برخوردشان (پشت آدمک‌ها)
    for (s in ch.shots) {
        val t = time - s.t0
        val span = s.a1 - s.a0 - muzzleAng
        if (t <= s.dur) {
            val p = t / s.dur
            val tailP = (p - (12.dp.toPx() / rr) / max(.05f, span)).coerceAtLeast(0f)
            fun at(q: Float): Offset {
                val th = s.a0 + muzzleAng + span * q
                return c + upAt(th) * (rr + gunH * (1f - q * q))
            }
            val head = at(p)
            val tail = at(tailP)
            drawLine(Tracer.copy(alpha = .35f * a), tail, head, 3.4.dp.toPx(), StrokeCap.Round)
            drawLine(TracerHot.copy(alpha = a), tail, head, 1.4.dp.toPx(), StrokeCap.Round)
            drawCircle(Color.White.copy(alpha = a), 1.1.dp.toPx(), head)
        } else {
            val k = ((t - s.dur) / .3f).coerceIn(0f, 1f)
            val hit = c + upAt(s.a1) * rr
            val up = upAt(s.a1)
            val back = fwdAt(s.a1) * -1f
            drawCircle(Tracer.copy(alpha = .5f * (1f - k) * a), (1.5f + 3f * k).dp.toPx(), hit)
            for (i in 0 until 5) {
                val ang = -.9f + .45f * i
                val d = up * cos(ang) + back * sin(ang)
                val len = (2f + 6f * k).dp.toPx() * (.6f + .4f * hash(i, s.t0.toBits()))
                drawLine(TracerHot.copy(alpha = (1f - k) * a), hit + d * (len * .4f), hit + d * len, 1.dp.toPx(), StrokeCap.Round)
            }
        }
    }

    // فراری جلوتر (با پرش از روی رگبار) و تیرانداز پشت سرش با اسلحه
    val jt = time - ch.jumpAt
    val lift = if (jt in 0f..0.5f) .55f * h * sin(jt / .5f * PI.toFloat()) else 0f
    stickman(c, rr, ch.pos + ch.gap, h, ch.stride + 1.3f, run, lift, gun = false, recoil = 0f, a = a)
    val since = time - ch.lastShot
    val recoil = if (firing > .5f) kotlin.math.exp(-since * 22f) else 0f
    val muzzle = stickman(c, rr, ch.pos, h, ch.stride, run, 0f, gun = true, recoil = recoil, a = a)

    // برق سر لوله
    if (firing > .5f && since < .08f) {
        val f = 1f - since / .08f
        val fw = fwdAt(ch.pos)
        val up = upAt(ch.pos)
        drawCircle(Tracer.copy(alpha = .45f * f * a), 4.dp.toPx() * f, muzzle)
        for (d in listOf(fw, fw * .8f + up * .5f, fw * .8f - up * .5f)) {
            drawLine(TracerHot.copy(alpha = f * a), muzzle, muzzle + d * (5.dp.toPx() * f), 1.1.dp.toPx(), StrokeCap.Round)
        }
        drawCircle(Color.White.copy(alpha = f * a), 1.2.dp.toPx(), muzzle)
    }
}

/** لحظه‌ی وصل شدن در گیمینگ (p: ۰ تا ۱): برق گیمینگ روی دکمه که آرام محو می‌شود */
private fun DrawScope.connectBlast(p: Float, c: Offset, r: Float) {
    val q = 1f - p
    drawCircle(Brush.radialGradient(listOf(GameLight.copy(alpha = .5f * q * q), Color.Transparent), c, r * 1.6f), r * 1.6f, c)
}

/** لبه‌ی دکمه در گیمینگ وصل با هر ضربان می‌درخشد (e: شدت همان لحظه) */
private fun DrawScope.heartbeat(e: Float, a: Float, c: Offset, r: Float) {
    for ((w, al) in listOf(12f to .07f, 6f to .16f, 2.6f to .45f)) drawCircle(Game.copy(alpha = (al * e * a).coerceAtMost(1f)), r + 2.dp.toPx(), c, style = Stroke(w.dp.toPx()))
}

private fun elapsed(s: Long?): String {
    val v = s ?: 0
    return String.format(java.util.Locale.US, "%02d:%02d:%02d", v / 3600, v / 60 % 60, v % 60)
}

/** کارت سرور وصل‌شده (مثل طرح): پرچم، کشور و شهر، پروتکل و «اتصال پایدار» */
@Composable
private fun ConnectedCard(s: ServerChipUi, c: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(D.RM)
    Row(
        modifier.fillMaxWidth().height(if (LocalCompact.current) D.HomeRowS else D.HomeRow).goldGlass(shape, selected = true)
            .padding(horizontal = D.S3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(s.flag, size = 18.sp, weight = FontWeight.Normal)
        Spacer(Modifier.width(D.S2))
        Column(Modifier.weight(1f)) {
            T(s.country, size = 13.sp, color = K.Text, weight = FontWeight.Black, maxLines = 1)
            T(listOfNotNull(s.city, protoTitle(s.proto)).joinToString(" · "), size = 10.sp, color = K.Sub, weight = FontWeight.Bold, maxLines = 1)
        }
        val sh = RoundedCornerShape(50)
        Row(
            Modifier.clip(sh).background(K.Green.copy(alpha = .1f)).border(1.dp, K.Green.copy(alpha = .5f), sh).padding(horizontal = D.S2, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(K.Green, 6.dp)
            Spacer(Modifier.width(D.S1))
            T("اتصال پایدار", size = 10.sp, color = K.Green, weight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** نوار کوچک «رمزنگاری شده | اتصال پایدار و بدون قطعی» وقتی وصل است */
@Composable
private fun SecureStrip(c: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().goldGlass(shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Dot(K.Green, 7.dp)
        Spacer(Modifier.width(6.dp))
        T("رمزنگاری شده", size = 11.sp, color = K.Text, weight = FontWeight.Bold, maxLines = 1)
        Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(14.dp).background(c.copy(alpha = .35f)))
        T("اتصال پایدار و بدون قطعی", size = 11.sp, color = K.Sub, weight = FontWeight.Bold, maxLines = 1)
    }
}

/** یک کارت شیشه‌ای با سه خانه‌ی هم‌اندازه: پینگ (با نمودار زنده)، دانلود، آپلود */
@Composable
private fun StatsRow(ui: HomeUi) {
    val on = ui.vstate == VState.ON
    val shape = RoundedCornerShape(D.RM)
    Row(
        Modifier.fillMaxWidth().height(if (LocalCompact.current) D.HomeStatS else D.HomeStat).goldGlass(shape).padding(horizontal = D.S1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1.25f), horizontalAlignment = Alignment.CenterHorizontally) {
            T("پینگ", size = 11.sp, color = K.Text, weight = FontWeight.Bold)
            val p = if (on) ui.livePing else null
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(p?.toString() ?: "--", size = 14.sp, color = if (p != null) pingColor(p) else K.Sub, font = Poppins, weight = FontWeight.Bold, ltr = true)
                    Spacer(Modifier.width(3.dp))
                    T("ms", size = 10.sp, color = K.Sub, font = Poppins, weight = FontWeight.SemiBold, ltr = true)
                    Spacer(Modifier.width(6.dp))
                    PingPulse(if (on) ui.pingHist else emptyList(), p, Modifier.width(44.dp).height(18.dp))
                }
            }
        }
        StatDivider()
        SpeedCell("down", "دانلود", K.Accent, if (on) ui.rx else null, Modifier.weight(1f))
        StatDivider()
        SpeedCell("up", "آپلود", K.Gold, if (on) ui.tx else null, Modifier.weight(1f))
    }
}

@Composable
private fun StatDivider() {
    Box(Modifier.width(1.dp).height(28.dp).background(Brush.verticalGradient(listOf(Color.Transparent, K.Gold.copy(alpha = .45f), Color.Transparent))))
}

@Composable
private fun SpeedCell(icon: String, label: String, tint: Color, bytesPerSec: Long?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(16.dp).clip(CircleShape).background(tint.copy(alpha = .12f)).border(1.dp, tint.copy(alpha = .8f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { MiniIcon(icon, tint, 9.dp) }
            Spacer(Modifier.width(D.S1))
            T(label, size = 11.sp, color = K.Text, weight = FontWeight.Bold)
        }
        val v = bytesPerSec?.let { String.format(java.util.Locale.US, "%.1f", it * 8 / 1_000_000.0) } ?: "--"
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.Bottom) {
                T(v, size = 14.sp, color = K.Text, font = Poppins, weight = FontWeight.Bold, ltr = true)
                Spacer(Modifier.width(3.dp))
                T("Mbps", size = 9.5.sp, color = K.Sub, font = Poppins, weight = FontWeight.SemiBold, ltr = true, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

/** شکل یک ضربان نوار قلب (u = ۰..۱ از دوره، خروجی نسبت به خط پایه): برآمدگی کوچک، جهش بلند، فرورفتگی، موج آرام */
private val ecgBeat: List<Pair<Float, Float>> = buildList {
    for (i in 0..4) add(i * .025f to .12f * sin(i / 4f * PI).toFloat())
    add(.13f to -.12f); add(.16f to 1f); add(.20f to -.35f); add(.24f to 0f); add(.30f to 0f)
    for (i in 1..7) add(.30f + i * .02f to .22f * sin(i / 7f * PI).toFloat())
    add(1f to 0f)
}

/**
 * ضربان زنده‌ی پینگ (کنار عدد پینگ): نوار قلبی که از راست می‌آید و به چپ می‌رود، با ریتم پینگ (پینگ کم آرام،
 * پینگ بالا تند) و به رنگ کیفیت پینگ. با هر ضربان یک هاله از قله‌اش بیرون می‌زند و با هر نمونه‌ی تازه‌ی پینگ
 * (هر چند ثانیه) سرِ نمودار یک «شلیک» کوچک می‌کند. خط پایه همان پینگ‌های اخیر است (بالا و پایین رفتن پینگ).
 * وقتی وصل نیست: یک ضربان ثابت کم‌رنگ، بدون انیمیشن.
 */
@Composable
private fun PingPulse(values: List<Long>, ping: Long?, modifier: Modifier) {
    val live = values.size >= 2
    val col by animateColorAsState(if (ping != null) pingColor(ping) else K.Gold, tween(600), label = "pp")
    val period by rememberUpdatedState(beatPeriod(ping))
    var beat by remember { mutableFloatStateOf(0f) }
    var time by remember { mutableFloatStateOf(0f) }
    var shotAt by remember { mutableFloatStateOf(-9f) }
    // خط پایه با هر نمونه‌ی تازه نرم به شکل تازه می‌رود
    var from by remember { mutableStateOf(values) }
    var to by remember { mutableStateOf(values) }
    val morph = remember { Animatable(1f) }
    LaunchedEffect(values) {
        if (values == to) return@LaunchedEffect
        from = to; to = values
        if (live) shotAt = time
        morph.snapTo(0f)
        morph.animateTo(1f, tween(700))
    }
    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1e9f).coerceAtMost(.1f)
                    time += dt
                    beat += dt / period
                }
                last = now
            }
        }
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val y0 = h * .74f
        val amp = h * .62f
        if (!live) {
            val path = Path()
            ecgBeat.forEachIndexed { i, (u, v) -> val x = w * (.1f + .8f * u); val y = y0 - amp * v; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            drawPath(path, K.Gold.copy(alpha = .25f), style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            return@Canvas
        }
        // خط پایه از پینگ‌های اخیر (کم‌دامنه تا ضربان خوانا بماند)
        fun base(vs: List<Long>, f: Float): Float {
            if (vs.size < 2) return 0f
            val lo = vs.min().toFloat()
            val span = (vs.max() - lo).coerceAtLeast(10f)
            val pos = f * (vs.size - 1)
            val i = pos.toInt().coerceAtMost(vs.size - 2)
            val v = vs[i] + (vs[i + 1] - vs[i]) * (pos - i)
            return ((v - lo) / span - .5f) * h * .2f
        }
        val m = morph.value
        fun yAt(x: Float, v: Float): Float {
            val f = (x / w).coerceIn(0f, 1f)
            return y0 - (base(from, f) * (1f - m) + base(to, f) * m) - amp * v
        }
        // فاز در هر x: دو ضربان در پهنای نمودار؛ لبه‌ی راست «الان» است
        val beats = 2f
        val p0 = beat
        val path = Path()
        var first = true
        var k = kotlin.math.floor(p0).toInt()
        while (k <= p0 + beats + 1) {
            for ((u, v) in ecgBeat) {
                val ph = k + u
                if (ph < p0 - .05f || ph > p0 + beats + .05f) continue
                val x = (ph - p0) / beats * w
                val y = yAt(x, v)
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            }
            k++
        }
        val trace = Brush.horizontalGradient(listOf(Color.Transparent, col.copy(alpha = .55f), col), 0f, w)
        clipRect(0f, -h, w, h * 2) {
            drawPath(path, Brush.horizontalGradient(listOf(Color.Transparent, col.copy(alpha = .22f)), 0f, w), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, trace, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // سرِ نمودار (لبه‌ی راست، الان)
        val uh = (p0 + beats) % 1f
        val vh = ecgAt(uh)
        val head = Offset(w, yAt(w, vh))
        drawCircle(col.copy(alpha = .25f), 4.dp.toPx(), head)
        drawCircle(Color.White.copy(alpha = .9f), 1.5.dp.toPx(), head)
        // هاله‌ی هر ضربان: از قله بیرون می‌زند و با آن به چپ می‌رود
        val since = ((uh - .16f) % 1f + 1f) % 1f
        if (since < .6f) {
            val q = since / .6f
            val x = w - since / beats * w
            drawCircle(col.copy(alpha = (1f - q) * .7f), (2f + 9f * q).dp.toPx(), Offset(x, yAt(x, 1f)), style = Stroke((1.4f - .8f * q).dp.toPx()))
        }
        // «شلیک» با هر نمونه‌ی تازه‌ی پینگ: حلقه‌ی بزرگ‌تر از سرِ نمودار و یک گلوله‌ی نور که به راست می‌پرد
        val st = time - shotAt
        if (st in 0f..0.7f) {
            val q = st / .7f
            drawCircle(col.copy(alpha = (1f - q) * .8f), (3f + 13f * q).dp.toPx(), head, style = Stroke((1.8f - 1.2f * q).dp.toPx()))
            val b = Offset(head.x + 14.dp.toPx() * smooth(q), head.y)
            drawCircle(col.copy(alpha = (1f - q) * .35f), 3.dp.toPx(), b)
            drawCircle(Color.White.copy(alpha = 1f - q), 1.2.dp.toPx(), b)
        }
    }
}

/** مقدار نوار قلب در فاز u (بین نقطه‌های ecgBeat خطی) */
private fun ecgAt(u: Float): Float {
    for (i in 1 until ecgBeat.size) {
        val (u1, v1) = ecgBeat[i]
        if (u <= u1) {
            val (u0, v0) = ecgBeat[i - 1]
            return v0 + (v1 - v0) * ((u - u0) / (u1 - u0).coerceAtLeast(1e-4f))
        }
    }
    return 0f
}

/** نوار باریک وضعیت اشتراک: گیگ باقی‌مانده · روز باقی‌مانده · وضعیت (فقط عدد)، و زیرش نوار حجم مصرف‌شده */
@Composable
fun UsageStrip(s: SubUi, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val active = isActive(s.status)
    val shape = RoundedCornerShape(D.RM)
    Column(
        modifier.fillMaxWidth().goldGlass(shape)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth().height(if (LocalCompact.current) D.HomeUsageS else D.HomeUsage).padding(horizontal = D.S3), verticalAlignment = Alignment.CenterVertically) {
            UsageCell(s.gbLeft, "گیگ", Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(18.dp).background(K.Gold.copy(alpha = .18f)))
            UsageCell(s.daysLeft, "روز", Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(18.dp).background(K.Gold.copy(alpha = .18f)))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                val c = if (active) K.Green else K.Red
                Dot(c, 7.dp)
                Spacer(Modifier.width(6.dp))
                T(faStatus(s.status), size = 12.sp, color = c, weight = FontWeight.Bold, maxLines = 1)
            }
        }
        // نوار حجم مصرف‌شده (فقط اگر حجم نامحدود نیست)
        s.usedFrac?.let { f ->
            val bar = RoundedCornerShape(2.dp)
            Box(Modifier.fillMaxWidth().padding(start = D.S3, end = D.S3, bottom = D.S2).height(4.dp).clip(bar).background(Color.White.copy(alpha = .08f))) {
                Box(
                    Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).fillMaxHeight().clip(bar)
                        .background(Brush.horizontalGradient(if (f > .85f) listOf(K.Warn, K.Red) else listOf(K.GoldDeep, K.Gold, K.GoldLight))),
                )
            }
        }
    }
}

@Composable
private fun UsageCell(value: String, unit: String, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val v = faDigits(value)
        val latin = isLatin(v)
        T(v, size = 14.sp, color = K.Text, font = if (latin) Poppins else Vazir, weight = FontWeight.Black, ltr = latin)
        Spacer(Modifier.width(D.S1))
        T(unit, size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
    }
}

/** تیک انتخاب (دایره به رنگ «وصل» تم) */
@Composable
fun CheckMark(on: Boolean, size: Dp = 22.dp, color: Color = K.Green) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (on) color else Color.Transparent)
            .border(1.5.dp, if (on) color else K.Sub.copy(alpha = .4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Canvas(Modifier.size(size * .55f)) {
            val w = this.size.width
            drawLine(K.Ink, Offset(w * .12f, w * .52f), Offset(w * .40f, w * .80f), w * .16f, StrokeCap.Round)
            drawLine(K.Ink, Offset(w * .40f, w * .80f), Offset(w * .90f, w * .22f), w * .16f, StrokeCap.Round)
        }
    }
}

/** یک خط توضیح زیر انتخاب پروتکل: تانل است یا مستقیم، و کِی به درد می‌خورد */
fun protoHint(p: String) = when (p) {
    "kankimix" -> "پروتکل کانکی · بسته‌های بازی بدون صف و دوتایی، کمترین لگ"
    "amneziawg" -> "تانل · از سرور ایران رد می‌شود و فیلتر را دور می‌زند"
    "amneziawg-direct" -> "مستقیم · بدون تانل؛ وقتی تانل‌ها وصل نمی‌شوند همین را بزنید"
    "wireguard" -> "تانل · سریع و مناسب گیم"
    "hysteria2" -> "تانل · پرسرعت روی اینترنت ضعیف"
    "xray" -> "Xray · برای فیلتر شدید، سخت‌ترین برای مسدود شدن"
    else -> protoDesc(p)
}
