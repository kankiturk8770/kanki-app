package cafe.kankipro.vpn

import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// چهار کاشی حالت، پنل هوشمند، کارت DNS، پنل گیمینگ و انتخاب برنامه‌ها.

/** رنگ اختصاصی هر حالت برای حلقه و نور دکمه‌ی اتصال؛ از تم (هر تم چهار رنگ هماهنگ خودش را دارد) */
fun modeColor(m: KMode): Color = when (m) {
    KMode.SMART -> ThemeState.current.modeSmart
    KMode.GAME -> ThemeState.current.modeGame
    KMode.FILTER -> ThemeState.current.modeAnti
    KMode.DNS -> ThemeState.current.modeDns
}

/** آیکون هر حالت (کشیده‌شده، بدون فایل تصویر) */
@Composable
fun ModeIcon(m: KMode, color: Color, size: Dp = D.Icon) {
    if (m == KMode.DNS) {
        Box(
            Modifier.size(size).clip(CircleShape).background(color.copy(alpha = .08f)).border(1.4.dp, color, CircleShape),
            contentAlignment = Alignment.Center,
        ) { T("DNS", size = (size.value * .26f).sp, color = color, font = Poppins, weight = FontWeight.Bold, ltr = true) }
    } else {
        Canvas(Modifier.size(size)) { modeLogo(m, color) }
    }
}

fun DrawScope.modeLogo(m: KMode, c: Color) {
    val w = size.width
    val sw = w * .085f
    val st = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (m) {
        KMode.SMART -> {
            // ستاره‌ی چهارپر بزرگ و یک ستاره‌ی کوچک (درخشش)
            fun star(cx: Float, cy: Float, r: Float) = Path().apply {
                moveTo(cx, cy - r)
                quadraticBezierTo(cx + r * .16f, cy - r * .16f, cx + r, cy)
                quadraticBezierTo(cx + r * .16f, cy + r * .16f, cx, cy + r)
                quadraticBezierTo(cx - r * .16f, cy + r * .16f, cx - r, cy)
                quadraticBezierTo(cx - r * .16f, cy - r * .16f, cx, cy - r)
                close()
            }
            drawPath(star(w * .42f, w * .42f, w * .36f), c)
            drawPath(star(w * .80f, w * .80f, w * .16f), c)
        }
        KMode.GAME -> {
            // دسته‌ی بازی
            val body = Path().apply {
                moveTo(w * .28f, w * .28f); lineTo(w * .72f, w * .28f)
                cubicTo(w * .92f, w * .28f, w * .98f, w * .62f, w * .9f, w * .76f)
                cubicTo(w * .84f, w * .86f, w * .72f, w * .82f, w * .66f, w * .68f)
                lineTo(w * .34f, w * .68f)
                cubicTo(w * .28f, w * .82f, w * .16f, w * .86f, w * .1f, w * .76f)
                cubicTo(w * .02f, w * .62f, w * .08f, w * .28f, w * .28f, w * .28f); close()
            }
            drawPath(body, c, style = st)
            drawLine(c, Offset(w * .3f, w * .40f), Offset(w * .3f, w * .56f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * .22f, w * .48f), Offset(w * .38f, w * .48f), sw, StrokeCap.Round)
            drawCircle(c, w * .05f, Offset(w * .66f, w * .42f))
            drawCircle(c, w * .05f, Offset(w * .76f, w * .53f))
        }
        KMode.FILTER -> {
            // سپر با یک موشک کوچک داخلش (پایدار روی شبکه‌ی محدود)
            val sh = Path().apply {
                moveTo(w * .5f, w * .05f); lineTo(w * .88f, w * .19f); lineTo(w * .88f, w * .48f)
                cubicTo(w * .88f, w * .72f, w * .72f, w * .88f, w * .5f, w * .96f)
                cubicTo(w * .28f, w * .88f, w * .12f, w * .72f, w * .12f, w * .48f); lineTo(w * .12f, w * .19f); close()
            }
            drawPath(sh, c, style = st)
            rotate(45f, Offset(w * .5f, w * .5f)) {
                val cx = w * .5f
                val rocket = Path().apply {
                    moveTo(cx, w * .25f)
                    quadraticBezierTo(cx + w * .10f, w * .34f, cx + w * .08f, w * .58f)
                    lineTo(cx - w * .08f, w * .58f)
                    quadraticBezierTo(cx - w * .10f, w * .34f, cx, w * .25f)
                    close()
                }
                drawPath(rocket, c)
                val finL = Path().apply { moveTo(cx - w * .08f, w * .48f); lineTo(cx - w * .15f, w * .62f); lineTo(cx - w * .08f, w * .58f); close() }
                val finR = Path().apply { moveTo(cx + w * .08f, w * .48f); lineTo(cx + w * .15f, w * .62f); lineTo(cx + w * .08f, w * .58f); close() }
                drawPath(finL, c)
                drawPath(finR, c)
                drawLine(c.copy(alpha = .7f), Offset(cx, w * .64f), Offset(cx, w * .72f), sw * .8f, StrokeCap.Round)
                drawCircle(Color.Black.copy(alpha = .55f), w * .035f, Offset(cx, w * .40f))
            }
        }
        KMode.DNS -> {
            drawCircle(c, w * .4f, Offset(w * .5f, w * .5f), style = st)
        }
    }
}

/**
 * چهار حالت روی یک مدار دور دکمه‌ی اتصال (۱.۳۲، طرح «Kanki Glass»): هوشمند و گیمینگ سمت شروع صفحه، ضد فیلتر و DNS سمت
 * مقابل، قرینه. مرکز هر دایره دقیقاً روی جای خودش است و اسمش زیرش. دو خط منحنی نقطه‌چین هر جفت را از وسط پایینِ دکمه‌ی
 * بالا به وسط بالای دکمه‌ی پایین (و برعکس در طرف مقابل) وصل می‌کنند؛ نور نئونی یک چرخه‌ی پیوسته دارد: هوشمند ← گیمینگ،
 * محو؛ بعد DNS ← ضد فیلتر، محو؛ و دوباره از هوشمند. dial: قطر دکمه‌ی اتصال (جا و اندازه‌ی حالت‌ها با آن مقیاس می‌شود).
 */
