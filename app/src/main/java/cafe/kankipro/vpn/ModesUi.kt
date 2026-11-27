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
fun ModeOrbit(selected: KMode, dial: Dp, enabled: Boolean, modifier: Modifier = Modifier, showDns: Boolean = true, live: Boolean = false, off: Set<KMode> = emptySet(), onTap: (KMode) -> Unit, onLong: (KMode) -> Unit) {
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
                Box(Modifier.alpha(if (m in off) .4f else 1f)) { ModeOrb(m, m == selected, enabled, d, tap, long) }
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

/**
 * پاپ‌آپ شناور گیمینگ و ضد فیلتر (۱.۴۱): کارت شیشه‌ای تیره با فاصله‌ی ۲۰dp از پایین و دو لبه، گوشه‌ی ۲۴dp و ارتفاع ثابت
 * (۶۴٪ صفحه) در همه‌ی پروتکل‌ها؛ فهرست کم هم بالا چیده می‌شود و قاب کوچک نمی‌شود، فهرست زیاد داخل خودش اسکرول می‌خورد.
 * سربرگ یک خط: آیکون و عنوان، و آن طرف چرخ‌دنده و ✕. در نمای تنظیمات به‌جای چرخ‌دنده «بازگشت به سرورها».
 * با لمس بیرون هم بسته می‌شود.
 */
@Composable
fun ModeSheet(
    visible: Boolean, mode: KMode, title: String, onClose: () -> Unit,
    inSettings: Boolean = false, onSettings: (() -> Unit)? = null, onBack: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val mc = modeColor(mode)
    AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Box(
            Modifier.fillMaxSize().background(Color(0x99000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
    }
    val h = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .64f).dp
    Box(Modifier.fillMaxSize().padding(SHEET_GAP), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible,
            enter = slideInVertically(tween(280)) { it / 3 } + fadeIn(tween(240)),
            exit = slideOutVertically(tween(220)) { it / 3 } + fadeOut(tween(180)),
        ) {
            val shape = RoundedCornerShape(SHEET_R)
            Column(
                Modifier.fillMaxWidth().height(h)
                    .drawBehind { liftShadow(shape.createOutline(size, layoutDirection, this), 14.dp.toPx(), .7f, mc) }
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(lerp(K.BgMid, Color.Black, .25f).copy(alpha = .97f), lerp(K.BgMid, Color.Black, .55f).copy(alpha = .98f))))
                    .background(Brush.linearGradient(listOf(Color.White.copy(alpha = .07f), Color.Transparent, mc.copy(alpha = .05f))))
                    .border(1.dp, Brush.verticalGradient(listOf(mc.copy(alpha = .55f), mc.copy(alpha = .18f), Color.White.copy(alpha = .06f))), shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .padding(start = D.S4, end = D.S4, top = D.S3),
            ) {
                // سربرگ یک‌خطی
                Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).goldFill(CircleShape), contentAlignment = Alignment.Center) { ModeIcon(mode, K.OnGold, 18.dp) }
                    Spacer(Modifier.width(D.S2))
                    T(if (inSettings) trf("تنظیمات %s", tr(title)) else title, size = 17.sp, color = K.Text, weight = FontWeight.Black, maxLines = 1, modifier = Modifier.weight(1f))
                    if (inSettings) {
                        // بازگشت به فهرست سرورها: دکمه‌ی گرد کوچک با فلش، کنار ✕ (عنوان کامل دیده شود)
                        RoundIcon(D.IconBox, mc, onClick = onBack) { Canvas(Modifier.size(14.dp)) { backArrow(K.Text) } }
                    } else if (onSettings != null) {
                        Box(Modifier.guideTarget(if (mode == KMode.GAME) "game_only" else "filter_set")) {
                            RoundIcon(D.IconBox, mc, onClick = onSettings) { Canvas(Modifier.size(18.dp)) { gearIcon(K.Text) } }
                        }
                    }
                    Spacer(Modifier.width(D.S2))
                    RoundIcon(D.IconBox, K.Sub, onClick = onClose) {
                        Canvas(Modifier.size(13.dp)) {
                            val w = size.width
                            drawLine(K.Text, Offset(0f, 0f), Offset(w, w), w * .17f, StrokeCap.Round)
                            drawLine(K.Text, Offset(w, 0f), Offset(0f, w), w * .17f, StrokeCap.Round)
                        }
                    }
                }
                Spacer(Modifier.height(D.S2))
                Column(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
    }
}

