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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
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
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
            LocationChip(ui.locationIso, ui.locationIsExit)
        }
        // نقشه‌ی بزرگ بدون قاب؛ دکمه‌ی گرد اتصال روی لبه‌ی محوشده‌ی پایین نقشه
        Box(Modifier.fillMaxWidth().height(344.dp)) {
            WorldMap(
                ui.countries, ui.pins, ui.userGeo, ui.userIso, on,
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(276.dp).guideTarget("map"),
                accent = if (ui.vstate == VState.OFF) K.Gold else mc,
            )
            Box(Modifier.align(Alignment.BottomCenter).guideTarget("connect")) {
                OrbButton(ui, mc, enabled = ui.hasAccount && !ui.sheetOpen, onClick = act.toggle)
            }
        }
        Spacer(Modifier.height(6.dp))

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
                AnimatedVisibility(on, enter = fadeIn(), exit = fadeOut()) { SecureStrip(mc, Modifier.padding(top = 8.dp)) }
            }
            ui.sub?.let {
                Spacer(Modifier.height(8.dp))
                UsageStrip(it, Modifier.guideTarget("sub"), act.openAccount)
            }
            Spacer(Modifier.height(10.dp))
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

/** ستاره‌ی ریز هاله‌ی دکمه: جایش روی حلقه‌ی دور دکمه ثابت است و آرام می‌چرخد و چشمک می‌زند */
private class OrbStar(val orbit: Float, val start: Float, val size: Float, val twinkle: Float, val white: Boolean)

/** ستاره‌های ثابت (با بذر ثابت)، تا با هر بار ساخته شدن صفحه جایشان نپرد */
private val orbStars: List<OrbStar> = run {
    val rnd = java.util.Random(2077)
    List(70) {
        OrbStar(
            orbit = 1.02f + (rnd.nextFloat() * rnd.nextFloat()) * .62f,
            start = rnd.nextFloat() * 360f,
            size = .35f + rnd.nextFloat() * rnd.nextFloat() * 1.3f,
            twinkle = .6f + rnd.nextFloat() * 2.2f,
            white = rnd.nextFloat() < .45f,
        )
    }
}

/**
 * دکمه‌ی گرد اتصال با هاله‌ی کهکشانی:
 * پشت دکمه همان کهکشان مارپیچ طلایی عکس پس‌زمینه (res/drawable-nodpi/galaxy_halo.webp، از روبه‌رو)
 * کمی کج و سه‌بعدی می‌چرخد؛ دورش غبار ستاره‌ای چشمک می‌زند و وسطش دیسک شیشه‌ای براق با لبه‌ی طلایی و لوگوی K است.
 * رنگ‌ها همیشه طلایی کهکشانی است؛ وقتی وصل است فقط پرنورتر و گرم‌تر می‌شود و کمی رنگ پروتکل
 * (color) قاطی‌اش می‌شود. موقع اتصال کهکشان تند می‌چرخد.
 */
