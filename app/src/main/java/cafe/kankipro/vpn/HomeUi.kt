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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.takeOrElse
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
import androidx.compose.ui.graphics.drawscope.inset
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
fun MainFrame(tab: KTab, onTab: (KTab) -> Unit, onRobot: () -> Unit, onAccount: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    ThemeState.animate() // گذر نرم بین تم‌ها
    // ورود محو + لغزش ملایم محتوا هنگام تعویض تب (سبک و ۶۰فریمی)
    val enter = remember { Animatable(1f) }
    LaunchedEffect(tab) {
        enter.snapTo(0f)
        enter.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
    }
    Column(Modifier.fillMaxSize()) {
        AppHeader(onRobot, onAccount)
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

/** سربرگ: لوگوی گرد K و KANKI VPN در یک طرف، سه دکمه‌ی گرد برجسته (لینک، ربات، گفتگو) در طرف دیگر */
@Composable
fun AppHeader(onRobot: () -> Unit, onAccount: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = D.PagePad, end = D.PagePad, top = D.S2, bottom = D.S2), verticalAlignment = Alignment.CenterVertically) {
        Logo3D(34.dp)
        Spacer(Modifier.width(D.S2))
        BrandTitle(15.sp)
        Spacer(Modifier.weight(1f))
        Box(Modifier.guideTarget("robot")) { HeaderIcon(K.Gold, onRobot) { robotHeadIcon(it) } }
        Spacer(Modifier.width(D.S3))
        Box(Modifier.guideTarget("account")) { HeaderIcon(K.Gold, onAccount) { linkIcon(it) } }
    }
}


/** دکمه‌ی گرد برجسته‌ی سربرگ (۱.۲۹: دایره به‌جای مربع، با سایه و لبه‌ی نورانی) */
@Composable
fun HeaderIcon(tint: Color, onClick: () -> Unit, draw: DrawScope.(Color) -> Unit) {
    Box(
        Modifier.size(40.dp).raisedCircle(tint).pressable(scale = .9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(22.dp)) { draw(tint) } }
}


/** دو حلقه‌ی زنجیر در هم (لینک اشتراک)، دوتُن */
fun DrawScope.linkIcon(c: Color) {
    val u = size.width / 24f
    drawCircle(c.copy(alpha = .14f), 9f * u, center)
    iconPath("M10 13.5a4.6 4.6 0 0 0 6.9.5l2.8-2.8a4.6 4.6 0 0 0-6.5-6.5l-1.6 1.6", c, 2.3f)
    iconPath("M14 10.5a4.6 4.6 0 0 0-6.9-.5l-2.8 2.8a4.6 4.6 0 0 0 6.5 6.5l1.6-1.6", lerp(c, Color.White, .3f), 2.3f)
}

/** سر ربات راهنما برای دکمه‌ی سربرگ: سر گرد طلایی، صفحه‌ی صورت تیره، دو چشم روشن و آنتن */
fun DrawScope.robotHeadIcon(c: Color) {
    val u = size.width / 24f
    val eye = K.Accent
    drawLine(c, Offset(12f * u, 5.6f * u), Offset(12f * u, 2.8f * u), 1.6f * u, StrokeCap.Round)
    drawCircle(eye, 1.6f * u, Offset(12f * u, 2.4f * u))
    drawRoundRect(c, Offset(1.6f * u, 10f * u), Size(2.6f * u, 5.4f * u), CornerRadius(1.2f * u))
    drawRoundRect(c, Offset(19.8f * u, 10f * u), Size(2.6f * u, 5.4f * u), CornerRadius(1.2f * u))
    drawRoundRect(Brush.verticalGradient(listOf(lerp(c, Color.White, .35f), c, lerp(c, Color.Black, .35f)), startY = 5.5f * u, endY = 21f * u),
        Offset(3.6f * u, 5.6f * u), Size(16.8f * u, 15f * u), CornerRadius(5.2f * u))
    drawRoundRect(Color.Black.copy(alpha = .82f), Offset(5.6f * u, 9f * u), Size(12.8f * u, 8.2f * u), CornerRadius(3.6f * u))
    for (x in listOf(9.3f, 14.7f)) {
        drawCircle(eye.copy(alpha = .30f), 2.4f * u, Offset(x * u, 13.1f * u))
        drawCircle(eye, 1.5f * u, Offset(x * u, 13.1f * u))
        drawCircle(Color.White.copy(alpha = .9f), .55f * u, Offset((x - .5f) * u, 12.6f * u))
    }
    drawRoundRect(Color.White.copy(alpha = .35f), Offset(6.5f * u, 6.6f * u), Size(11f * u, 1.3f * u), CornerRadius(.7f * u))
}