private val SHEET_GAP = 20.dp
private val SHEET_R = 24.dp

/** چرخ‌دنده‌ی تنظیمات (بی متن) */
fun DrawScope.gearIcon(c: Color) {
    val w = size.minDimension
    val ctr = center
    val teeth = 8
    for (i in 0 until teeth) {
        rotate(i * 360f / teeth, ctr) {
            drawRoundRect(c, Offset(ctr.x - w * .09f, ctr.y - w * .5f), Size(w * .18f, w * .26f), CornerRadius(w * .04f))
        }
    }
    drawCircle(c, w * .33f, ctr)
    drawCircle(Color.Black.copy(alpha = .55f), w * .14f, ctr)
}

/** فلش «بازگشت»: رو به جهت خواندن (در فارسی به راست) */
fun DrawScope.backArrow(c: Color) {
    val w = size.minDimension
    val rtl = layoutDirection == LayoutDirection.Rtl
    val tip = if (rtl) w * .85f else w * .15f
    val tail = if (rtl) w * .15f else w * .85f
    val k = w * .17f
    drawLine(c, Offset(tail, w / 2), Offset(tip, w / 2), k, StrokeCap.Round)
    drawLine(c, Offset(tip, w / 2), Offset((tip + tail) / 2, w * .18f), k, StrokeCap.Round)
    drawLine(c, Offset(tip, w / 2), Offset((tip + tail) / 2, w * .82f), k, StrokeCap.Round)
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

/**
 * برچسب راه («مستقیم»، «تانل»، «آفلاین»): هم‌عرض و هم‌ارتفاع همه‌جا، متن دقیقاً وسط (بی فاصله‌ی اضافه‌ی بالا و پایین فونت)،
 * تا در گیمینگ و ضد فیلتر یک خط منظم بسازند.
 */
@Composable
fun SideTag(text: String, c: Color) {
    val sh = RoundedCornerShape(50)
    Box(
        Modifier.width(50.dp).height(19.dp).clip(sh).background(c.copy(alpha = .16f)).border(1.dp, c.copy(alpha = .45f), sh),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            tr(text), maxLines = 1,
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = Vazir, fontWeight = FontWeight.Black, fontSize = 10.sp, color = c, lineHeight = 10.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                    androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                ),
            ),
        )
    }
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

/** یک خانواده‌ی پروتکل در پنل گیمینگ یا ضد فیلتر (KANKI MIX، Amnezia، Xray، …) با سرورهای خودش */
data class GameFamilyUi(val key: String, val title: String, val sub: String, val servers: List<SheetServer>) {
    val best: Long? get() = servers.mapNotNull { it.ping }.filter { it > 0 }.minOrNull()
}

/** راه هر سرور برای فیلتر سه‌حالته‌ی بالای فهرست */
fun sideOf(sv: SheetServer): String = if (sv.side == "tunnel") "tunnel" else "direct"

/** یک سرور برای کنار هم گذاشتن مستقیم و تانلش: Xray با «group» پنل (یا اسم ردیف)، بقیه با شناسه‌ی سرور بی پسوند تانل */
fun serverKey(sv: SheetServer): String = if (sv.proto == "xray") "x:" + sv.group.ifBlank { sv.name } else sv.node.removeSuffix(TUNNEL_TWIN)