@Composable
fun ModeOrbit(selected: KMode, dial: Dp, enabled: Boolean, modifier: Modifier = Modifier, showDns: Boolean = true, live: Boolean = false, onTap: (KMode) -> Unit, onLong: (KMode) -> Unit) {
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLong)
    @Suppress("UNUSED_VARIABLE") val hasDns = showDns // DNS همیشه سر جایش می‌ماند (خواسته‌ی صاحب اپ)
    val s = dial / OrbBase
    // کمی بیرون‌تر از مدار طرح (ORBIT_PUSH) تا دکمه‌ها و انیمیشن‌هایشان به دکمه‌ی اتصال نخورند
    val dx = dial * ORBIT_DX + ORBIT_PUSH
    val dy = dial * ORBIT_DY
    val d = (ORBIT_MODE * s).coerceAtLeast(44.dp)
    val labelGap = 9.dp
    val labelH = 16.dp
    // x منفی = سمت شروع صفحه (offset خودش با جهت صفحه برمی‌گردد)، y مثبت = پایین
    val slots = listOf(KMode.SMART to (-dx to -dy), KMode.GAME to (-dx to dy), KMode.FILTER to (dx to -dy), KMode.DNS to (dx to dy))
    Box(modifier, contentAlignment = Alignment.Center) {
        slots.forEach { (m, xy) ->
            // مرکز دایره درست روی جای حالت، اسم زیرش
            Box(Modifier.offset(x = xy.first, y = xy.second)) {
                ModeOrb(m, m == selected, enabled, d, tap, long)
            }
            // اسم کمی رو به بیرون، تا روی قاب دکمه‌ی اتصال نیفتد
            Box(Modifier.offset(x = xy.first + (if (xy.first < 0.dp) (-6).dp else 6.dp), y = xy.second + d / 2 + labelGap + labelH / 2 - 2.dp)) {
                T(m.title, size = 10.5.sp, color = if (m == selected) K.Gold else K.Sub, weight = if (m == selected) FontWeight.Black else FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/**
 * دکمه‌ی گرد یک حالت: شیشه‌ی تمیز و تاریک (حالت فعال هم هیچ ته‌رنگی روی دکمه نمی‌گیرد) با آیکون به رنگ تم. حالت فعال: دو
 * نقطه‌ی نور با دنباله‌ی ظریف، ۱۸۰ درجه از هم، کمی بیرون از لبه آرام دورش می‌چرخند. زدن انتخاب، نگه‌داشتن پنلش را باز می‌کند.
 */
@Composable
private fun ModeOrb(m: KMode, sel: Boolean, enabled: Boolean, d: Dp, tap: (KMode) -> Unit, long: (KMode) -> Unit) {
    val ac = K.Gold
    val selA by animateFloatAsState(if (sel) 1f else 0f, tween(300), label = "sel")
    val press = remember { Animatable(1f) }
    val clock = motionTime()
    Box(
        Modifier.size(d + 24.dp).alpha(if (enabled) 1f else .5f)
            .graphicsLayer { scaleX = press.value; scaleY = press.value }
            .pointerInput(m, enabled) {
                if (enabled) detectTapGestures(
                    onPress = { press.animateTo(.92f, tween(80)); tryAwaitRelease(); press.animateTo(1f, spring(Spring.DampingRatioMediumBouncy)) },
                    onTap = { tap(m) },
                    onLongPress = { long(m) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (selA > .01f) Canvas(Modifier.fillMaxSize()) {
            // ۴dp فاصله از لبه‌ی دکمه تا لبه‌ی دنباله
            val tw = 3.4.dp.toPx()
            val r = d.toPx() / 2 + 4.dp.toPx() + tw / 2
            val tl = Offset(center.x - r, center.y - r)
            val sz = Size(r * 2, r * 2)
            val rot = clock.value * 70f % 360f
            for (k in 0 until 2) {
                val headDeg = rot + k * 180f
                // دنباله: چند تکه‌ی باریک که رو به سر پررنگ می‌شوند (بدون محو پهن)
                val n = 10
                for (i in 0 until n) {
                    val f = (i + 1f) / n
                    drawArc(lerp(ac, Color.White, .1f).copy(alpha = selA * (.35f + .65f * f)), headDeg - 70f + 70f * i / n, 70f / n + .5f, false, tl, sz, style = Stroke(tw * (.5f + .5f * f), cap = StrokeCap.Round))
                }
                val rad = Math.toRadians(headDeg.toDouble())
                val hp = Offset(center.x + r * kotlin.math.cos(rad).toFloat(), center.y + r * kotlin.math.sin(rad).toFloat())
                drawCircle(Color.Black.copy(alpha = .55f * selA), 5.8.dp.toPx(), hp)
                drawCircle(lerp(ac, Color.White, .15f).copy(alpha = selA), 5.dp.toPx(), hp)
                drawCircle(Color.White.copy(alpha = selA), 2.5.dp.toPx(), hp)
            }
        }
        Box(Modifier.size(d).raisedCircle(ac, 0f), contentAlignment = Alignment.Center) {
            ModeIcon(m, if (sel) ac else ac.copy(alpha = .8f), d * .46f)
        }
    }
}

/** یک خط باریک زیر کاشی‌ها: حالت انتخاب‌شده چه می‌کند */
@Composable
fun ModeHint(m: KMode, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(D.RS)
    Row(
        modifier.fillMaxWidth().goldGlass(shape)
            .padding(horizontal = D.S3, vertical = if (LocalCompact.current) D.S1 else D.S2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniIcon("spark", modeColor(m), 12.dp)
        Spacer(Modifier.width(D.S2))
        T(tr(m.title) + " : " + tr(m.sub), size = 11.sp, color = K.Sub, weight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
    }
}

// ================================================================== پنل هوشمند

data class SmartRowUi(val key: String, val flag: String, val title: String, val ping: Long)

data class SmartPanelUi(
    val open: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val status: String = "",
    val rows: List<SmartRowUi> = emptyList(),
    val current: String? = null,
)

/** ارتفاع هر ردیف پنل هوشمند (بدون فاصله‌ی بالایش) */
val SmartRowH = D.S6

/** پنل کوچک «هوشمند»: پیشرفت سنجش، بهترین‌ها و سروری که به آن وصل می‌شود؛ بعد از اتصال خودش بسته می‌شود */
@Composable
fun SmartPanel(u: SmartPanelUi, modifier: Modifier = Modifier) {
    val mc = modeColor(KMode.SMART)
    KCard(modifier.fillMaxWidth(), padding = D.CardPad, radius = D.RM, border = mc.copy(alpha = .45f)) {
        Row(Modifier.height(D.S5), verticalAlignment = Alignment.CenterVertically) {
            Spinner(14.dp, mc)
            Spacer(Modifier.width(D.S2))
            T(u.status, size = 12.sp, color = K.Text, weight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
            if (u.total > 0) T("${u.done}/${u.total}", size = 11.sp, color = K.Sub, font = Poppins, weight = FontWeight.SemiBold, ltr = true)
        }
        if (u.total > 0) {
            Spacer(Modifier.height(D.S2))
            ProgressLine(u.done.toFloat() / u.total.coerceAtLeast(1), mc, height = 4.dp)
        }
        u.rows.forEachIndexed { i, r ->
            val cur = r.key == u.current
            Row(Modifier.fillMaxWidth().padding(top = D.S1).height(SmartRowH), verticalAlignment = Alignment.CenterVertically) {
                if (cur) Spinner(12.dp, K.Green) else if (i == 0) MiniIcon("crown", mc, 12.dp) else Spacer(Modifier.width(12.dp))
                Spacer(Modifier.width(D.S2))
                T(r.flag, size = 13.sp, weight = FontWeight.Normal)
                Spacer(Modifier.width(D.S2))
                AutoText(r.title, size = 11.sp, color = if (cur) K.Green else K.Text, modifier = Modifier.weight(1f))
                PingChip(r.ping)
            }
        }
    }
}

// ================================================================== کارت حالت DNS

/** یک سرور DNS در فهرست: خودکار، مستقیم (سرور خارج) یا تانل (ریلی ایران ← سرور خارج) */
data class DnsRowUi(val id: String, val title: String, val role: String, val flag: String, val exitFlag: String = "", val exitTitle: String = "")

data class DnsCardUi(
    val hasDns: Boolean = false,
    /** سرورهایی که در پنل اضافه شده‌اند؛ شناسه‌ی خالی یعنی خودکار */
    val servers: List<DnsRowUi> = emptyList(),
    val pick: String = "",
    val active: String? = null,
    /** مسیر سرور فعال: «از ریلی ایران» یا «مستقیم» */
    val activeRoute: String? = null,
    /** مصرف این اتصال (دریافت + ارسال، بایت)؛ null وقتی وصل نیست */
    val sessionBytes: Long? = null,
)

class DnsActions(
    val pickDns: (String) -> Unit = {},
)

/**
 * کارت حالت DNS: دکمه‌ی «سرورهای DNS» که فهرست سرورهای پنل را باز می‌کند.
 * سرور مستقیم: پرچم و اسم سرور خارج. سرور تانل: 🇮🇷 ← پرچم خارج با برچسب «تانل»، تا معلوم باشد از ایران رد می‌شود.
 */
@Composable
fun DnsCard(u: DnsCardUi, vstate: VState, act: DnsActions, modifier: Modifier = Modifier) {
    val mc = modeColor(KMode.DNS)
    val shape = RoundedCornerShape(D.RM)
    val compact = LocalCompact.current
    var open by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().goldGlass(shape, tint = mc)
            .padding(horizontal = D.S3, vertical = if (compact) D.S1 else D.S2),
    ) {
        if (!u.hasDns) {
            T("اشتراک شما DNS ندارد", size = 12.sp, color = K.Warn, weight = FontWeight.Bold)
            return@Column
        }
        val cur = u.servers.firstOrNull { it.id == u.pick } ?: u.servers.firstOrNull()
        val bs = RoundedCornerShape(D.RS)
        Row(
            Modifier.fillMaxWidth().height(if (compact) D.HomeRowS else D.HomeRow).clip(bs)
                .background(Brush.horizontalGradient(listOf(mc.copy(alpha = .26f), mc.copy(alpha = .10f))))
                .border(1.dp, mc.copy(alpha = .7f), bs)
                .clickable { open = !open }
                .padding(horizontal = D.S3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("DNS", size = 11.sp, color = mc, font = Poppins, weight = FontWeight.Bold, ltr = true)
            Spacer(Modifier.width(D.S3))
            Column(Modifier.weight(1f)) {
                T("سرورهای DNS", size = 12.sp, color = K.Text, weight = FontWeight.Black)
                cur?.let { T(dnsRowLabel(it), size = 10.sp, color = K.Sub, weight = FontWeight.Normal, maxLines = 1) }
            }
            T(if (open) "▴" else "▾", size = 14.sp, color = mc)
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(top = D.S2), verticalArrangement = Arrangement.spacedBy(D.S1)) {
                u.servers.forEach { r ->
                    val sel = u.pick == r.id
                    val rs = RoundedCornerShape(D.RS)
                    Row(
                        Modifier.fillMaxWidth().clip(rs)
                            .background(if (sel) mc.copy(alpha = .16f) else Color(0x0CFFFFFF))
                            .border(1.dp, if (sel) mc else Color(0x18FFFFFF), rs)
                            .clickable(enabled = vstate != VState.CONNECTING) { act.pickDns(r.id); open = false }
                            .height(D.RowS).padding(horizontal = D.S3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            AutoText(dnsRowTitle(r), size = 12.5.sp, color = if (sel) mc else K.Text)
                            T(dnsRowTag(r), size = 10.sp, color = K.Sub, weight = FontWeight.Bold)
                        }
                        if (sel) CheckMark(true, D.Icon, mc)
                    }
                }
                if (vstate == VState.ON) T("برای عوض کردن سرور، یک بار قطع و وصل کن", size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
            }
        }
        u.active?.let {
            Spacer(Modifier.height(D.S1))
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(trf("وصل به: %s", it) + (u.activeRoute?.let { r -> " · " + tr(r) } ?: ""), size = 10.sp, color = K.Green, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                u.sessionBytes?.let { b -> T(trf("مصرف: %s", formatBytes(b)), size = 10.sp, color = mc, weight = FontWeight.Bold) }
            }
        }
    }
}

/** اسم سرور (برای تانل: اسم سرور خارجی که تانل به آن می‌رسد؛ بدون پرچم و اسم ایران) */
private fun dnsRowTitle(r: DnsRowUi): String = when (r.role) {
    "relay" -> r.exitTitle.ifBlank { r.title }
    "auto" -> tr("خودکار")
    else -> r.title
}

private fun dnsRowTag(r: DnsRowUi): String = tr(when (r.role) { "relay" -> "تانل"; "auto" -> "سریع‌ترین"; else -> "مستقیم" })

private fun dnsRowLabel(r: DnsRowUi): String = dnsRowTitle(r) + " · " + dnsRowTag(r)

// ================================================================== پنل‌های پایین (گیمینگ و ضد فیلتر)

/** قاب پنل پایین با پرده‌ی تیره روی نقشه؛ با لمس بیرون بسته می‌شود */
@Composable
fun ModeSheet(
    visible: Boolean, mode: KMode, title: String, sub: String, onClose: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val mc = modeColor(mode)
    AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Box(
            Modifier.fillMaxSize().background(Color(0xA6000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible,
            enter = slideInVertically(tween(260)) { it } + fadeIn(),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(),
        ) {
            val shape = RoundedCornerShape(topStart = D.RX, topEnd = D.RX)
            // کشیدن سربرگ پنل به پایین می‌بندد (۱.۲۹)؛ کمتر از ۹۶dp با فنر برمی‌گردد
            val drag = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            val dragMod = Modifier.pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (drag.value > 96.dp.toPx()) { onClose(); scope.launch { drag.snapTo(0f) } }
                        else scope.launch { drag.animateTo(0f, spring(Spring.DampingRatioMediumBouncy)) }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f) } },
                ) { _, dy -> scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) } }
            }
            Column(
                Modifier.fillMaxWidth().heightIn(max = 660.dp)
                    .graphicsLayer { translationY = drag.value }
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(lerp(K.BgMid, Color.White, .09f), lerp(K.BgMid, Color.Black, .35f))))
                    .background(Brush.linearGradient(listOf(Color.White.copy(alpha = .06f), Color.Transparent, Color.Black.copy(alpha = .1f))))
                    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .35f), mc.copy(alpha = .25f), Color.Transparent)), shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .padding(horizontal = D.PagePad, vertical = D.S2),
            ) {
                Box(Modifier.fillMaxWidth().height(D.S5).then(dragMod), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(D.S6 * 2).height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = .25f)))
                }
                Spacer(Modifier.height(D.S1))
                Row(Modifier.then(dragMod), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(D.IconBox + D.S1).goldFill(CircleShape), contentAlignment = Alignment.Center) { ModeIcon(mode, K.OnGold, D.S5) }
                    Spacer(Modifier.width(D.S3))
                    Column(Modifier.weight(1f)) {
                        T(title, size = D.TxtPage, color = K.Text, weight = FontWeight.Black)
                        T(sub, size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal)
                    }
                    trailing()
                    Spacer(Modifier.width(D.S2))
                    // دکمه‌ی بستن (قبلاً فقط با لمس روی نقشه بسته می‌شد)
                    RoundIcon(D.IconBox, K.Sub, onClick = onClose) {
                        Canvas(Modifier.size(14.dp)) {
                            val w = size.width
                            drawLine(K.Text, Offset(0f, 0f), Offset(w, w), w * .16f, StrokeCap.Round)
                            drawLine(K.Text, Offset(w, 0f), Offset(0f, w), w * .16f, StrokeCap.Round)
                        }
                    }
                }
                Spacer(Modifier.height(D.S2))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = D.S3, bottom = D.S2, start = D.S1, end = D.S1), verticalAlignment = Alignment.CenterVertically) {
        T(text, size = 12.sp, color = K.Sub, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** برچسب کوچک گرد */
@Composable
private fun Tag(text: String, c: Color, strong: Boolean = false) {
    val sh = RoundedCornerShape(D.RT)
    T(
        text, size = 9.5.sp, color = c, weight = if (strong) FontWeight.Black else FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(sh).background(c.copy(alpha = if (strong) .18f else .10f)).border(1.dp, c.copy(alpha = .35f), sh)
            .padding(horizontal = D.S2, vertical = 2.dp),
    )
}

@Composable
private fun Radio(on: Boolean, c: Color) {
    Box(
        Modifier.size(D.Icon).clip(CircleShape).border(1.6.dp, if (on) c else K.Sub.copy(alpha = .6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(c)) }
}

@Composable
private fun SwitchRow(title: String, sub: String?, on: Boolean, color: Color, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = D.S1), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(title, size = 13.sp, color = K.Text, weight = FontWeight.Bold)
            sub?.let { T(it, size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal) }
        }
        GlassSwitch(on, onChange, enabled)
    }
}