/**
 * نوار تب‌های پایین (۱.۲۹): قرص رنگی تب انتخاب‌شده با فنر از تبی به تب دیگر می‌لغزد، آیکون تب تازه کمی بالا
 * می‌پرد و پر می‌شود. نوار و قرص هر دو برجسته (سایه و لبه‌ی نورانی).
 */
@Composable
fun BottomBar(current: KTab, onSelect: (KTab) -> Unit) {
    val shape = RoundedCornerShape(D.RX)
    val idx by animateFloatAsState(current.ordinal.toFloat(), spring(dampingRatio = .72f, stiffness = 420f), label = "nav")
    val p = ThemeState.current
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = D.S3, vertical = D.S2).goldGlass(shape).padding(D.S1),
    ) {
        val n = KTab.entries.size
        val gap = D.S1
        val tabW = (maxWidth - gap * (n - 1)) / n
        // قرص لغزان زیر تب انتخاب‌شده (offset با جهت صفحه برمی‌گردد، مثل خود ردیف)
        val ts = RoundedCornerShape(D.RL)
        Box(
            Modifier.offset(x = (tabW + gap) * idx).width(tabW).height(54.dp)
                .goldFill(ts),
        )
        Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
            KTab.entries.forEachIndexed { i, t ->
                val sel = t == current
                val c = if (sel) p.onNav else K.Sub
                val ids = listOf("nav_home", "nav_servers", "nav_test", "nav_settings")
                val lift by animateFloatAsState(if (sel) 1f else 0f, spring(dampingRatio = .45f, stiffness = 380f), label = "lift")
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(ts)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(t) }
                        .guideTarget(ids[i]),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Canvas(Modifier.size(22.dp).graphicsLayer {
                        translationY = -2.dp.toPx() * lift
                        scaleX = 1f + .12f * lift; scaleY = 1f + .12f * lift
                    }) { navIcon(i, c, lift) }
                    Spacer(Modifier.height(3.dp))
                    T(t.title, size = 11.sp, color = c, weight = if (sel) FontWeight.Black else FontWeight.Bold, maxLines = 1)
                }
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
                // نقشه و پنل‌های روی آن (هوشمند، هشدار ربات)؛ از ۱.۲۸ کوتاه‌تر (۷۶ تا ۱۷۰dp) تا دکمه و حالت‌ها کنار هم جا شوند
                BoxWithConstraints(Modifier.fillMaxWidth().weight(.37f).padding(top = D.S2)) {
                    val mapH = maxHeight.coerceIn(80.dp, 210.dp)
                    // روی گوشی کوتاه پنل هوشمند فقط تعداد ردیفی که روی نقشه جا می‌شود (سرتیتر و نوار پیشرفت همیشه)
                    val rows = ((maxHeight - gap * 2 - 64.dp) / (SmartRowH + D.S1)).toInt().coerceAtLeast(1)
                    WorldMap(
                        ui.countries, ui.pins, ui.userGeo, ui.userIso, on,
                        // نقشه داخل یک قاب گرد تیره با لبه‌ی شیشه‌ای (مثل طرح)
                        Modifier.fillMaxWidth().padding(horizontal = D.PagePad).height(mapH).align(Alignment.Center)
                            .drawBehind { liftShadow(RoundedCornerShape(D.RX).createOutline(size, layoutDirection, this), 6.dp.toPx(), .5f) }
                            .clip(RoundedCornerShape(D.RX))
                            .background(Brush.radialGradient(listOf(K.Gold.copy(alpha = .07f), Color.Transparent), radius = 900f))
                            .background(lerp(K.BgMid, Color.Black, .45f).copy(alpha = .85f))
                            .drawWithContent {
                                drawContent()
                                // شیشه‌ی روی نقشه: نور مورب کم‌رنگ و کلاهک براق بالا
                                drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = .07f), Color.Transparent, Color.Black.copy(alpha = .12f)), Offset.Zero, Offset(size.width * .5f, size.height)))
                                drawOval(Brush.verticalGradient(listOf(Color.White.copy(alpha = .14f), Color.Transparent), startY = 0f, endY = 34.dp.toPx()),
                                    Offset(size.width * .05f, -30.dp.toPx()), Size(size.width * .9f, 64.dp.toPx()))
                            }
                            .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .5f), Color.White.copy(alpha = .07f), Color.Black.copy(alpha = .3f), K.Gold.copy(alpha = .45f))), RoundedCornerShape(D.RX))
                            .guideTarget("map"),
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
                // دکمه‌ی اتصال دقیقاً وسط و چهار حالت روی یک مدار دورش (دو تا هر طرف، قرینه). قطر دکمه با فضای مانده.
                BoxWithConstraints(Modifier.fillMaxWidth().weight(.63f).padding(horizontal = D.PagePad, vertical = D.S1), contentAlignment = Alignment.Center) {
                    // ارتفاع لازم: دکمه + حالت‌های بالا و پایین مدار با اسمشان (حدود ۱.۰۴ قطر + ۳۶dp)
                    // ارتفاع لازم: دکمه + حالت‌های بالا و پایین با اسمشان؛ پهنا: حالت‌ها با نیم‌دایره‌شان داخل صفحه
                    val byH = if (ui.hasAccount) (maxHeight - 62.dp) / 1.06f else maxHeight - D.S2
                    val byW = if (ui.hasAccount) (maxWidth / 2 - 14.dp - ORBIT_PUSH) / (ORBIT_DX + 54f / 212f / 2f) else maxWidth
                    val d = minOf(byH, byW).coerceIn(112.dp, 240.dp)
                    Box(Modifier.guideTarget("connect")) {
                        OrbButton(ui, mc, enabled = ui.hasAccount && !ui.sheetOpen, diameter = d, onClick = act.toggle)
                    }
                    if (ui.hasAccount) {
                        ModeOrbit(
                            ui.mode, d, enabled = ui.vstate != VState.CONNECTING || ui.mode == KMode.GAME,
                            modifier = Modifier.fillMaxSize().guideTarget("protocols"),
                            showDns = ui.dns.hasDns, live = ui.vstate == VState.ON, onTap = act.tapMode, onLong = act.longMode,
                        )
                    }
                }
                Column(
                    Modifier.fillMaxWidth().padding(start = D.PagePad, end = D.PagePad, top = gap, bottom = gap),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    // کارت سرور: وصل = سرور واقعی با «اتصال پایدار»؛ قطع = سرور انتخابی روی نقشه با «آماده»
                    val pick = ui.pins.firstOrNull { it.selected }
                    if (on && ui.server != null) ConnectedCard(ui.server, mc)
                    else if (ui.hasAccount && pick != null && ui.mode != KMode.DNS) StripCard(
                        flagEmoji(pick.iso) ?: "🌐", countryName(pick.iso) ?: pick.iso ?: "—", tr("سرور انتخابی"),
                        if (ui.vstate == VState.CONNECTING) "در حال اتصال" else "آماده", K.Gold, selected = false,
                    )
                    if (!ui.hasAccount) {
                        if (ui.loading) Spinner(28.dp) else GoldButton("تلاش دوباره", Modifier.fillMaxWidth(), onClick = act.retry)
                    } else {
                        ModeHint(ui.mode)
                        if (ui.mode == KMode.DNS) DnsCard(ui.dns, ui.vstate, act.dns)
                    }
                    if (ui.hasAccount || ui.sub != null) StatsCard(ui, act.openAccount)
                }
            }
        }
    }
}