@Composable
fun OrbButton(ui: HomeUi, color: Color, enabled: Boolean, diameter: Dp = 138.dp, onClick: () -> Unit) {
    val on = ui.vstate == VState.ON
    val connecting = ui.vstate == VState.CONNECTING
    // سرعت (نرم عوض می‌شود تا سیاره‌ها با تغییر وضعیت نپرند)
    val speed by animateFloatAsState(if (connecting) 4f else if (on) 1.1f else .45f, tween(900), label = "spd")
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) time += ((now - last) / 1e9f).coerceAtMost(.1f) * speed
                last = now
            }
        }
    }
    val t = rememberInfiniteTransition(label = "orb")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "pulse")
    val breathe by t.animateFloat(.7f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "br")
    // هاله‌ی طلایی تپنده پشت دکمه (مثل طرح)
    val glowA by t.animateFloat(.18f, .52f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "gl")
    val lit by animateFloatAsState(if (on) 1f else if (connecting) .8f else .55f, tween(700), label = "lit")
    // طلایی کهکشانی؛ وصل: روشن‌تر با ۲۰٪ رنگ پروتکل
    val gold by animateColorAsState(if (on) androidx.compose.ui.graphics.lerp(K.GoldLight, color, .2f) else K.Gold, tween(700), label = "g")
    val halo = 78.dp
    val galaxy = androidx.compose.ui.graphics.ImageBitmap.imageResource(R.drawable.galaxy_halo)

    Box(
        Modifier.size(diameter + halo).alpha(if (enabled) 1f else .55f).clickable(
            enabled = enabled,
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val outer = this.size.minDimension / 2
            val r = outer * (diameter.value / (diameter.value + halo.value))
            // ---- هاله‌ی طلایی تپنده
            val gr = outer * 1.02f
            drawCircle(Brush.radialGradient(listOf(gold.copy(alpha = glowA * (.6f + .4f * lit)), gold.copy(alpha = .12f), Color.Transparent), c, gr), gr, c)
            // ---- کهکشان چرخان (کمی کج، مثل سه‌بعدی)، آرام پشت هاله
            val gs = outer * 2.05f
            withTransform({
                rotate(-16f, c)
                scale(1f, .74f, c)
                rotate(time * 10f, c)
            }) {
                drawImage(
                    galaxy, dstOffset = androidx.compose.ui.unit.IntOffset((c.x - gs / 2).toInt(), (c.y - gs / 2).toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(gs.toInt(), gs.toInt()), alpha = .35f + .30f * lit,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                )
            }
            // ---- غبار ستاره‌ای
            orbStars.forEach { s ->
                val deg = s.start + time * (14f / s.orbit)
                val rad = Math.toRadians(deg.toDouble())
                val rr = r * s.orbit * (1f + .18f * kotlin.math.sin(rad * 2).toFloat())
                val o = Offset(c.x + rr * kotlin.math.cos(rad).toFloat(), c.y + rr * kotlin.math.sin(rad).toFloat() * .8f)
                val tw = .5f + .5f * kotlin.math.sin((time * s.twinkle * 1.6f + s.start).toDouble()).toFloat()
                val col = if (s.white) Color.White else K.GoldLight
                val sz = s.size.dp.toPx()
                drawCircle(col.copy(alpha = .18f * tw * lit), sz * 3f, o)
                drawCircle(col.copy(alpha = (.35f + .65f * tw) * lit), sz, o)
            }

            // ---- دیسک تیره با حلقه‌ی ضخیم طلایی (مثل طرح)
            val disc = r
            drawCircle(Color.Black.copy(alpha = .55f), disc * 1.06f, Offset(c.x, c.y + disc * .06f))
            drawCircle(Brush.radialGradient(listOf(Color(0xFF42300D), Color(0xFF15110A), Color(0xFF050505)), c, disc), disc, c)
            // حلقه‌ی بیرونی کم‌رنگ و درخشش لبه
            drawCircle(gold.copy(alpha = .16f), disc - 4.dp.toPx(), c, style = Stroke(8.dp.toPx()))
            for ((m, al) in listOf(6f to .07f, 3.5f to .14f)) {
                drawCircle(gold.copy(alpha = al * (.6f + .4f * lit)), disc, c, style = Stroke(3.dp.toPx() * m))
            }
            // لبه‌ی اصلی طلایی با نور چرخان
            drawCircle(
                Brush.sweepGradient(listOf(K.GoldDeep, gold, K.Shine, gold, K.GoldDeep, gold, K.GoldDeep), c),
                disc - 1.5.dp.toPx(), c, style = Stroke(3.dp.toPx()),
            )
            drawCircle(gold.copy(alpha = .30f), disc * .86f, c, style = Stroke(.8.dp.toPx()))
            // براقی شیشه‌ای بالای دیسک
            drawOval(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = .16f), Color.Transparent), startY = c.y - disc * .9f, endY = c.y - disc * .15f),
                Offset(c.x - disc * .62f, c.y - disc * .88f), Size(disc * 1.24f, disc * .7f),
            )
            // موج تپنده وقتی وصل است
            if (on) drawCircle(gold.copy(alpha = (1f - pulse) * .5f), disc * (1.04f + pulse * .22f), c, style = Stroke(1.4.dp.toPx()))

        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Logo3D(34.dp, animate = on)
            Spacer(Modifier.height(4.dp))
            val dnsOn = ui.mode == KMode.DNS
            val (title, sub) = when (ui.vstate) {
                VState.ON -> (if (dnsOn) "DNS فعال" else "اتصال فعال") to
                    (if (dnsOn) (ui.dns.activeRoute ?: "DNS") else elapsed(ui.elapsedSec))
                VState.CONNECTING -> "در حال اتصال" to "لمس: لغو"
                VState.OFF -> "اتصال" to ui.mode.title
            }
            T(title, size = if (ui.vstate == VState.OFF) 20.sp else 15.sp, color = if (on) Color.White else K.GoldLight, weight = FontWeight.Black, shadow = glow(gold, 14f), maxLines = 1)
            if (sub.isNotBlank()) {
                val num = on && !dnsOn
                T(sub, size = if (num) 13.sp else 10.sp, color = if (num) gold else K.Text.copy(alpha = .8f), font = if (num) Poppins else Vazir,
                    weight = FontWeight.Bold, ltr = num, maxLines = 1)
            }
        }
    }
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