/** یک سرور در پنل گیمینگ / ضد فیلتر: پرچم، اسم، برچسب (تانل، مستقیم، …)، پینگ و راهش (tunnel / direct) */
data class SheetServer(val node: String, val proto: String, val flag: String, val name: String, val tag: String?, val ping: Long?, val side: String = "direct", val q: Modes.GameQ? = null, val group: String = "")

/** یک سرور با دو راهش کنار هم: کانفیگ تانل (راست) و کانفیگ مستقیم (چپ)؛ هر کدام ممکن است نباشد */
data class SheetPair(val flag: String, val name: String, val tunnel: SheetServer?, val direct: SheetServer?) {
    /** شناسه‌ی ثابت ردیف (اسم دو سرور می‌تواند یکی باشد) */
    val id: String get() = (tunnel?.let { it.node + "|" + it.proto } ?: "-") + "/" + (direct?.let { it.node + "|" + it.proto } ?: "-")
    val best: Long? get() = listOfNotNull(tunnel?.ping, direct?.ping).filter { it > 0 }.minOrNull()
}

/**
 * کانفیگ‌های یک پروتکل، هر سرور یک ردیف: تانل و مستقیم همان سرور روبه‌روی هم (حتی اگر تانل سرور دیگری پینگ بهتری
 * داشته باشد)؛ ردیف‌ها به ترتیب بهترین پینگ هر سرور.
 */