/** جای حالت‌ها روی مدار دور دکمه‌ی اتصال، نسبت به قطر دکمه (مثل طرح: دکمه ۲۱۲، مدار ۱۴۶، زاویه ±۳۴ درجه) */
const val ORBIT_R = 146f / 212f
const val ORBIT_DX = 121f / 212f
const val ORBIT_DY = 82f / 212f

/** قطر دکمه‌ی هر حالت روی دکمه‌ی پایه و فاصله‌ی اضافه‌ی آن‌ها رو به بیرون (۱.۳۲.۳) */
val ORBIT_MODE = 54.dp
val ORBIT_PUSH = 14.dp

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

/** تعداد تکه‌های حلقه‌ی نئونی دور دکمه‌ی اتصال */
private const val SEGS = 44

/**
 * دکمه‌ی اتصال (۱.۳۲، طرح «Kanki Glass»): بزل شیشه‌ای با برق چرخان، حلقه‌ای از ۴۴ تکه‌ی نئونی، سه نقطه‌ی نور که با
 * فاصله‌ی برابر دورش می‌چرخند، و وسطش گنبد شیشه‌ای تیره با نشان پاور، اسم حالت و زمان اتصال.
 * خاموش: تکه‌ها کم‌رنگ‌اند و یک جرقه آرام دورشان می‌گردد؛ در حال اتصال: تکه‌ها یکی‌یکی روشن می‌شوند و نور دور
 * نشان پاور تند می‌چرخد؛ وصل: همه‌ی تکه‌ها به رنگ اتصال روشن‌اند، دو جرقه‌ی سفید رویشان می‌دوند، گنبد از داخل
 * می‌درخشد و هاله‌ی پشت دکمه آرام نفس می‌کشد. خود گنبد هیچ‌وقت رنگی و پر نمی‌شود (فقط حلقه و نشان پاور).
 */