/** کارت پینگ (با نمودار زنده) و کارت دانلود/آپلود */
@Composable
private fun StatsRow(ui: HomeUi) {
    val on = ui.vstate == VState.ON
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("پینگ", size = 12.sp, color = K.Sub, weight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                val p = if (on) ui.livePing else null
                T(p?.let { "$it ms" } ?: "– ms", size = 16.sp, color = if (p != null) pingColor(p) else K.Sub, font = Poppins, weight = FontWeight.Bold, ltr = true)
            }
            Spacer(Modifier.height(6.dp))
            Sparkline(if (on) ui.pingHist else emptyList(), Modifier.fillMaxWidth().height(30.dp))
        }
        StatCard(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth()) {
                SpeedCell("down", "دانلود", if (on) ui.rx else null, Modifier.weight(1f))
                SpeedCell("up", "آپلود", if (on) ui.tx else null, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.height(88.dp).goldGlass(shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
private fun SpeedCell(icon: String, label: String, bytesPerSec: Long?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniIcon(icon, K.Gold, 10.dp)
            Spacer(Modifier.width(4.dp))
            T(label, size = 11.sp, color = K.Sub, weight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        val v = bytesPerSec?.let { String.format(java.util.Locale.US, "%.1f", it * 8 / 1_000_000.0) } ?: "–"
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.Bottom) {
                T(v, size = 16.sp, color = K.Text, font = Poppins, weight = FontWeight.Bold, ltr = true)
                Spacer(Modifier.width(3.dp))
                T("Mbps", size = 9.5.sp, color = K.Sub, font = Poppins, weight = FontWeight.SemiBold, ltr = true, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

/** نمودار خطی کوچک پینگ‌های اخیر (طلایی با کمی درخشش) */
@Composable
private fun Sparkline(values: List<Long>, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (values.size < 2) {
            drawLine(K.Gold.copy(alpha = .25f), Offset(0f, h * .6f), Offset(w, h * .6f), 1.5.dp.toPx(), StrokeCap.Round)
            return@Canvas
        }
        val lo = values.min().toFloat()
        val hi = values.max().toFloat()
        val span = (hi - lo).coerceAtLeast(8f)
        val step = w / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = h * .9f - (v - lo) / span * h * .8f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, K.Gold.copy(alpha = .18f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, K.Gold, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
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
            Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp).height(4.dp).clip(bar).background(K.Gold.copy(alpha = .12f))) {
                Box(
                    Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).fillMaxHeight().clip(bar)
                        .background(Brush.horizontalGradient(listOf(K.Green, if (f > .85f) K.Warn else K.Green.copy(alpha = .7f)))),
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