/** فهرست یک پروتکل با فیلتر «همه / مستقیم / تانل» */
fun filterSide(list: List<SheetServer>, side: String): List<SheetServer> = if (side == "all") list else list.filter { sideOf(it) == side }

/** کپسول یک پروتکل در نوار افقی بالای پاپ‌آپ */
@Composable
private fun ProtoPill(title: String, c: Color, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val selA by animateFloatAsState(if (selected) 1f else 0f, tween(260), label = "pill")
    Box(
        Modifier.height(32.dp)
            .goldGlass(CircleShape, selected = selected, tint = c)
            .alpha(if (enabled) 1f else .4f)
            .then(if (enabled) Modifier.pressable(scale = .95f, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(title, size = 12.sp, color = lerp(K.Text, Color.White, selA), weight = FontWeight.Bold, maxLines = 1, ltr = true)
    }
}

/** نوار افقی و اسکرولی پروتکل‌ها (یک ردیف) */
@Composable
private fun ProtoBar(families: List<GameFamilyUi>, sel: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(D.S2),
    ) {
        families.forEach { f -> ProtoPill(f.title, familyColor(f.key), f.key == sel, f.servers.isNotEmpty()) { onPick(f.key) } }
    }
}

/** سوییچ کپسولی سه‌حالته‌ی یکپارچه: همه | مستقیم | تانل (یک قاب، سه خانه‌ی هم‌عرض، متن وسط، جداکننده‌ی نازک بین خانه‌های خاموش) */
@Composable
private fun SideSwitch(sel: String, c: Color, counts: Map<String, Int>, onPick: (String) -> Unit) {
    val sh = RoundedCornerShape(50)
    val opts = listOf("all" to "همه", "direct" to "مستقیم", "tunnel" to "تانل")
    Row(
        Modifier.fillMaxWidth().height(36.dp).clip(sh).background(Color.Black.copy(alpha = .30f))
            .border(1.dp, Brush.horizontalGradient(listOf(c.copy(alpha = .45f), Color.White.copy(alpha = .12f), c.copy(alpha = .45f))), sh).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        opts.forEachIndexed { i, (k, name) ->
            val on = k == sel
            val n = counts[k] ?: 0
            val bg by animateColorAsState(if (on) c.copy(alpha = .85f) else Color.Transparent, tween(200), label = "side")
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(sh).background(bg)
                    .alpha(if (n > 0 || k == "all") 1f else .45f)
                    .pressable(scale = .97f) { onPick(k) },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    T(name, size = 12.sp, color = if (on) K.OnGold else K.Text, weight = FontWeight.Bold, maxLines = 1)
                    Spacer(Modifier.width(5.dp))
                    T("$n", size = 11.sp, color = if (on) K.OnGold.copy(alpha = .75f) else K.Sub, font = Poppins, weight = FontWeight.Bold, ltr = true, maxLines = 1)
                }
            }
            // جداکننده فقط بین دو خانه‌ی خاموش
            if (i < opts.lastIndex && !on && opts[i + 1].first != sel) Box(Modifier.width(1.dp).height(16.dp).background(Color.White.copy(alpha = .12f)))
            else if (i < opts.lastIndex) Spacer(Modifier.width(1.dp))
        }
    }
}

/**
 * کارت افقی یک کانفیگ (۱.۴۱): پرچم، اسم و برچسب رنگی «مستقیم» یا «تانل» (و برای گیمینگ خط پایداری) در یک طرف، پینگ با رنگ
 * وضعیت در طرف دیگر. سروری که جواب نداد کم‌رنگ و با برچسب «آفلاین»، و لمس نمی‌شود.
 */