@Composable
fun OrbButton(ui: HomeUi, color: Color, enabled: Boolean, diameter: Dp = OrbBase, onClick: () -> Unit) {
    val on = ui.vstate == VState.ON
    val connecting = ui.vstate == VState.CONNECTING
    val clock = motionTime()
    val onA by animateFloatAsState(if (on) 1f else 0f, tween(600), label = "on")
    val conA by animateFloatAsState(if (connecting) 1f else 0f, tween(300), label = "con")
    // لحظه‌ی شروع «در حال اتصال»: حلقه از همان لحظه تکه‌تکه پر می‌شود
    var conStart by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(connecting) { if (connecting) conStart = clock.value }
    val ac = color
    val s = diameter / OrbBase
    Box(
        Modifier.size(diameter).alpha(if (enabled) 1f else .55f).clickable(
            enabled = enabled,
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val t = clock.value
            val r = size.minDimension / 2
            val c = center
            val u = 1.dp.toPx()
            // ---- هاله‌ی پشت دکمه (وصل: پررنگ و نفس‌کشان)
            // گیمینگ: هاله تندتر می‌زند (حال‌وهوای بازی)؛ بقیه آرام
            val breathe = .5f + .5f * sin(t * (if (ui.mode == KMode.GAME) 4.6f else 1.85f))
            val haloA = .3f + .7f * onA * (.85f + .15f * breathe)
            drawCircle(
                Brush.radialGradient(0f to ac.copy(alpha = .30f * haloA), .45f to ac.copy(alpha = .08f * haloA), 1f to Color.Transparent, center = c, radius = r * 1.3f),
                r * 1.3f, c,
            )
            // ---- سایه‌ی عمیق زیر دکمه
            val sc = c + Offset(0f, r * .1f)
            drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = .7f), Color.Transparent), sc, r * 1.12f), r * 1.12f, sc)
            // ---- بزل شیشه‌ای (بین ۰.۶۷ و ۱ شعاع) با برق مخروطی
            val bw = r * .33f
            val br = r - bw / 2
            drawCircle(Brush.verticalGradient(listOf(lerp(K.BgMid, Color.White, .14f), lerp(K.BgMid, Color.Black, .45f)), startY = c.y - r, endY = c.y + r), br, c, style = Stroke(bw))
            rotate(t * 6f % 360f, c) {
                drawCircle(
                    Brush.sweepGradient(
                        listOf(
                            Color.White.copy(alpha = .20f), Color.White.copy(alpha = .03f), ac.copy(alpha = .14f), Color.White.copy(alpha = .02f),
                            Color.White.copy(alpha = .15f), Color.White.copy(alpha = .02f), Color.White.copy(alpha = .20f),
                        ),
                        c,
                    ),
                    br, c, style = Stroke(bw),
                )
            }
            drawCircle(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = .38f), Color.White.copy(alpha = .04f), Color.Black.copy(alpha = .5f)), startY = c.y - r, endY = c.y + r),
                r - .5f * u, c, style = Stroke(1.2f * u),
            )
            drawCircle(Color.Black.copy(alpha = .55f), r * .67f, c, style = Stroke(1.5f * u))
            // برق شیشه روی بزل: کمان نور سفید بالا و لبه‌ی روشن
            drawArc(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = .42f), Color.Transparent), startY = c.y - r, endY = c.y - r * .2f),
                200f, 140f, false, Offset(c.x - (r - bw * .3f), c.y - (r - bw * .3f)), Size((r - bw * .3f) * 2, (r - bw * .3f) * 2), style = Stroke(bw * .45f, cap = StrokeCap.Round),
            )
            drawCircle(Brush.verticalGradient(listOf(Color.White.copy(alpha = .6f), Color.White.copy(alpha = .05f), K.Gold.copy(alpha = .35f)), startY = c.y - r, endY = c.y + r), r - .6f * u, c, style = Stroke(1.3f * u))
            // ---- حلقه‌ی تکه‌تکه‌ی نئونی
            val sr = r * .82f
            val sw = r * .078f
            val step = 360f / SEGS
            val gap = 2.6f
            val litN = if (connecting) (((t - conStart) / 2.2f % 1f) * SEGS).toInt() + 1 else 0
            val tl = Offset(c.x - sr, c.y - sr)
            val sz = Size(sr * 2, sr * 2)
            for (i in 0 until SEGS) {
                val start = i * step - 90f + gap / 2
                val isLit = i < litN
                // فقط سرِ پرشونده‌ی «در حال اتصال» سفید است؛ نور تند دور حلقه دیگر نیست
                val hot = connecting && i == litN - 1
                // هاله‌ی باریک و کم‌رنگ (بدون محو پهن)، تا تکه‌ها شارپ و زنده بمانند
                if (isLit) drawArc(ac.copy(alpha = .16f), start, step - gap, false, tl, sz, style = Stroke(sw * 1.45f, cap = StrokeCap.Round))
                val col = when {
                    hot -> Color.White
                    isLit -> lerp(ac, Color.White, .12f)
                    else -> Color.White.copy(alpha = .09f)
                }
                drawArc(col, start, step - gap, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            }
            // ---- وصل: سه کمان جدا (هر کدام ۶۵ درجه، فاصله‌ی برابر) روی همان حلقه، شارپ و بی‌هاله، خیلی آرام می‌چرخند
            if (onA > .01f) {
                for (k in 0 until 3) {
                    val st = t * 9f + k * 120f - 90f
                    drawArc(lerp(ac, Color.White, .10f).copy(alpha = onA), st, 65f, false, tl, sz, style = Stroke(sw * .62f, cap = StrokeCap.Round))
                }
            }
            // ---- گنبد شیشه‌ای تیره وسط
            val dr = r * .59f
            drawCircle(Color.Black.copy(alpha = .4f), dr + 6f * u, c)
            drawCircle(lerp(Color.White.copy(alpha = .07f), ac.copy(alpha = .4f), onA), dr + 6.5f * u, c, style = Stroke(1.2f * u))
            drawCircle(
                Brush.linearGradient(listOf(lerp(K.BgMid, Color.White, .12f), lerp(K.BgMid, Color.Black, .5f)), Offset(c.x - dr, c.y - dr), Offset(c.x + dr * .3f, c.y + dr)),
                dr, c,
            )
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .16f), Color.Transparent), Offset(c.x - dr * .32f, c.y - dr * .56f), dr * .9f), dr, c)
            drawCircle(Brush.radialGradient(listOf(ac.copy(alpha = .15f + .15f * onA), Color.Transparent), Offset(c.x, c.y + dr * 1.15f), dr * 1.2f), dr, c)
            if (onA > .01f) {
                // وصل: وسط گنبد با رنگ تم روشن می‌شود
                drawCircle(Brush.radialGradient(listOf(ac.copy(alpha = .34f * onA), ac.copy(alpha = .10f * onA), Color.Transparent), c, dr), dr, c)
                drawCircle(Brush.radialGradient(listOf(Color.Transparent, ac.copy(alpha = .26f * onA)), c, dr), dr, c)
                drawCircle(ac.copy(alpha = .14f * onA), dr + 3f * u, c, style = Stroke(6f * u))
                drawCircle(ac.copy(alpha = .65f * onA), dr, c, style = Stroke(1.5f * u))
            }
            drawCircle(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .45f)), startY = c.y, endY = c.y + dr), dr, c)
            drawOval(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = .28f), Color.Transparent), startY = c.y - dr * .94f, endY = c.y - dr * .3f),
                Offset(c.x - dr * .6f, c.y - dr * .94f), Size(dr * 1.2f, dr * .62f),
            )
            drawCircle(Brush.verticalGradient(listOf(Color.White.copy(alpha = .3f), Color.Transparent), startY = c.y - dr, endY = c.y), dr, c, style = Stroke(1.2f * u))
        }
        // ---- وسط گنبد: نشان پاور، اسم حالت و زمان اتصال
        Column(Modifier.width(diameter * .5f), horizontalAlignment = Alignment.CenterHorizontally) {
            PowerEmblem(ui.vstate, ac, 34.dp * s, ui.mode == KMode.GAME)
            Spacer(Modifier.height(5.dp * s))
            val dnsOn = ui.mode == KMode.DNS
            val (title, sub) = when (ui.vstate) {
                VState.ON -> (if (dnsOn) "DNS فعال" else ui.mode.title) to (if (dnsOn) (ui.dns.activeRoute ?: "DNS") else elapsed(ui.elapsedSec))
                VState.CONNECTING -> "در حال اتصال" to "لمس برای لغو"
                VState.OFF -> "اتصال" to ui.mode.title
            }
            val lit = on || connecting
            val tc = if (lit) ac else K.Text
            T(title, size = (if (connecting) 15f else 20f).sp * s.coerceAtMost(1.1f), color = tc, weight = FontWeight.Black, shadow = if (lit) glow(ac, 16f) else null, maxLines = 1)
            val num = on && !dnsOn
            T(sub, size = (if (num) 15f else 12f).sp * s.coerceAtMost(1.1f), color = if (num) K.Text else K.Sub, font = if (num) Poppins else Vazir,
                weight = FontWeight.Bold, ltr = num, maxLines = 1)
        }
    }
}