fun pairsOf(list: List<SheetServer>): List<SheetPair> =
    list.groupBy { it.node.removeSuffix(TUNNEL_TWIN) }.values.flatMap { g ->
        val t = g.filter { it.side == "tunnel" }
        val d = g.filter { it.side != "tunnel" }
        val head = g.first()
        (0 until maxOf(t.size, d.size)).map { i -> SheetPair(head.flag, head.name, t.getOrNull(i), d.getOrNull(i)) }
    }.sortedBy { it.best ?: 99_999L }

/** لینک‌های Xray، جفت‌شده با «group» پنل (یا اسم ردیف: کشور خروج · نوع پروتکل): هر ردیف یک خانه‌ی تانل و یک خانه‌ی مستقیم */
fun xrayPairs(list: List<SheetServer>): List<SheetPair> =
    list.groupBy { it.group.ifBlank { it.name } }.values.flatMap { g ->
        val t = g.filter { it.side == "tunnel" }
        val d = g.filter { it.side != "tunnel" }
        // پرچم و اسم ردیف از لینک مستقیم (مکان خروج خود سرور)، نه از کپی تانل
        val head = d.firstOrNull() ?: g.first()
        (0 until maxOf(t.size, d.size)).map { i -> SheetPair(head.flag, head.name, t.getOrNull(i), d.getOrNull(i)) }
    }.sortedBy { it.best ?: 99_999L }