@Composable
private fun ServerCard(sv: SheetServer, selected: Boolean, color: Color, enabled: Boolean, gaming: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(D.RM)
    val off = sv.ping != null && sv.ping < 0
    Row(
        Modifier.fillMaxWidth().padding(bottom = D.S2).height(if (gaming) 58.dp else 52.dp)
            .goldGlass(shape, selected = selected, tint = color)
            .alpha(if (off) .45f else if (enabled) 1f else .6f)
            .then(if (enabled && !off) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(horizontal = D.S3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = .4f)).border(1.2.dp, Color.White.copy(alpha = .35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { T(sv.flag, size = 17.sp, weight = FontWeight.Normal) }
        Spacer(Modifier.width(D.S2))
        Column(Modifier.weight(1f)) {
            AutoText(sv.name, size = 13.sp, color = K.Text)
            if (gaming && !off) StabilityLine(sv.q, sv.ping)
        }
        Spacer(Modifier.width(D.S2))
        // برچسب راه در یک ستون ثابت کنار پینگ: در همه‌ی کارت‌ها دقیقاً زیر هم
        val tun = sideOf(sv) == "tunnel"
        if (off) SideTag("آفلاین", K.Sub) else SideTag(if (tun) "تانل" else "مستقیم", if (tun) K.Gold2 else K.Gold)
        Spacer(Modifier.width(D.S2))
        PingSlot(sv.ping, 12.5.sp, 54.dp)
    }
}

/** خط دوم هر کارت گیمینگ: نقطه‌ی رنگی و «پایدار / متوسط / ضعیف · افت ٪»؛ تا سنجش برسد «در حال سنجش…» */
@Composable
private fun StabilityLine(q: Modes.GameQ?, ping: Long?) {
    val (txt, col) = when {
        ping != null && ping < 0 -> tr("بدون جواب") to K.Sub
        // پینگ رسیده ولی سنجش پایداری بازی هنوز نه: «آماده اتصال»، نه «در حال سنجش»
        q == null && ping != null && ping > 0 -> tr("آماده اتصال") to K.Good
        q == null -> tr("در حال سنجش…") to K.Sub
        else -> {
            val lv = Modes.stability(q)
            val name = when (lv) { 0 -> tr("پایدار"); 1 -> tr("متوسط"); else -> tr("ضعیف") }
            val c = when (lv) { 0 -> K.Ok; 1 -> K.Warn; else -> K.Red }
            // درصد افت فقط وقتی سنجش به‌اندازه‌ی کافی بسته داشت
            (if (q.n >= Modes.LOSS_MIN_SAMPLES) name + " · " + trf("افت %d٪", q.loss) else name) to c
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(col))
        Spacer(Modifier.width(D.S1))
        T(txt, size = 10.sp, color = col, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * جای ثابت عدد پینگ: «…» تا جواب بیاید، بعد عدد یا ✕. عرضش ثابت است تا با رسیدن پینگ اسم کنارش جابه‌جا نشود.
 */
@Composable
private fun PingSlot(p: Long?, size: androidx.compose.ui.unit.TextUnit, width: androidx.compose.ui.unit.Dp = 50.dp) {
    Box(Modifier.width(width), contentAlignment = Alignment.CenterEnd) {
        if (p == null) T("…", size = size, color = K.Sub, font = Poppins, ltr = true, maxLines = 1)
        else T(if (p < 0) "✕" else "$p ms", size = size, color = pingColor(p), font = Poppins, weight = FontWeight.Bold, ltr = true, maxLines = 1)
    }
}

/**
 * ردیف «بهترین سرور» (۱.۳۲): قاب ویژه با لبه‌ی گرادیان نئونی، آیکون صاعقه در دکمه‌ی گرد پر، برچسب «پایدار» و
 * پینگ؛ خودش بهترین سرور همین پروتکل را انتخاب و وصل می‌کند.
 */
@Composable
private fun FastestRow(
    selected: Boolean, color: Color, best: Long?, enabled: Boolean,
    title: String = "بهترین سرور برای بازی", sub: String = "پایدارترین را با پینگ، لرزش و افت پیدا و وصل می‌کند",
    steady: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(D.RM)
    Row(
        Modifier.fillMaxWidth().padding(bottom = D.S2).height(D.RowH)
            .drawBehind {
                val o = shape.createOutline(size, layoutDirection, this)
                for ((m, a) in listOf(8f to .05f, 4f to .12f)) drawOutline(o, color.copy(alpha = a * (if (selected) 1.5f else 1f)), style = Stroke(m.dp.toPx()))
            }
            .clip(shape)
            .background(Brush.linearGradient(listOf(lerp(K.BgMid, color, .16f), lerp(K.BgMid, Color.Black, .45f))))
            .border(1.5.dp, Brush.linearGradient(listOf(lerp(color, Color.White, .5f), color, Color.Transparent, color.copy(alpha = .5f), lerp(color, Color.White, .5f))), shape)
            .alpha(if (enabled) 1f else .5f)
            .then(if (enabled) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(horizontal = D.S3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).goldFill(CircleShape), contentAlignment = Alignment.Center) { MiniIcon("bolt", K.OnGold, 15.dp) }
        Spacer(Modifier.width(D.S3))
        Column(Modifier.weight(1f)) {
            T(title, size = D.TxtTitle, color = K.Text, weight = FontWeight.Black, maxLines = 1)
            T(sub, size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal, maxLines = 1)
        }
        Spacer(Modifier.width(D.S2))
        val sh = RoundedCornerShape(50)
        if (steady) Row(
            Modifier.clip(sh).background(K.Good.copy(alpha = .15f)).border(1.dp, K.Good.copy(alpha = .4f), sh).padding(horizontal = D.S2, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(K.Good, 5.dp)
            Spacer(Modifier.width(4.dp))
            T("پایدار", size = 10.sp, color = K.Good, weight = FontWeight.Black, maxLines = 1)
        }
        Box(Modifier.width(52.dp), contentAlignment = Alignment.CenterEnd) {
            best?.let { T("$it ms", size = 12.sp, color = pingColor(it), font = Poppins, weight = FontWeight.Bold, ltr = true, maxLines = 1) }
        }
    }
}

/**
 * نمای فهرست مشترک هر دو پاپ‌آپ: نوار پروتکل‌ها، فیلتر سه‌حالته و زیرش (داخل قاب ثابت، اسکرولی) «بهترین سرور» و کارت‌ها.
 * ترتیب کارت‌ها با باز شدن پنل (یا عوض شدن پروتکل) یک بار ثابت می‌شود: پینگ‌هایی که بعد می‌رسند فقط عددها را عوض
 * می‌کنند، نه جای کارت‌ها (وگرنه کارت زیر انگشت کاربر جابه‌جا می‌شد).
 */
@Composable
private fun ColumnScope.SheetList(
    visible: Boolean, families: List<GameFamilyUi>, fam: String, onFam: (String) -> Unit,
    side: String, onSide: (String) -> Unit, current: String?, auto: Boolean, busy: Boolean, gaming: Boolean,
    listTarget: String, fastest: @Composable (GameFamilyUi) -> Unit, onConnect: (String, String) -> Unit,
) {
    val f = families.firstOrNull { it.key == fam }
    val c = familyColor(fam)
    ProtoBar(families, fam, Modifier.guideTarget(if (gaming) "game_fams" else "filter_list"), onFam)
    Spacer(Modifier.height(D.S2))
    val all = f?.servers ?: emptyList()
    SideSwitch(side, c, mapOf("all" to all.size, "direct" to all.count { sideOf(it) == "direct" }, "tunnel" to all.count { sideOf(it) == "tunnel" }), onSide)
    Spacer(Modifier.height(D.S2))
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).guideTarget(listTarget)) {
        if (f == null || all.isEmpty()) {
            Spacer(Modifier.height(D.S4))
            T("این پروتکل در اشتراک شما نیست.", size = 12.sp, color = K.Warn, weight = FontWeight.Normal, modifier = Modifier.align(Alignment.CenterHorizontally))
        } else {
            fastest(f)
            val shown = filterSide(all, side)
            // هر سرور (کشور) با هم: اول مستقیمش، زیرش تانلش (نبود = فقط همان یکی)؛ سرورها به ترتیب بهترین پینگ، که با باز شدن پنل یک بار ثابت می‌شود
            val order = remember(visible, fam) { mutableListOf<String>() }
            all.groupBy { serverKey(it) }.entries.sortedBy { e -> e.value.mapNotNull { it.ping }.filter { it > 0 }.minOrNull() ?: 99_999L }
                .forEach { if (it.key !in order) order.add(it.key) }
            if (shown.isEmpty()) T(if (side == "tunnel") "این پروتکل کانفیگ تانل ندارد." else "این پروتکل کانفیگ مستقیم ندارد.", size = 12.sp, color = K.Sub, weight = FontWeight.Normal, modifier = Modifier.padding(D.S2))
            shown.sortedWith(compareBy({ order.indexOf(serverKey(it)) }, { if (sideOf(it) == "direct") 0 else 1 }, { it.ping?.takeIf { v -> v > 0 } ?: 99_999L })).forEach { sv ->
                key(sv.node + "|" + sv.proto + "|" + sv.name) {
                    ServerCard(sv, !auto && current == sv.node + "|" + sv.proto, c, !busy, gaming) { onConnect(sv.node, sv.proto) }
                }
            }
        }
        Spacer(Modifier.height(D.S4))
    }
}

/** یک ردیف تنظیمات با آیکون و فلش (مثل «انتخاب بازی‌ها») */
@Composable
private fun SettingLink(title: String, sub: String, c: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(D.RM)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).goldGlass(shape, tint = c).pressable(onClick = onClick).padding(horizontal = D.S3, vertical = D.S2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).goldFill(CircleShape), contentAlignment = Alignment.Center) { ModeIcon(KMode.GAME, K.OnGold, 16.dp) }
        Spacer(Modifier.width(D.S3))
        Column(Modifier.weight(1f)) {
            T(title, size = 13.5.sp, color = K.Text, weight = FontWeight.Black, maxLines = 1)
            T(sub, size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal)
        }
        Canvas(Modifier.size(12.dp).graphicsLayer { rotationZ = 180f }) { backArrow(K.Sub) }
    }
}

/** عنوان کوچک یک بخش تنظیمات */
@Composable
private fun SetLabel(text: String) {
    T(text, size = 12.sp, color = K.Sub, weight = FontWeight.Bold, modifier = Modifier.padding(top = D.S4, bottom = D.S2, start = D.S1))
}

/**
 * پنل گیمینگ: نوار پروتکل‌ها (KANKI MIX، Amnezia، WireGuard؛ فقط آن‌هایی که در اشتراک هست)، فیلتر
 * «همه / مستقیم / تانل» و کارت هر کانفیگ؛ لمس کارت = اتصال به همان. چرخ‌دنده: انتخاب بازی‌ها (در گیمینگ فقط همین‌ها
 * اینترنت دارند؛ فایروال همیشه روشن است) و تنظیمات KANKI MIX.
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
    gameCount: Int,
    onPickGames: () -> Unit,
    onFamilyShown: (String) -> Unit,
    onConnect: (node: String, proto: String) -> Unit,
    onFastest: (family: String) -> Unit,
    onClose: () -> Unit,
    startInSettings: Boolean = false,
) {
    var fam by remember(visible) {
        mutableStateOf(initFamily.takeIf { k -> families.any { it.key == k && it.servers.isNotEmpty() } }
            ?: families.firstOrNull { it.servers.isNotEmpty() }?.key ?: initFamily)
    }
    var side by remember(visible) { mutableStateOf("all") }
    var settings by remember(visible) { mutableStateOf(startInSettings) }
    LaunchedEffect(visible, fam) { if (visible) onFamilyShown(fam) }
    ModeSheet(visible, KMode.GAME, "گیمینگ", onClose, inSettings = settings, onSettings = { settings = true }, onBack = { settings = false }) {
        androidx.compose.animation.Crossfade(settings, animationSpec = tween(220), label = "gset", modifier = Modifier.weight(1f)) { inSet ->
            Column(Modifier.fillMaxSize()) {
                if (!inSet) {
                    SheetList(
                        visible, families, fam, { fam = it }, side, { side = it }, current, currentAuto, busy, gaming = true, listTarget = "game_list",
                        fastest = { f -> FastestRow(currentAuto && Modes.familyOf(current?.substringAfter("|") ?: "") == f.key, familyColor(f.key), f.best, !busy) { onFastest(f.key) } },
                        onConnect = onConnect,
                    )
                } else GameSettings(families, fam, kmixDup, onKmixDup, gameCount, onPickGames)
            }
        }
    }
}

/** نمای تنظیمات گیمینگ: «انتخاب بازی‌ها» بالا، بعد هر هسته‌ی گیمینگ با کپسول خودش و تنظیمات همان هسته زیرش */
@Composable
private fun ColumnScope.GameSettings(families: List<GameFamilyUi>, fam: String, kmixDup: Boolean, onKmixDup: (Boolean) -> Unit, gameCount: Int, onPickGames: () -> Unit) {
    // فقط هسته‌هایی که تنظیم دارند
    val cores = listOf("kankimix").filter { k -> families.any { it.key == k && it.servers.isNotEmpty() } }
    var core by remember { mutableStateOf(fam.takeIf { it in cores } ?: cores.firstOrNull() ?: "") }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        SettingLink(
            "انتخاب بازی‌ها",
            if (AppGate.supported) trf("لیست برنامه‌های مجاز در حالت گیمینگ · %d بازی", gameCount)
            else tr("لیست برنامه‌های مجاز در حالت گیمینگ · بستن بقیه‌ی برنامه‌ها روی اندروید ۱۰ به بالا"),
            modeColor(KMode.GAME), onPickGames,
        )
        if (cores.isNotEmpty()) {
            SetLabel(tr("تنظیمات هسته‌های گیمینگ"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(D.S2)) {
                cores.forEach { k ->
                    Box(Modifier.weight(1f)) {
                        val sel = k == core
                        Box(
                            Modifier.fillMaxWidth().height(36.dp).goldGlass(CircleShape, selected = sel, tint = familyColor(k)).pressable(scale = .96f) { core = k },
                            contentAlignment = Alignment.Center,
                        ) { T(families.first { it.key == k }.title, size = 12.sp, color = if (sel) Color.White else K.Text, weight = FontWeight.Bold, maxLines = 1, ltr = true) }
                    }
                }
            }
            val c = familyColor(core)
            androidx.compose.animation.Crossfade(core, animationSpec = tween(200), label = "core") { k ->
                Column(Modifier.fillMaxWidth()) {
                    when (k) {
                        "kankimix" -> Column(Modifier.fillMaxWidth().padding(top = D.S3).goldGlass(RoundedCornerShape(D.RL), tint = c).padding(horizontal = D.S3, vertical = D.S2)) {
                            T("KANKI MIX", size = 13.sp, color = c, weight = FontWeight.Black, ltr = true)
                            Spacer(Modifier.height(D.S1))
                            SwitchRow("حالت پایدار", "وای‌فای و دیتا با هم؛ بسته‌ی گم‌شده خودش جبران می‌شود، فقط به اندازه‌ی لازم", kmixDup, c, onChange = onKmixDup)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(D.S4))
    }
}

/**
 * پنل ضد فیلتر (لمس اول کاشی ضد فیلتر، مثل گیمینگ): نوار پروتکل‌ها (Xray، Amnezia، WireGuard، Hysteria2)، فیلتر
 * «همه / مستقیم / تانل» و کارت هر کانفیگ؛ «اتصال خودکار» بالای فهرست کم‌پینگ‌ترین راه را پیدا و وصل می‌کند.
 * چرخ‌دنده: پروتکل‌هایی که اتصال خودکار از بینشان می‌گردد.
 */
@Composable
fun FilterSheet(
    visible: Boolean,
    families: List<GameFamilyUi>,
    initFamily: String,
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
    startInSettings: Boolean = false,
) {
    val mc = modeColor(KMode.FILTER)
    LaunchedEffect(visible) { if (visible) onShown() }
    var fam by remember(visible) {
        mutableStateOf(initFamily.takeIf { k -> families.any { it.key == k && it.servers.isNotEmpty() } }
            ?: families.firstOrNull { it.servers.isNotEmpty() }?.key ?: initFamily)
    }
    var side by remember(visible) { mutableStateOf("all") }
    var settings by remember(visible) { mutableStateOf(startInSettings) }
    // اتصال خودکار فقط بین پروتکل‌های تیک‌خورده (Xray، و Amnezia / WireGuard / Hysteria2 با هم)
    val pool = families.filter { if (it.key == "xray") useX else useAwg }.flatMap { it.servers }
    ModeSheet(visible, KMode.FILTER, "ضد فیلتر", onClose, inSettings = settings, onSettings = { settings = true }, onBack = { settings = false }) {
        androidx.compose.animation.Crossfade(settings, animationSpec = tween(220), label = "fset", modifier = Modifier.weight(1f)) { inSet ->
            Column(Modifier.fillMaxSize()) {
                if (!inSet) {
                    SheetList(
                        visible, families, fam, { fam = it }, side, { side = it }, current, auto, busy, gaming = false, listTarget = "filter_cards",
                        fastest = {
                            FastestRow(
                                auto, mc, pool.mapNotNull { it.ping }.filter { it > 0 }.minOrNull(), !busy && pool.isNotEmpty(),
                                title = "اتصال خودکار ضد فیلتر", sub = "بهترین راه را خودش وصل می‌کند", steady = false, onClick = onAuto,
                            )
                        },
                        onConnect = onConnect,
                    )
                } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    SetLabel(tr("اتصال خودکار از بین این‌ها می‌گردد"))
                    SwitchRow("Xray", tr("VLESS، Trojan و Shadowsocks؛ برای سخت‌ترین محدودیت‌ها"), useX, protoColor("xray"), enabled = useAwg || !useX) { onTicks(it, useAwg) }
                    SwitchRow("Amnezia · WireGuard · Hysteria2", tr("مستقیم و تانل؛ سریع‌تر روی شبکه‌ی کم‌محدود"), useAwg, protoColor("amneziawg"), enabled = useX || !useAwg) { onTicks(useX, it) }
                    Spacer(Modifier.height(D.S4))
                }
            }
        }
    }
}

/** رنگ هر پروتکل (حلقه و هاله‌ی دکمه‌ی اتصال، کارت‌ها)؛ همه از تُن‌های همان تم، تا کنار هم یکدست بمانند */
fun protoColor(p: String?): Color = when (p) {
    "kankimix" -> ThemeState.current.mix
    "amneziawg", "amneziawg-direct" -> ThemeState.current.modeAnti
    "wireguard" -> ThemeState.current.modeDns
    "hysteria2" -> ThemeState.current.connected
    "xray" -> K.Gold
    else -> K.Gold
}

fun familyColor(f: String): Color = when (f) {
    "kankimix" -> protoColor("kankimix")
    "hysteria2" -> protoColor("hysteria2")
    "amnezia" -> protoColor("amneziawg")
    "xray" -> protoColor("xray")
    else -> protoColor("wireguard")
}

/** آیکون خانواده‌ی پروتکل: KANKI MIX دسته‌ی بازی با جرقه، Hysteria2 صاعقه، Amnezia سپر، WireGuard موج */
fun DrawScope.familyIcon(f: String, c: Color) {
    val w = size.width
    val st = Stroke(w * .09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (f) {
        "kankimix" -> {
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