/**
 * نشان پاور وسط دکمه‌ی اتصال (۱.۳۲، به‌جای کلید): عدسی شیشه‌ای کوچک. خاموش خاکستری؛ در حال اتصال نوری دورش تند
 * می‌چرخد و آیکون به رنگ اتصال در می‌آید؛ وصل: عدسی پر از رنگ اتصال، با برق و درخشش.
 */
@Composable
private fun PowerEmblem(state: VState, ac: Color, d: Dp, game: Boolean = false) {
    val clock = motionTime()
    val onA by animateFloatAsState(if (state == VState.ON) 1f else 0f, tween(450), label = "pwOn")
    val conA by animateFloatAsState(if (state == VState.CONNECTING) 1f else 0f, tween(250), label = "pwCon")
    Canvas(Modifier.size(d)) {
        val r = size.minDimension / 2
        val c = center
        val u = 1.dp.toPx()
        drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = .6f), Color.Transparent), c + Offset(0f, r * .25f), r * 1.3f), r * 1.3f, c + Offset(0f, r * .25f))
        if (onA > .01f) drawCircle(ac.copy(alpha = .3f * onA), r + 4f * u, c, style = Stroke(5f * u))
        val top = lerp(lerp(K.BgMid, Color.White, .16f), lerp(ac, Color.White, .45f), onA)
        val bot = lerp(lerp(K.BgMid, Color.Black, .5f), lerp(ac, Color.Black, .4f), onA)
        drawCircle(Brush.verticalGradient(listOf(top, bot)), r, c)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .22f + .35f * onA), Color.Transparent), Offset(c.x - r * .3f, c.y - r * .5f), r * .9f), r, c)
        drawCircle(Brush.verticalGradient(listOf(Color.White.copy(alpha = .35f + .45f * onA), Color.White.copy(alpha = .05f))), r - .5f * u, c, style = Stroke(u))
        if (conA > .01f) {
            val rr = r + 3f * u
            drawArc(ac.copy(alpha = conA), clock.value * 400f % 360f, 110f, false, Offset(c.x - rr, c.y - rr), Size(rr * 2, rr * 2), style = Stroke(2f * u, cap = StrokeCap.Round))
        }
        val ic = lerp(lerp(K.Sub, ac, conA), K.OnGold, onA)
        // وصل در گیمینگ: دسته‌ی بازی به‌جای نشان پاور
        if (game && state == VState.ON) inset(size.width * .17f) { modeLogo(KMode.GAME, ic) }
        else inset(size.width * .2f) { iconPath("M12 3v9M17.7 6.3a8 8 0 1 1-11.4 0", ic, 2.4f) }
    }
}