/** یک خانواده‌ی پروتکل در پنل گیمینگ (KANKI MIX، Amnezia، WireGuard) با سرورهای خودش */
data class GameFamilyUi(val key: String, val title: String, val sub: String, val servers: List<SheetServer>) {
    val best: Long? get() = servers.mapNotNull { it.ping }.filter { it > 0 }.minOrNull()
}

/** دکمه‌ی کپسولی کوچک یک خانواده‌ی پروتکل (۱.۳۲.۳): فقط اسم؛ انتخاب‌شده شیشه‌ی نئونی */
@Composable
private fun FamilyCard(f: GameFamilyUi, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = familyColor(f.key)
    val has = f.servers.isNotEmpty()
    // ۱.۳۴.۲: شیشه‌ی تم با فشردگی نرم هنگام لمس و پالس نئونی دورنگ (رنگ اصلی تا رنگ دوم تم) دور کپسول انتخاب‌شده
    val press = remember { MutableInteractionSource() }
    val down by press.collectIsPressedAsState()
    val k by animateFloatAsState(if (down) .95f else 1f, spring(dampingRatio = .55f, stiffness = 700f), label = "famPress")
    val selA by animateFloatAsState(if (selected) 1f else 0f, tween(300), label = "famSel")
    val clock = motionTime()
    Box(
        modifier.height(36.dp)
            .graphicsLayer { scaleX = k; scaleY = k }
            .drawBehind {
                if (selA > .01f) {
                    val pulse = .5f + .5f * kotlin.math.sin(clock.value * 3.2f)
                    val o = CircleShape.createOutline(size, layoutDirection, this)
                    val br = Brush.linearGradient(listOf(c, K.Gold2, c), Offset.Zero, Offset(size.width, size.height))
                    drawOutline(o, br, alpha = selA * (.10f + .14f * pulse), style = Stroke((5f + 3f * pulse).dp.toPx()))
                }
            }
            .goldGlass(CircleShape, selected = selected, tint = c)
            .alpha(if (has) 1f else .4f)
            .clickable(enabled = has, interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick)
            .padding(horizontal = D.S2),
        contentAlignment = Alignment.Center,
    ) {
        AutoText(f.title, size = 12.sp, color = if (selected) Color.White else K.Text)
    }
}

/** یک سرور در یک ردیف باریک (۱.۳۲): پرچم گرد و اسم، و کنارش دو خانه‌ی کوچک تانل و مستقیم؛ لمس هر خانه = اتصال به همان */
@Composable
private fun PairRow(p: SheetPair, current: String?, auto: Boolean, color: Color, enabled: Boolean, tunnelOnly: Boolean = false, onClick: (SheetServer) -> Unit) {
    val shape = RoundedCornerShape(D.RM)
    val lit = !auto && listOfNotNull(p.tunnel, p.direct).any { current == it.node + "|" + it.proto }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 7.dp).height(52.dp).goldGlass(shape, selected = lit, tint = color).padding(horizontal = D.S2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = .4f)).border(1.5.dp, Color.White.copy(alpha = .4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { T(p.flag, size = 17.sp, weight = FontWeight.Normal) }
        Spacer(Modifier.width(D.S2))
        AutoText(p.name, size = 13.sp, color = K.Text, modifier = Modifier.weight(1f))
        PairCell(p.tunnel, "تانل", !auto && p.tunnel != null && current == p.tunnel.node + "|" + p.tunnel.proto, color, enabled, Modifier.width(66.dp), onClick)
        if (!tunnelOnly) Spacer(Modifier.width(6.dp))
        if (!tunnelOnly) PairCell(p.direct, "مستقیم", !auto && p.direct != null && current == p.direct.node + "|" + p.direct.proto, color, enabled, Modifier.width(66.dp), onClick)
    }
}

/**
 * یک خانه: دکمه‌ی انتخاب، اسم راه (تانل، مستقیم یا اسمی که ادمین گذاشته) و پینگ؛ زیرش پایداری برای بازی (پایدار / متوسط / ضعیف
 * و درصد افت، از سنجش واقعی). جای خط دوم همیشه هست تا با رسیدن سنجش چیزی جابه‌جا نشود. خالی وقتی این سرور آن راه را ندارد.
 */
@Composable
private fun PairCell(sv: SheetServer?, label: String, selected: Boolean, color: Color, enabled: Boolean, modifier: Modifier, onClick: (SheetServer) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.height(38.dp)
            .then(if (sv != null) Modifier.goldGlass(shape, selected = selected, tint = color) else Modifier.clip(shape).border(1.dp, Color.White.copy(alpha = .06f), shape))
            .alpha(if (sv == null) .4f else if (enabled) 1f else .55f)
            .clickable(enabled = enabled && sv != null) { sv?.let(onClick) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (sv == null) { T("—", size = 12.sp, color = K.Sub, ltr = true); return@Column }
        // اسم راه و نقطه‌ی پایداری (سبز پایدار، زرد متوسط، قرمز ضعیف؛ خاکستری تا سنجش برسد)
        val q = sv.q
        val dot = when {
            sv.ping != null && sv.ping < 0 -> K.Sub
            q == null -> K.Sub.copy(alpha = .5f)
            else -> when (Modes.stability(q)) { 0 -> K.Ok; 1 -> K.Warn; else -> K.Red }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(3.dp))
            T(label, size = 10.sp, color = Color.White, weight = FontWeight.Bold, maxLines = 1)
        }
        val p = sv.ping
        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.Bottom) {
                T(when { p == null -> "…"; p < 0 -> "✕"; else -> "$p" }, size = 12.sp, color = if (p != null && p > 0) K.Text else K.Sub, font = Poppins, weight = FontWeight.Bold, ltr = true, maxLines = 1)
                if (p != null && p > 0) {
                    Spacer(Modifier.width(2.dp))
                    T("ms", size = 8.5.sp, color = K.Sub, font = Poppins, ltr = true, modifier = Modifier.padding(bottom = 1.dp))
                }
            }
        }
    }
}

