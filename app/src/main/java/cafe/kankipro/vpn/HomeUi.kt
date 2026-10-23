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
    Column(Modifier.fillMaxSize()) {
        AppHeader(onRobot, onAccount, onEmergency)
        Box(Modifier.weight(1f), content = content)
        BottomBar(tab, onTab)
    }
}

/** سربرگ: لوگوی سپر K و KANKI VPN در یک طرف، سه دکمه‌ی کوچک مربعی (لینک، ربات، گفتگو) در طرف دیگر */
@Composable
fun AppHeader(onRobot: () -> Unit, onAccount: () -> Unit, onEmergency: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Logo3D(30.dp)
        Spacer(Modifier.width(8.dp))
        BrandTitle(15.sp)
        Spacer(Modifier.weight(1f))
        Box(Modifier.guideTarget("emergency")) { HeaderIcon(K.Gold, onEmergency) { emergencyChatIcon(it) } }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.guideTarget("robot")) { HeaderIcon(K.Gold, onRobot) { drawRobot(body = false) } }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.guideTarget("account")) { HeaderIcon(K.Gold, onAccount) { linkIcon(it) } }
    }
}

/** دکمه‌ی کوچک مربعی شیشه‌ای سربرگ با لبه‌ی طلایی */
@Composable
fun HeaderIcon(tint: Color, onClick: () -> Unit, draw: DrawScope.(Color) -> Unit) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        Modifier.size(38.dp).goldGlass(shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(20.dp)) { draw(tint) } }
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
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).goldGlass(shape)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        KTab.entries.forEachIndexed { i, t ->
            val sel = t == current
            val c = if (sel) K.Gold else K.Sub
            val ids = listOf("nav_home", "nav_servers", "nav_test", "nav_settings")
            val ts = RoundedCornerShape(16.dp)
            Column(
                Modifier.weight(1f).then(if (sel) Modifier.goldGlass(ts, selected = true) else Modifier.clip(ts))
                    .clickable { onSelect(t) }
                    .guideTarget(ids[i])
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Canvas(Modifier.size(22.dp)) { navIcon(i, c) }
                Spacer(Modifier.height(4.dp))
                T(t.title, size = 11.sp, color = c, weight = if (sel) FontWeight.Black else FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

// ================================================================== صفحه‌ی اتصال

@Composable
fun HomeContent(ui: HomeUi, act: HomeActions, modifier: Modifier = Modifier) {
    val mc = ui.accent
    val on = ui.vstate == VState.ON
    // ستون دست‌کم به بلندی صفحه است و فاصله‌های weight فضای خالی را پخش می‌کنند:
    // نقشه بالا، دکمه‌ی اتصال وسط، کارت‌ها چسبیده به نوار پایین. اگر محتوا بلندتر شد، صفحه اسکرول می‌خورد.
    BoxWithConstraints(modifier.fillMaxSize()) {
        val minH = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().heightIn(min = minH)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                    LocationChip(ui.locationIso, ui.locationIsExit)
                }
                // نقشه بدون قاب، لبه‌هایش در زمینه‌ی مشکی محو می‌شود؛ جدا از دکمه‌ی اتصال تا رویش نیفتد
                WorldMap(
                    ui.countries, ui.pins, ui.userGeo, ui.userIso, on,
                    Modifier.fillMaxWidth().height(206.dp).guideTarget("map"),
                    accent = if (ui.vstate == VState.OFF) K.Gold else mc,
                    dns = ui.mode == KMode.DNS,
                )
                Spacer(Modifier.weight(1f).heightIn(min = 4.dp))
                Box(Modifier.align(Alignment.CenterHorizontally).guideTarget("connect")) {
                    OrbButton(ui, mc, enabled = ui.hasAccount && !ui.sheetOpen, onClick = act.toggle)
                }
                Spacer(Modifier.weight(.7f).heightIn(min = 6.dp))

                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedVisibility(on && ui.server != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        ui.server?.let { ConnectedCard(it, mc, Modifier.padding(bottom = 8.dp)) }
                    }
                    if (!ui.hasAccount) {
                        if (ui.loading) Spinner(28.dp) else GoldButton("تلاش دوباره", Modifier.fillMaxWidth(), onClick = act.retry)
                    } else {
                        ModeRow(ui.mode, enabled = ui.vstate != VState.CONNECTING || ui.mode == KMode.GAME, modifier = Modifier.guideTarget("protocols"), onTap = act.tapMode, onLong = act.longMode)
                        Spacer(Modifier.height(8.dp))
                        ModeHint(ui.mode)
                        AnimatedVisibility(ui.smart.open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                            SmartPanel(ui.smart, Modifier.padding(top = 8.dp))
                        }
                        if (ui.mode == KMode.DNS) DnsCard(ui.dns, ui.vstate, act.dns, Modifier.padding(top = 8.dp))
                    }

                    AnimatedVisibility(visible = ui.alert != null) {
                        ui.alert?.let { al ->
                            RobotBanner(al, Modifier.padding(top = 8.dp), onAction = act.alertAction, onClose = act.dismissAlert)
                        }
                    }
                    if (ui.hasAccount) {
                        Spacer(Modifier.height(8.dp))
                        StatsRow(ui)
                    }
                    ui.sub?.let {
                        Spacer(Modifier.height(8.dp))
                        UsageStrip(it, Modifier.guideTarget("sub"), act.openAccount)
                    }
                    Spacer(Modifier.height(10.dp))
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
            .padding(horizontal = 12.dp, vertical = 5.dp),
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
 * حالت گیمینگ یک لایه‌ی سبز روی همین دکمه دارد (پایین‌تر: revCounter، scanLine، hudBrackets، sparks، heartbeat): گوشه‌های نشانه‌گیری دور دکمه؛ موقع اتصال درجه‌ها
 * پشت هم روشن می‌شوند (مثل دورسنج) و یک خط اسکن دور حلقه می‌چرخد؛ وقتی وصل است دکمه با ریتم پینگ می‌تپد
 * و چند جرقه‌ی پیکسلی روی حلقه می‌گردند.
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
    var spin by remember { mutableFloatStateOf(0f) }
    var tick by remember { mutableFloatStateOf(0f) }
    var time by remember { mutableFloatStateOf(0f) }
    var beat by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1e9f).coerceAtMost(.1f)
                    spin += dt * speed
                    // حلقه‌ی درجه‌دار موقع اتصال گیمینگ آهسته‌تر می‌چرخد تا روشن شدن درجه‌ها خوانا باشد
                    tick += dt * speed * (1f - .75f * gCon)
                    time += dt
                    beat += dt / period
                }
                last = now
            }
        }
    }
    val lit by animateFloatAsState(if (on) 1f else if (connecting) .85f else .6f, tween(700), label = "lit")
    val onA by animateFloatAsState(if (on) 1f else 0f, tween(700), label = "on")
    val conA by animateFloatAsState(if (connecting) 1f else 0f, tween(400), label = "con")
    val halo = 78.dp

    Box(
        Modifier.size(diameter + halo).alpha(if (enabled) 1f else .55f).clickable(
            enabled = enabled,
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = diameter.toPx() / 2
            val gold = K.Gold
            val breathe = .5f + .5f * sin(time * 2.1f)
            val deg = Math.toDegrees(spin.toDouble()).toFloat()
            val env = beatEnv(beat % 1f)

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
                        val a = (.10f + .08f * lit) * (.7f + .3f * breathe)
                        drawPath(ray, Brush.verticalGradient(0f to gold.copy(alpha = 0f), .45f to gold.copy(alpha = a), 1f to gold.copy(alpha = 0f), startY = c.y - len, endY = c.y))
                    }
                }
            }
            // ---- هاله‌ی گرم پشت دکمه
            drawCircle(
                Brush.radialGradient(
                    0f to K.Warn.copy(alpha = 0f), .45f to K.Warn.copy(alpha = (.30f + .2f * lit) * (.75f + .25f * breathe)),
                    .72f to K.Warn.copy(alpha = .07f), 1f to Color.Transparent,
                    center = c, radius = r * 2f,
                ),
                r * 2f, c,
            )
            // ---- ستاره‌های تک
            orbStars.forEach { s ->
                val rad = Math.toRadians((s.start + deg * .07f / s.orbit).toDouble())
                val o = Offset(c.x + r * s.orbit * cos(rad).toFloat(), c.y + r * s.orbit * sin(rad).toFloat() * .8f)
                val tw = .5f + .5f * sin(time * s.twinkle + s.start)
                val col = if (s.white) Color.White else K.GoldLight
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

            // ---- حلقه‌ی نئونی بیرونی با دو نور دنباله‌دار
            val r2 = r * 1.42f
            neonRing(gold, c, r2, 1.2.dp.toPx(), strength = .45f + .25f * lit)
            neonComet(K.GoldLight, deg, 80f, c, r2, 1.6.dp.toPx())
            neonComet(K.GoldLight, deg + 180f, 80f, c, r2, 1.6.dp.toPx())
            // ---- حلقه‌ی درجه‌دار ظریف که برعکس می‌چرخد
            rotate(-Math.toDegrees(tick.toDouble()).toFloat() * .35f, c) {
                val r1 = r * 1.2f
                for (i in 0 until 72) {
                    val a = Math.toRadians(i * 5.0)
                    val major = i % 6 == 0
                    val h = (if (major) 5f else 3f).dp.toPx()
                    val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
                    drawLine(
                        if (major) K.GoldLight.copy(alpha = .85f) else gold.copy(alpha = .4f),
                        Offset(c.x + (r1 - h) * ca, c.y + (r1 - h) * sa), Offset(c.x + (r1 + h) * ca, c.y + (r1 + h) * sa),
                        (if (major) 2f else 1.1f).dp.toPx(), StrokeCap.Round,
                    )
                }
                if (gCon > .01f) revCounter(time, gCon, c, r1)
            }
            if (gCon > .01f) scanLine(time, gCon, c, r)
            if (gameA > .01f) hudBrackets(time, gameA, gCon, gOn, env, c, r)
            if (gOn > .01f) sparks(time, gOn, env, c, r2)

            // ---- دکمه: شیشه‌ی مشکی با لبه‌ی طلایی ضخیم و درخشان
            val glowA = .35f + .25f * lit * breathe
            drawCircle(Brush.radialGradient(listOf(K.Warn.copy(alpha = glowA), Color.Transparent), c, r * 1.32f), r * 1.32f, c)
            drawCircle(Color(0xFF0B0906), r + 4.dp.toPx(), c)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF2A2112), Color(0xFF0E0B07), Color(0xFF050403)), Offset(c.x, c.y - r * .3f), r * 1.1f), r, c)
            drawCircle(
                Brush.verticalGradient(listOf(K.Shine, gold, K.GoldDeep, K.GoldLight), startY = c.y - r, endY = c.y + r),
                r + 1.dp.toPx(), c, style = Stroke(5.dp.toPx()),
            )
            drawCircle(K.GoldLight.copy(alpha = .5f), r - 6.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            drawOval(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = .20f), Color.Transparent), startY = c.y - r, endY = c.y - r * .1f),
                Offset(c.x - r * .7f, c.y - r * .9f), Size(r * 1.4f, r * .8f),
            )
            // در حال اتصال: نور سفید دور لبه می‌دود
            if (conA > .01f) {
                val rr = r + 1.dp.toPx()
                drawArc(
                    lerp(Color(0xFFFFFBEA), GameLight, gCon).copy(alpha = conA), time * 344f, 69f, false,
                    Offset(c.x - rr, c.y - rr), Size(rr * 2, rr * 2), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            // وصل: دو موج طلایی که از دکمه بیرون می‌زنند
            if (onA > .01f) for (k in 0 until 2) {
                val p = ((time / 2.6f) + k * .5f) % 1f
                drawCircle(K.GoldLight.copy(alpha = (1f - p) * .55f * onA * (1f - .55f * gOn)), r * (1.05f + p * .75f), c, style = Stroke(1.6.dp.toPx()))
            }
            if (gOn > .01f) heartbeat(beat % 1f, env, gOn, c, r)
        }
        // ---- وسط دکمه: لوگو، عنوان و یک خط توضیح، همه وسط‌چین و در اندازه‌ی دکمه
        Column(Modifier.width(diameter * .74f), horizontalAlignment = Alignment.CenterHorizontally) {
            Logo3D(30.dp, animate = on)
            Spacer(Modifier.height(3.dp))
            val dnsOn = ui.mode == KMode.DNS
            val (title, sub) = when (ui.vstate) {
                VState.ON -> (if (dnsOn) "DNS فعال" else "متصل") to
                    (if (dnsOn) (ui.dns.activeRoute ?: "DNS") else elapsed(ui.elapsedSec))
                VState.CONNECTING -> "در حال اتصال" to "لمس برای لغو"
                VState.OFF -> "اتصال" to ui.mode.title
            }
            val tc = if (on) K.Green else K.Gold
            T(title, size = if (ui.vstate == VState.OFF) 22.sp else 16.sp, color = tc, weight = FontWeight.Black, shadow = glow(tc, 16f), maxLines = 1)
            val num = on && !dnsOn
            T(sub, size = if (num) 14.sp else 11.sp, color = if (num) Color.White else K.Text.copy(alpha = .85f), font = if (num) Poppins else Vazir,
                weight = FontWeight.Bold, ltr = num, maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------ لایه‌ی گیمینگ دکمه‌ی اتصال

/** سبز گیمینگ و نسخه‌ی روشنش (برای سرِ نورها) */
private val Game get() = modeColor(KMode.GAME)
private val GameLight get() = lerp(Game, Color.White, .35f)

/** دوره‌ی هر ضربان (ثانیه) از پینگ: پینگ کم آرام و منظم، پینگ بالا تند و نگران */
private fun beatPeriod(ping: Long?): Float = if (ping == null || ping < 0) 1.15f else (1.25f - ping / 400f).coerceIn(.55f, 1.15f)

/** شدت تپش در یک دوره (۰..۱): «تاپ» قوی و پشتش «توپ» ضعیف‌تر، مثل ضربان قلب */
private fun beatEnv(u: Float): Float {
    fun bump(c: Float, w: Float) = (1f - abs(u - c) / w).coerceIn(0f, 1f).let { it * it }
    return max(bump(.05f, .06f), .6f * bump(.22f, .07f))
}

/** نرم شدن آغاز و پایان (۰..۱) */
private fun smooth(x: Float) = x * x * (3f - 2f * x)

/** موقع اتصال گیمینگ: درجه‌ها یکی‌یکی از بالا سبز می‌شوند (مثل دورسنج)، پر که شد محو می‌شود و از نو */
private fun DrawScope.revCounter(time: Float, a: Float, c: Offset, r1: Float) {
    val ch = (time / 1.5f) % 1f
    val n = smooth(ch) * 72f
    val fade = if (ch > .86f) 1f - (ch - .86f) / .14f else 1f
    for (i in 0 until kotlin.math.ceil(n).toInt().coerceAtMost(72)) {
        val ang = Math.toRadians(i * 5.0 - 90)
        val major = i % 6 == 0
        val h = (if (major) 6.5f else 4f).dp.toPx()
        val head = 1f - ((n - i) / 6f).coerceIn(0f, 1f)   // ۱ برای درجه‌های سرِ پر شدن
        val col = lerp(Game, Color.White, .65f * head)
        val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()
        val p1 = Offset(c.x + (r1 - h) * ca, c.y + (r1 - h) * sa)
        val p2 = Offset(c.x + (r1 + h) * ca, c.y + (r1 + h) * sa)
        if (head > 0f) drawLine(Game.copy(alpha = .3f * head * a * fade), p1, p2, 5.dp.toPx(), StrokeCap.Round)
        drawLine(col.copy(alpha = a * fade * (.6f + .4f * head)), p1, p2, (if (major) 2.4f else 1.6f).dp.toPx(), StrokeCap.Round)
    }
}

/** خط اسکن (مثل رادار) که دور حلقه می‌چرخد و پشتش یک بادبزن سبز محو می‌آید */
private fun DrawScope.scanLine(time: Float, a: Float, c: Offset, r: Float) {
    val sa = (time * 250f) % 360f
    val rb = r * 1.24f
    val tl = Offset(c.x - rb, c.y - rb)
    val sz = Size(rb * 2, rb * 2)
    for (k in 0 until 12) drawArc(Game.copy(alpha = a * .14f * (1f - k / 12f)), sa - (k + 1) * 3.5f, 3.7f, false, tl, sz, style = Stroke(r * .3f))
    val rad = Math.toRadians(sa.toDouble())
    val d = Offset(cos(rad).toFloat(), sin(rad).toFloat())
    val p1 = c + d * (r + 7.dp.toPx())
    val p2 = c + d * (r * 1.4f)
    drawLine(Game.copy(alpha = .25f * a), p1, p2, 4.dp.toPx(), StrokeCap.Round)
    drawLine(GameLight.copy(alpha = .9f * a), p1, p2, 1.3.dp.toPx(), StrokeCap.Round)
}

/**
 * گوشه‌های نشانه‌گیری دور دکمه و چهار علامت کوچک روی محورها. خاموش: کم‌رنگ و کمی دورتر؛ در حال اتصال: با هر دور پر شدن
 * درجه‌ها از بیرون روی دکمه قفل می‌شوند؛ وصل: سر جایشان با ضربان کمی نفس می‌کشند.
 */
private fun DrawScope.hudBrackets(time: Float, a0: Float, gCon: Float, gOn: Float, env: Float, c: Offset, r: Float) {
    val ch = smooth((time / 1.5f) % 1f)
    val k = 1f + .2f * gCon * (1f - ch) + .025f * env * gOn + .05f * (1f - gCon) * (1f - gOn)
    val hh = r * 1.12f * k
    val arm = r * .2f
    val a = a0 * (.3f + .25f * gCon + .3f * gOn + .15f * env * gOn)
    val glow = Stroke(4.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val line = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) {
        val p = Offset(c.x + sx * hh, c.y + sy * hh)
        val path = Path().apply { moveTo(p.x - sx * arm, p.y); lineTo(p.x, p.y); lineTo(p.x, p.y - sy * arm) }
        drawPath(path, Game.copy(alpha = .22f * a), style = glow)
        drawPath(path, GameLight.copy(alpha = a), style = line)
    }
    for (q in 0 until 4) {
        val rad = Math.toRadians(q * 90.0)
        val d = Offset(cos(rad).toFloat(), sin(rad).toFloat())
        drawLine(GameLight.copy(alpha = .8f * a), c + d * (r * 1.48f), c + d * (r * 1.56f), 1.4.dp.toPx(), StrokeCap.Round)
    }
}

/** جرقه‌های پیکسلی کوچک (سبز و طلایی) که با دنباله‌ی کوتاه روی حلقه‌ی نئونی می‌گردند و با ضربان روشن‌تر می‌شوند */
private fun DrawScope.sparks(time: Float, a: Float, env: Float, c: Offset, r2: Float) {
    for (i in 0 until 8) {
        val sp = if (i % 2 == 0) 34f + i * 6f else -(28f + i * 5f)
        val ang = i * 58f + time * sp
        val rr = r2 + sin(time * 2.3f + i) * 3.dp.toPx()
        val col = if (i % 3 == 0) K.GoldLight else GameLight
        val tw = .55f + .45f * sin(time * (3f + i * .4f) + i)
        for (k in 0 until 4) {
            val rad = Math.toRadians((ang - kotlin.math.sign(sp) * k * 2.6f).toDouble())
            val o = Offset(c.x + rr * cos(rad).toFloat(), c.y + rr * sin(rad).toFloat())
            val s = (2.4f - .45f * k).dp.toPx()
            val al = a * tw * (1f - k * .27f) * (.7f + .3f * env)
            if (k == 0) drawCircle(col.copy(alpha = .22f * al), s * 2.2f, o)
            drawRect(col.copy(alpha = al), Offset(o.x - s / 2, o.y - s / 2), Size(s, s))
        }
    }
}

/** ضربان دکمه در حالت گیمینگ وصل: لبه با ریتم پینگ سبز می‌درخشد و با هر «تاپ» یک موج سبز تا حلقه می‌رود */
private fun DrawScope.heartbeat(u: Float, env: Float, a: Float, c: Offset, r: Float) {
    val e = env * a
    for ((w, al) in listOf(9f to .08f, 5f to .16f, 2.6f to .42f)) drawCircle(Game.copy(alpha = al * e), r + 2.dp.toPx(), c, style = Stroke(w.dp.toPx()))
    val wp = u / .7f
    if (wp < 1f) drawCircle(GameLight.copy(alpha = (1f - wp) * .55f * a), r * (1.04f + wp * .38f), c, style = Stroke((2.2f - wp * 1.2f).dp.toPx()))
}

private fun elapsed(s: Long?): String {
    val v = s ?: 0
    return String.format(java.util.Locale.US, "%02d:%02d:%02d", v / 3600, v / 60 % 60, v % 60)
}

/** کارت سرور وصل‌شده (مثل طرح): پرچم، کشور و شهر، پروتکل و «اتصال پایدار» */
@Composable
private fun ConnectedCard(s: ServerChipUi, c: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.fillMaxWidth().goldGlass(shape, selected = true)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(s.flag, size = 22.sp, weight = FontWeight.Normal)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            T(s.country, size = 14.sp, color = K.Text, weight = FontWeight.Black, maxLines = 1)
            T(listOfNotNull(s.city, protoTitle(s.proto)).joinToString(" · "), size = 10.5.sp, color = K.Sub, weight = FontWeight.Bold, maxLines = 1)
        }
        val sh = RoundedCornerShape(50)
        Row(
            Modifier.clip(sh).background(K.Green.copy(alpha = .1f)).border(1.dp, K.Green.copy(alpha = .5f), sh).padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(K.Green, 6.dp)
            Spacer(Modifier.width(5.dp))
            T("اتصال پایدار", size = 10.5.sp, color = K.Green, weight = FontWeight.Bold, maxLines = 1)
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
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().height(66.dp).goldGlass(shape).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1.25f), horizontalAlignment = Alignment.CenterHorizontally) {
            T("پینگ", size = 11.5.sp, color = K.Text, weight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            val p = if (on) ui.livePing else null
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(p?.toString() ?: "--", size = 15.sp, color = if (p != null) pingColor(p) else K.Sub, font = Poppins, weight = FontWeight.Bold, ltr = true)
                    Spacer(Modifier.width(3.dp))
                    T("ms", size = 10.sp, color = K.Sub, font = Poppins, weight = FontWeight.SemiBold, ltr = true)
                    Spacer(Modifier.width(6.dp))
                    PingPulse(if (on) ui.pingHist else emptyList(), p, Modifier.width(46.dp).height(20.dp))
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
    Box(Modifier.width(1.dp).height(34.dp).background(Brush.verticalGradient(listOf(Color.Transparent, K.Gold.copy(alpha = .45f), Color.Transparent))))
}

@Composable
private fun SpeedCell(icon: String, label: String, tint: Color, bytesPerSec: Long?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(18.dp).clip(CircleShape).background(tint.copy(alpha = .12f)).border(1.dp, tint.copy(alpha = .8f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { MiniIcon(icon, tint, 9.dp) }
            Spacer(Modifier.width(5.dp))
            T(label, size = 11.5.sp, color = K.Text, weight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        val v = bytesPerSec?.let { String.format(java.util.Locale.US, "%.1f", it * 8 / 1_000_000.0) } ?: "--"
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.Bottom) {
                T(v, size = 15.sp, color = K.Text, font = Poppins, weight = FontWeight.Bold, ltr = true)
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
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxWidth().goldGlass(shape)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            UsageCell(s.gbLeft, "گیگ", Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(18.dp).background(K.Gold.copy(alpha = .18f)))
            UsageCell(s.daysLeft, "روز", Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(18.dp).background(K.Gold.copy(alpha = .18f)))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                val c = if (active) K.Green else K.Red
                Dot(c, 7.dp)
                Spacer(Modifier.width(6.dp))
                T(faStatus(s.status), size = 13.sp, color = c, weight = FontWeight.Bold, maxLines = 1)
            }
        }
        // نوار حجم مصرف‌شده (فقط اگر حجم نامحدود نیست)
        s.usedFrac?.let { f ->
            val bar = RoundedCornerShape(2.dp)
            Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 9.dp).height(5.dp).clip(bar).background(Color.White.copy(alpha = .08f))) {
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
        T(v, size = 15.sp, color = K.Text, font = if (latin) Poppins else Vazir, weight = FontWeight.Black, ltr = latin)
        Spacer(Modifier.width(5.dp))
        T(unit, size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
    }
}

/** تیک انتخاب (دایره‌ی سبز) */
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