/** قطر دکمه‌ی اتصال در طرح اصلی؛ اندازه‌ی واقعی با diameter از همین مقیاس می‌شود */
val OrbBase = 212.dp

/** دوره‌ی هر ضربان نوار قلب پینگ (ثانیه): پینگ کم آرام، پینگ بالا تند */
private fun beatPeriod(ping: Long?): Float = if (ping == null || ping < 0) 1.15f else (1.25f - ping / 400f).coerceIn(.55f, 1.15f)

private fun smooth(x: Float) = x * x * (3f - 2f * x)

private fun elapsed(s: Long?): String {
    val v = s ?: 0
    return String.format(java.util.Locale.US, "%02d:%02d:%02d", v / 3600, v / 60 % 60, v % 60)
}

/** کارت سرور وصل‌شده: پرچم، کشور و شهر، پروتکل و «اتصال پایدار» */
@Composable
private fun ConnectedCard(s: ServerChipUi, c: Color) {
    StripCard(s.flag, s.country, listOfNotNull(s.city, protoTitle(s.proto)).joinToString(" · "), "اتصال پایدار", c, selected = true)
}

/** کارت باریک سرور زیر دکمه‌ی اتصال: پرچم گرد، کشور، یک خط توضیح و یک برچسب وضعیت با نقطه‌ی نورانی */
@Composable
private fun StripCard(flag: String, country: String, sub: String, pill: String, c: Color, selected: Boolean) {
    val shape = RoundedCornerShape(D.RL)
    Row(
        Modifier.fillMaxWidth().height(if (LocalCompact.current) D.HomeRowS else D.HomeRow + 4.dp).goldGlass(shape, selected = selected, tint = c)
            .padding(horizontal = D.S3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = .4f)).border(1.5.dp, Color.White.copy(alpha = .45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { T(flag, size = 19.sp, weight = FontWeight.Normal) }
        Spacer(Modifier.width(D.S2 + 2.dp))
        Column(Modifier.weight(1f)) {
            T(country, size = 14.sp, color = K.Text, weight = FontWeight.Black, maxLines = 1)
            AutoText(sub, size = 11.sp, color = K.Sub, weight = FontWeight.Bold)
        }
        val sh = RoundedCornerShape(50)
        Row(
            Modifier.clip(sh).background(Color.Black.copy(alpha = .35f)).border(1.dp, c.copy(alpha = .6f), sh).padding(horizontal = D.S3, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(c, 6.dp)
            Spacer(Modifier.width(6.dp))
            T(pill, size = 11.sp, color = K.Text, weight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/**
 * کارت پایین صفحه‌ی خانه (۱.۳۲): بالا یک خط نازک اشتراک (گیگ و روز مانده، وضعیت) با نوار نئونی ۶dp حجم مانده،
 * زیرش سه خانه‌ی کم‌ارتفاع پینگ (با ضربان زنده)، دانلود و آپلود. زدن خط اشتراک پنل حساب را باز می‌کند.
 */
@Composable
private fun StatsCard(ui: HomeUi, onAccount: () -> Unit) {
    val on = ui.vstate == VState.ON
    val shape = RoundedCornerShape(D.RL)
    val compact = LocalCompact.current
    Column(Modifier.fillMaxWidth().goldGlass(shape)) {
        ui.sub?.let { s ->
            val active = isActive(s.status)
            Column(
                Modifier.fillMaxWidth().clickable(onClick = onAccount).guideTarget("sub").padding(start = D.S3, end = D.S3, top = if (compact) 6.dp else D.S2, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UsageCell(s.gbLeft, "گیگ مانده")
                    Spacer(Modifier.width(D.S3))
                    UsageCell(s.daysLeft, "روز")
                    Spacer(Modifier.weight(1f))
                    val c = if (active) K.Good else K.Red
                    Dot(c, 7.dp)
                    Spacer(Modifier.width(6.dp))
                    T(faStatus(s.status), size = 12.sp, color = c, weight = FontWeight.Black, maxLines = 1)
                }
                // نوار نئونی حجم مانده (فقط اگر حجم نامحدود نیست)
                s.usedFrac?.let { f -> NeonBar(1f - f.coerceIn(0f, 1f), warn = f > .85f) }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = D.S3).height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .10f), Color.Transparent))))
        }
        if (ui.hasAccount) Row(
            Modifier.fillMaxWidth().height(if (compact) 36.dp else 40.dp).guideTarget("stats").padding(horizontal = D.S1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1.25f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MiniIcon("pulse", K.Gold, 11.dp)
                    Spacer(Modifier.width(D.S1))
                    T("پینگ", size = 11.sp, color = K.Sub, weight = FontWeight.Bold)
                }
                val p = if (on) ui.livePing else null
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PingPulse(if (on) ui.pingHist else emptyList(), p, Modifier.width(44.dp).height(16.dp))
                        Spacer(Modifier.width(6.dp))
                        T(p?.toString() ?: "--", size = 14.sp, color = if (p != null) pingColor(p) else K.Sub, font = Poppins, weight = FontWeight.Bold, ltr = true)
                        Spacer(Modifier.width(3.dp))
                        T("ms", size = 10.sp, color = K.Sub, font = Poppins, weight = FontWeight.SemiBold, ltr = true)
                    }
                }
            }
            StatDivider()
            SpeedCell("down", "دانلود", K.Gold, if (on) ui.rx else null, Modifier.weight(1f))
            StatDivider()
            SpeedCell("up", "آپلود", K.Gold, if (on) ui.tx else null, Modifier.weight(1f))
        }
    }
}