/** خط دوم هر خانه: نقطه‌ی رنگی و «پایدار / متوسط / ضعیف · افت ٪»؛ تا سنجش برسد «در حال سنجش…» */
@Composable
private fun StabilityLine(q: Modes.GameQ?, ping: Long?) {
    val (txt, col) = when {
        ping != null && ping < 0 -> tr("بدون جواب") to K.Sub
        q == null -> tr("در حال سنجش…") to K.Sub
        else -> {
            val lv = Modes.stability(q)
            val name = when (lv) { 0 -> tr("پایدار"); 1 -> tr("متوسط"); else -> tr("ضعیف") }
            val c = when (lv) { 0 -> K.Ok; 1 -> K.Warn; else -> K.Red }
            // درصد افت فقط وقتی سنجش به‌اندازه‌ی کافی بسته داشت
            (if (q.n >= Modes.LOSS_MIN_SAMPLES) name + " · " + trf("افت %d٪", q.loss) else name) to c
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = D.S1, top = 2.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(col))
        Spacer(Modifier.width(D.S1))
        T(txt, size = 10.sp, color = if (q == null) K.Sub else col, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * جای ثابت عدد پینگ: «…» تا جواب بیاید، بعد عدد یا ✕. عرضش ثابت است تا با رسیدن پینگ اسم کنارش جابه‌جا نشود.
 */
@Composable
private fun PingSlot(p: Long?, size: androidx.compose.ui.unit.TextUnit, width: androidx.compose.ui.unit.Dp = 50.dp) {
    Box(Modifier.width(width), contentAlignment = Alignment.CenterEnd) {
        if (p == null) T("…", size = size, color = K.Sub, font = Poppins, ltr = true, maxLines = 1)
        else T(if (p < 0) "✕" else "$p ms", size = size, color = pingColor(p), font = Poppins, weight = FontWeight.SemiBold, ltr = true, maxLines = 1)
    }
}

/** یک ردیف سرور: لمس یعنی همین حالا به همین وصل شو */
@Composable
private fun ServerRow(sv: SheetServer, selected: Boolean, color: Color, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(D.RS)
    Row(
        Modifier.fillMaxWidth().padding(bottom = D.S2).height(D.RowH).clip(shape)
            .background(if (selected) color.copy(alpha = .12f) else Color(0x08FFFFFF))
            .border(if (selected) 1.3.dp else 1.dp, if (selected) color.copy(alpha = .85f) else K.Gold.copy(alpha = .16f), shape)
            .alpha(if (enabled) 1f else .55f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = D.S3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Radio(selected, color)
        Spacer(Modifier.width(D.S3))
        T(sv.flag, size = 15.sp, weight = FontWeight.Normal)
        Spacer(Modifier.width(D.S2))
        AutoText(sv.name, size = 13.sp, color = K.Text, modifier = Modifier.weight(1f))
        sv.tag?.let { Tag(it, color); Spacer(Modifier.width(D.S2)) }
        PingSlot(sv.ping, 11.sp)
    }
}

/**
 * ردیف «بهترین سرور برای بازی» (۱.۳۲): قاب ویژه با لبه‌ی گرادیان نئونی، آیکون صاعقه در دکمه‌ی گرد پر، برچسب «پایدار» و
 * پینگ؛ خودش پایدارترین سرور همین پروتکل را انتخاب و وصل می‌کند.
 */
@Composable
private fun FastestRow(
    selected: Boolean, color: Color, best: Long?, enabled: Boolean,
    title: String = "بهترین سرور برای بازی", sub: String = "پایدارترین را با پینگ، لرزش و افت پیدا و وصل می‌کند",
    steady: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(D.RL)
    Row(
        Modifier.fillMaxWidth().padding(bottom = D.S2).height(D.RowH + 4.dp)
            .drawBehind {
                val o = shape.createOutline(size, layoutDirection, this)
                liftShadow(o, 6.dp.toPx(), .5f, color)
                for ((m, a) in listOf(10f to .05f, 5f to .12f)) drawOutline(o, color.copy(alpha = a * (if (selected) 1.5f else 1f)), style = Stroke(m.dp.toPx()))
            }
            .clip(shape)
            .background(Brush.linearGradient(listOf(lerp(K.BgMid, color, .16f), lerp(K.BgMid, Color.Black, .45f))))
            .border(1.5.dp, Brush.linearGradient(listOf(lerp(color, Color.White, .5f), color, Color.Transparent, color.copy(alpha = .5f), lerp(color, Color.White, .5f))), shape)
            .alpha(if (enabled) 1f else .5f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = D.S3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).goldFill(CircleShape), contentAlignment = Alignment.Center) { MiniIcon("bolt", K.OnGold, 15.dp) }
        Spacer(Modifier.width(D.S3))
        Column(Modifier.weight(1f)) {
            T(title, size = D.TxtTitle, color = K.Text, weight = FontWeight.Black, maxLines = 1)
            T(sub, size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal, maxLines = 1)
        }
        Spacer(Modifier.width(D.S2))
        val sh = RoundedCornerShape(50)
        // برچسب «پایدار» فقط برای گیمینگ (که با لرزش و افت انتخاب می‌کند)؛ ضد فیلتر با پینگ و وصل شدن انتخاب می‌کند
        if (steady) Row(
            Modifier.clip(sh).background(K.Good.copy(alpha = .15f)).border(1.dp, K.Good.copy(alpha = .4f), sh).padding(horizontal = D.S2, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // رنگ وضعیت ثابت (سبز «خوب») در هر تم
            Dot(K.Good, 5.dp)
            Spacer(Modifier.width(4.dp))
            T("پایدار", size = 10.sp, color = K.Good, weight = FontWeight.Black, maxLines = 1)
        }
        // جای عدد همیشه هست (خالی تا اولین پینگ): متن کنارش با رسیدن عدد جابه‌جا نمی‌شود
        Box(Modifier.width(48.dp), contentAlignment = Alignment.CenterEnd) {
            best?.let { T("$it ms", size = 12.sp, color = K.Text, font = Poppins, weight = FontWeight.Bold, ltr = true, maxLines = 1) }
        }
    }
}

/**
 * پنل گیمینگ: بالا چهار پروتکل هم‌اندازه (KANKI MIX اول، Hysteria2، Amnezia، WireGuard)؛ زیرش سرورهای همان
 * پروتکل در دو ستون، تانل راست و مستقیم چپ، هر سرور یک ردیف. لمس یک خانه = اتصال به همان.
 * «سریع‌ترین سرور» کم‌پینگ‌ترین کانفیگ همان پروتکل را برمی‌دارد.
 * برای KANKI MIX کلید «ارسال دوتایی» هست. همه‌ی برنامه‌ها (بازی، سرویس‌های گوگل، مرورگر) از VPN رد می‌شوند.
 */
@Composable
fun GameSheet(
    visible: Boolean,
    families: List<GameFamilyUi>,
    initFamily: String,
    current: String?,
    currentAuto: Boolean,
    busy: Boolean,
    kmixDup: Boolean,
    onKmixDup: (Boolean) -> Unit,
    gameOnly: Boolean,
    gameCount: Int,
    onGameOnly: (Boolean) -> Unit,
    onPickGames: () -> Unit,
    onFamilyShown: (String) -> Unit,
    onConnect: (node: String, proto: String) -> Unit,
    onFastest: (family: String) -> Unit,
    onClose: () -> Unit,
) {
    var fam by remember(visible) {
        mutableStateOf(initFamily.takeIf { k -> families.any { it.key == k && it.servers.isNotEmpty() } }
            ?: families.firstOrNull { it.servers.isNotEmpty() }?.key ?: initFamily)
    }
    LaunchedEffect(visible, fam) { if (visible) onFamilyShown(fam) }
    val f = families.firstOrNull { it.key == fam }
    val c = familyColor(fam)
    ModeSheet(visible, KMode.GAME, "گیمینگ", "پروتکل را انتخاب کن، بعد سرور را بزن", onClose) {
        // سه کارت کوچک در هر ردیف (طرح شیشه‌ای ۱.۳۲): اسم، پینگ و نوار پایداری
        Column(Modifier.fillMaxWidth().padding(top = D.S1).guideTarget("game_fams"), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            families.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    row.forEach { x -> FamilyCard(x, x.key == fam, Modifier.weight(1f)) { fam = x.key } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        // تنظیمات (KPING، KANKI MIX، microtun) پشت یک دکمه‌ی کوچک، تا فهرست سرورها اول دیده شود
        val has = f?.servers?.isNotEmpty() == true
        val dupOn = fam == "kankimix" && has
        val gateOn = (fam == "kankimix" || fam == "mtun") && has
        val kpOn = fam == "kping" && has
        var setOpen by remember(fam) { mutableStateOf(false) }
        if (dupOn || gateOn || kpOn) {
            SmallLine(if (setOpen) "بستن تنظیمات" else "تنظیمات", Modifier.padding(top = D.S2).then(if (gateOn) Modifier.guideTarget("game_only") else Modifier), onClick = { setOpen = !setOpen })
            androidx.compose.animation.AnimatedVisibility(setOpen) {
                if (kpOn) KpingPanel(c) else Column(
                    Modifier.fillMaxWidth().padding(top = D.S2).goldGlass(RoundedCornerShape(D.RL)).padding(horizontal = D.S3, vertical = D.S1),
                ) {
                    if (dupOn) SwitchRow("ارسال دوتایی بسته‌ها", "هر بسته‌ی بازی دو بار می‌رود: پکت‌لاس اثر ندارد (کمی حجم بیشتر)", kmixDup, c, onChange = onKmixDup)
                    if (dupOn && gateOn) Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .1f), Color.Transparent))))
                    // فایروال «فقط بازی»: فقط پروتکل‌های مخصوص بازی (KANKI MIX و microtun)، اندروید ۱۰ به بالا
                    if (gateOn) {
                        val ok = AppGate.supported
                        Box { SwitchRow(
                            "فقط بازی اینترنت داشته باشد",
                            if (!ok) "روی اندروید ۱۰ به بالا" else trf("%d بازی · سرویس‌های گوگل و DNS هم باز می‌مانند؛ بقیه‌ی برنامه‌ها تا قطع VPN اینترنت ندارند", gameCount),
                            gameOnly && ok, c, enabled = ok, onChange = onGameOnly,
                        ) }
                        if (gameOnly && ok) SmallLine("انتخاب بازی‌ها", Modifier.padding(bottom = D.S2), onClick = onPickGames)
                    }
                }
            }
        }
        SheetLabel(trf("سرورهای %s", f?.title ?: "")) {
            if (f != null && f.servers.isNotEmpty()) T(if (fam == "kping") trf("%d سرور · تانل", pairsOf(f.servers).size) else trf("%d سرور", pairsOf(f.servers).size) + " · " + tr("مستقیم / تانل"), size = 11.sp, color = K.Sub, weight = FontWeight.Normal, maxLines = 1)
        }
        if (f == null || f.servers.isEmpty()) {
            T("این پروتکل در اشتراک شما نیست.", size = 11.sp, color = K.Warn, weight = FontWeight.Normal)
        } else {
            Column(Modifier.fillMaxWidth().guideTarget("game_list")) {
                FastestRow(currentAuto && Modes.familyOf(current?.substringAfter("|") ?: "") == fam, c, f.best, !busy) { onFastest(fam) }
                // ترتیب سرورها با باز شدن پنل (یا عوض شدن پروتکل) یک بار ثابت می‌شود: پینگ‌هایی که بعد می‌رسند فقط
                // عددها را عوض می‌کنند، نه جای ردیف‌ها (وگرنه ردیف زیر انگشت کاربر جابه‌جا می‌شد)
                val pairs = pairsOf(f.servers)
                val order = remember(visible, fam) { mutableListOf<String>() }
                // سرور تازه (مثلاً بعد از بروزرسانی اشتراک) ته فهرست اضافه می‌شود
                pairs.forEach { if (it.id !in order) order.add(it.id) }
                pairs.sortedBy { order.indexOf(it.id) }.forEach { p ->
                    key(p.id) { PairRow(p, current, currentAuto, c, !busy, tunnelOnly = fam == "kping") { sv -> onConnect(sv.node, sv.proto) } }
                }
            }
        }

        Spacer(Modifier.height(D.S2))
    }
}

/**
 * پنل ضد فیلتر (نگه‌داشتن کاشی «ضد فیلتر»): فقط سرورهای Xray و Amnezia، نه همه‌ی سرورها.
 * دو تیک بالا (Xray و Amnezia) هم فهرست را عوض می‌کنند و هم اینکه «خودکار» بین کدام‌ها بگردد.
 */
@Composable
fun FilterSheet(
    visible: Boolean,
    xray: List<SheetServer>,
    amnezia: List<SheetServer>,
    useX: Boolean,
    useAwg: Boolean,
    auto: Boolean,
    current: String?,
    busy: Boolean,
    onTicks: (Boolean, Boolean) -> Unit,
    onShown: () -> Unit,
    onAuto: () -> Unit,
    onConnect: (node: String, proto: String) -> Unit,
    onClose: () -> Unit,
) {
    val mc = modeColor(KMode.FILTER)
    LaunchedEffect(visible) { if (visible) onShown() }
    ModeSheet(visible, KMode.FILTER, "ضد فیلتر", "Xray و Amnezia برای محدودیت شدید شبکه", onClose) {
        SheetLabel("پروتکل‌ها")
        Row(Modifier.fillMaxWidth().guideTarget("filter_list"), horizontalArrangement = Arrangement.spacedBy(D.S2)) {
            TickChip("Xray", trf("%d سرور", xrayPairs(xray).size), useX, protoColor("xray"), Modifier.weight(1f)) { onTicks(!useX, useAwg) }
            TickChip("Amnezia", trf("%d سرور", pairsOf(amnezia).size), useAwg, protoColor("amneziawg"), Modifier.weight(1f)) { onTicks(useX, !useAwg) }
        }
        val list = (if (useX) xray else emptyList()) + (if (useAwg) amnezia else emptyList())
        Spacer(Modifier.height(D.S3))
        FastestRow(
            auto, mc, list.mapNotNull { it.ping }.filter { it > 0 }.minOrNull(), !busy && list.isNotEmpty(),
            title = "اتصال خودکار ضد فیلتر", sub = "کم‌پینگ‌ترینِ تیک‌خورده‌ها را پیدا و وصل می‌کند", steady = false, onClick = onAuto,
        )
        if (useX) {
            SheetLabel("Xray")
            if (xray.isEmpty()) T("اشتراک شما Xray ندارد.", size = 11.sp, color = K.Warn, weight = FontWeight.Normal)
            // مثل Amnezia و گیمینگ: هر کشور و نوع پروتکل یک ردیف با دو خانه‌ی تانل و مستقیم و پینگ زنده (اسم راه داخل خود خانه است)
            xrayPairs(xray).forEach { p -> PairRow(p, current, auto, protoColor("xray"), !busy) { sv -> onConnect(sv.node, sv.proto) } }
        }
        if (useAwg) {
            SheetLabel("Amnezia")
            if (amnezia.isEmpty()) T("اشتراک شما Amnezia ندارد.", size = 11.sp, color = K.Warn, weight = FontWeight.Normal)
            pairsOf(amnezia).forEach { p -> PairRow(p, current, auto, protoColor("amneziawg"), !busy) { sv -> onConnect(sv.node, sv.proto) } }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** چیپ تیک‌دار بزرگ (Xray / Amnezia) */
@Composable
private fun TickChip(title: String, sub: String, on: Boolean, c: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(D.RS)
    Row(
        modifier.height(D.RowH).goldGlass(shape, selected = on, tint = c)
            .clickable(onClick = onClick)
            .padding(horizontal = D.S3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckMark(on, D.Icon, c)
        Spacer(Modifier.width(D.S2))
        Column {
            T(title, size = 13.5.sp, color = K.Text, weight = FontWeight.Black, font = Poppins, ltr = true)
            T(sub, size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
        }
    }
}

/** رنگ هر پروتکل (حلقه و هاله‌ی دکمه‌ی اتصال، کارت‌ها)؛ همه از تُن‌های همان تم، تا کنار هم یکدست بمانند */
fun protoColor(p: String?): Color = when (p) {
    "kankimix" -> ThemeState.current.mix
    "mtun" -> ThemeState.current.connected
    "kping" -> ThemeState.current.modeGame
    "amneziawg", "amneziawg-direct" -> ThemeState.current.modeAnti
    "wireguard" -> ThemeState.current.modeDns
    "hysteria2" -> ThemeState.current.connected
    "xray" -> K.Gold
    else -> K.Gold
}

fun familyColor(f: String): Color = when (f) {
    "kankimix" -> protoColor("kankimix")
    "mtun" -> protoColor("mtun")
    "kping" -> protoColor("kping")
    "hysteria2" -> protoColor("hysteria2")
    "amnezia" -> protoColor("amneziawg")
    else -> protoColor("wireguard")
}

/** آیکون خانواده‌ی پروتکل: KANKI MIX دسته‌ی بازی با جرقه، Hysteria2 صاعقه، Amnezia سپر، WireGuard موج */
fun DrawScope.familyIcon(f: String, c: Color) {
    val w = size.width
    val st = Stroke(w * .09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (f) {
        "kankimix", "mtun", "kping" -> {
            modeLogo(KMode.GAME, c)
            drawCircle(c, w * .07f, Offset(w * .88f, w * .14f))
        }
        "hysteria2" -> {
            // صاعقه: سرعت روی UDP
            val bolt = Path().apply {
                moveTo(w * .58f, w * .06f); lineTo(w * .2f, w * .56f); lineTo(w * .48f, w * .56f)
                lineTo(w * .4f, w * .94f); lineTo(w * .8f, w * .42f); lineTo(w * .52f, w * .42f); close()
            }
            drawPath(bolt, c, style = st)
        }
        "amnezia" -> {
            val sh = Path().apply {
                moveTo(w * .5f, w * .06f); lineTo(w * .88f, w * .2f); lineTo(w * .88f, w * .48f)
                cubicTo(w * .88f, w * .72f, w * .72f, w * .88f, w * .5f, w * .96f)
                cubicTo(w * .28f, w * .88f, w * .12f, w * .72f, w * .12f, w * .48f); lineTo(w * .12f, w * .2f); close()
            }
            drawPath(sh, c, style = st)
            drawLine(c, Offset(w * .36f, w * .52f), Offset(w * .47f, w * .63f), w * .09f, StrokeCap.Round)
            drawLine(c, Offset(w * .47f, w * .63f), Offset(w * .66f, w * .40f), w * .09f, StrokeCap.Round)
        }
        else -> {
            val wave = Path().apply {
                moveTo(w * .08f, w * .55f)
                cubicTo(w * .25f, w * .25f, w * .4f, w * .25f, w * .5f, w * .5f)
                cubicTo(w * .6f, w * .75f, w * .75f, w * .75f, w * .92f, w * .45f)
            }
            drawPath(wave, c, style = st)
            drawCircle(c, w * .08f, Offset(w * .92f, w * .45f))
        }
    }
}