/**
 * نوار نئونی باریک (۶dp): شیار تو رفته، پرشده با گرادیان رنگ تم و یک نقطه‌ی نورانی سرِ آن.
 * fraction: سهم پر (۰ تا ۱)، از سمت شروع صفحه. warn: رنگ هشدار (حجم رو به پایان).
 */
@Composable
fun NeonBar(fraction: Float, modifier: Modifier = Modifier, warn: Boolean = false, color: Color = K.Gold) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "neon")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val c = if (warn) K.Warn else color
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val h = size.height
        val w = size.width
        val cr = CornerRadius(h / 2)
        drawRoundRect(Color.Black.copy(alpha = .45f), Offset.Zero, size, cr)
        drawRoundRect(Color.White.copy(alpha = .07f), Offset.Zero, size, cr)
        val fw = (w * f).coerceAtLeast(h)
        val x0 = if (rtl) w - fw else 0f
        val tip = if (rtl) x0 + h / 2 else fw - h / 2
        val stops = listOf(lerp(c, Color.Black, .45f), c, lerp(c, Color.White, .4f))
        drawRoundRect(c.copy(alpha = .28f), Offset(x0, -h * .35f), Size(fw, h * 1.7f), CornerRadius(h))
        drawRoundRect(
            Brush.horizontalGradient(if (rtl) stops.reversed() else stops, startX = x0, endX = x0 + fw),
            Offset(x0, h * .12f), Size(fw, h * .76f), cr,
        )
        drawCircle(c.copy(alpha = .5f), h * 1.1f, Offset(tip, h / 2))
        drawCircle(Color.White, h * .62f, Offset(tip, h / 2))
    }
}

@Composable
private fun StatDivider() {
    Box(Modifier.width(1.dp).height(28.dp).background(Brush.verticalGradient(listOf(Color.Transparent, K.Gold.copy(alpha = .45f), Color.Transparent))))
}

/**
 * سرعت لحظه‌ای با واحد مناسب، مثل شمارنده‌ی سرعت گوشی: زیر ۱ مگابایت بر ثانیه به کیلوبایت (۸۵۰ KB/s)، بالاتر به
 * مگابایت (۲.۴ MB/s)؛ پس دانلود کم «0.0» نشان داده نمی‌شود
 */
fun rateParts(bytesPerSec: Long): Pair<String, String> {
    val b = bytesPerSec.coerceAtLeast(0)
    return when {
        b >= 1_000_000 -> String.format(java.util.Locale.US, if (b >= 100_000_000) "%.0f" else "%.1f", b / 1_000_000.0) to "MB/s"
        b >= 10_000 -> String.format(java.util.Locale.US, "%.0f", b / 1_000.0) to "KB/s"
        else -> String.format(java.util.Locale.US, "%.1f", b / 1_000.0) to "KB/s"
    }
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
        val (v, unit) = bytesPerSec?.let { rateParts(it) } ?: ("--" to "KB/s")
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.Bottom) {
                T(v, size = 14.sp, color = K.Text, font = Poppins, weight = FontWeight.Bold, ltr = true)
                Spacer(Modifier.width(3.dp))
                T(unit, size = 9.5.sp, color = K.Sub, font = Poppins, weight = FontWeight.SemiBold, ltr = true, modifier = Modifier.padding(bottom = 2.dp))
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
                // فقط سر فاصله‌ی Motion.frameNs جلو می‌رود (گوشی ضعیف ۳۰ فریم، بقیه ۶۰)
                if (last != 0L && now - last < Motion.frameNs) return@withFrameNanos
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

@Composable
private fun UsageCell(value: String, unit: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val v = faDigits(value)
        val latin = isLatin(v) || v.none { it in '\u0600'..'\u06FF' }
        T(v, size = 14.sp, color = K.Text, font = if (latin) Poppins else Vazir, weight = FontWeight.Black, ltr = latin)
        Spacer(Modifier.width(6.dp))
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

