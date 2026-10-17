@file:OptIn(ExperimentalTextApi::class)

package cafe.kankipro.vpn

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

// ------------------------------------------------------------------ رنگ‌ها

/** یک تم رنگی کامل */
class Palette(
    val id: String,
    val gold: Color, val goldLight: Color, val goldDeep: Color, val shine: Color,
    val ink: Color, val text: Color, val sub: Color,
    val bgTop: Color, val bgMid: Color, val bgBottom: Color,
    val surface: Color, val surface2: Color, val panel: Color, val onGold: Color, val accent: Color,
    /** رنگ «وصل / فعال» (سبز) */
    val ok: Color,
)

val Themes: List<Palette> = listOf(
    // طلایی کانکی
    Palette(
        "gold", Color(0xFFE8B64C), Color(0xFFFFD97A), Color(0xFF9E701F), Color(0xFFFFE6A6),
        Color(0xFF070504), Color(0xFFF6EEDC), Color(0xFFA3967C),
        Color(0xFF0B0805), Color(0xFF110C07), Color(0xFF050403),
        Color(0xE0181209), Color(0xEC0C0906), Color(0xFF0E0A06), Color(0xFF1C1305), Color(0xFF5CC8FF),
        Color(0xFF35E39B),
    ),
    // گرافیت نئونی (مشکی، نقره‌ای، یخی)
    Palette(
        "mono", Color(0xFFDADDE3), Color(0xFFFFFFFF), Color(0xFF5E636B), Color(0xFFF4F6FA),
        Color(0xFF050608), Color(0xFFF2F4F8), Color(0xFF8C93A0),
        Color(0xFF0A0B0E), Color(0xFF15181E), Color(0xFF040506),
        Color(0xE8181B22), Color(0xF00B0D11), Color(0xFF0F1116), Color(0xFF0B0C10), Color(0xFF8FE3FF),
        Color(0xFF9CFFE0),
    ),
    // زرشکی نئونی با آبی
    Palette(
        "crimson", Color(0xFFFF3D6E), Color(0xFFFF9DB5), Color(0xFF9E1238), Color(0xFFFF7A98),
        Color(0xFF07030A), Color(0xFFFBEFF3), Color(0xFFA592A6),
        Color(0xFF0D0610), Color(0xFF1C0A1C), Color(0xFF07030A),
        Color(0xE8220C20), Color(0xF00E0610), Color(0xFF150A15), Color(0xFF1A0510), Color(0xFF3FA9FF),
        Color(0xFF45F0A6),
    ),
    // سایبری (فیروزه‌ای و صورتی نئون)
    Palette(
        "cyber", Color(0xFF00E5FF), Color(0xFF9DF6FF), Color(0xFF007C8C), Color(0xFF6FF3FF),
        Color(0xFF03060A), Color(0xFFEAFBFF), Color(0xFF7FA3AE),
        Color(0xFF050B10), Color(0xFF0A1822), Color(0xFF03060A),
        Color(0xE80B1A24), Color(0xF0061016), Color(0xFF08131A), Color(0xFF021014), Color(0xFFFF2BD6),
        Color(0xFF39FF9A),
    ),
    // بنفش نئونی
    Palette(
        "violet", Color(0xFFB26BFF), Color(0xFFE0C4FF), Color(0xFF5B2A9E), Color(0xFFCFA2FF),
        Color(0xFF05030A), Color(0xFFF4EEFF), Color(0xFF9A8FB0),
        Color(0xFF0A0614), Color(0xFF170C28), Color(0xFF05030A),
        Color(0xE8190F2A), Color(0xF00C0816), Color(0xFF110A1C), Color(0xFF12081E), Color(0xFF4DE1FF),
        Color(0xFF7CFFB2),
    ),
)

object ThemeState {
    var current: Palette by androidx.compose.runtime.mutableStateOf(Themes[0])
    fun set(id: String) { current = Themes.firstOrNull { it.id == id } ?: Themes[0] }
}

object K {
    val Gold: Color get() = ThemeState.current.gold
    val GoldLight: Color get() = ThemeState.current.goldLight
    val GoldDeep: Color get() = ThemeState.current.goldDeep
    val Shine: Color get() = ThemeState.current.shine
    val Ink: Color get() = ThemeState.current.ink
    val Text: Color get() = ThemeState.current.text
    val Sub: Color get() = ThemeState.current.sub
    val Line: Color get() = ThemeState.current.gold.copy(alpha = .25f)
    val Surface: Color get() = ThemeState.current.surface
    val Surface2: Color get() = ThemeState.current.surface2
    val Panel: Color get() = ThemeState.current.panel
    val OnGold: Color get() = ThemeState.current.onGold
    val Accent: Color get() = ThemeState.current.accent
    val Ok: Color get() = ThemeState.current.ok
    /** رنگ «وصل / فعال»؛ در همه‌ی تم‌ها سبز است */
    val Green: Color get() = ThemeState.current.ok
    val Red = Color(0xFFFF6B6B)
    /** هشدار (حجم یا زمان کم) */
    val Warn = Color(0xFFFFB547)
}

fun glow(c: Color, r: Float = 22f) = Shadow(color = c.copy(alpha = .75f), offset = Offset.Zero, blurRadius = r)

@Composable
fun T(
    text: String,
    size: TextUnit = 15.sp,
    color: Color = K.Text,
    weight: FontWeight = FontWeight.Bold,
    font: FontFamily = Vazir,
    shadow: Shadow? = null,
    modifier: Modifier = Modifier,
    ltr: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    val s = tr(text)
    Text(
        s, modifier = modifier,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        style = TextStyle(
            fontFamily = font, fontWeight = weight, fontSize = size, color = color, shadow = shadow,
            // جمله‌ی فارسی که با کلمه‌ی لاتین شروع می‌شود (مثل «Amnezia برای …») هم راست‌به‌چپ بماند
            textDirection = when {
                ltr -> TextDirection.Ltr
                s.any { it in '\u0600'..'\u06FF' } -> TextDirection.Rtl
                else -> TextDirection.Content
            },
        ),
    )
}

// ------------------------------------------------------------------ پس‌زمینه‌ی متحرک

/** ستاره‌ی چشمک‌زن روی عکس کهکشان */
private class Mote(val x: Float, val y: Float, val r: Float, val phase: Float, val speed: Float, val warm: Boolean)

/** speed عدد صحیح است تا چشمک‌زدن در پایان هر دور نپرد */
private val motes: List<Mote> = run {
    val rnd = java.util.Random(77)
    List(34) {
        Mote(.03f + rnd.nextFloat() * .94f, .02f + rnd.nextFloat() * .96f, .5f + rnd.nextFloat() * 1.1f, rnd.nextFloat(),
            (1 + rnd.nextInt(3)).toFloat(), rnd.nextFloat() < .6f)
    }
}

/**
 * پس‌زمینه‌ی کهکشانی: عکس واقعی کهکشان طلایی و آبی (res/drawable-nodpi/galaxy_bg.jpg)،
 * یک لایه‌ی تیره‌ی ملایم تا نوشته‌ها خوانا بمانند، و ستاره‌هایی که آرام چشمک می‌زنند و
 * ستاره‌های درشت‌تر نور چهارپر دارند. عکس خیلی آهسته جابه‌جا می‌شود تا آسمان زنده به نظر برسد.
 * calm: صفحه‌ی ورود و صفحه‌های آرام (لایه‌ی تیره کمتر، ستاره‌های کمتر).
 */
@Composable
fun KankiBackground(accent: Color = K.Gold, calm: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val t = rememberInfiniteTransition(label = "bg")
    val twinkle by t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "tw")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(60000, easing = LinearEasing), RepeatMode.Reverse), label = "dr")
    val breathe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val count = if (calm) 18 else motes.size
    val sky = ImageBitmap.imageResource(R.drawable.galaxy_bg)

    Box(Modifier.fillMaxSize().background(K.Ink)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // عکس کهکشان: پرکردن صفحه (Crop) با کمی بزرگ‌نمایی برای جابه‌جایی آرام
            val sc = maxOf(w / sky.width, h / sky.height) * 1.06f
            val dw = sky.width * sc
            val dh = sky.height * sc
            val ox = (w - dw) / 2 + (drift - .5f) * (dw - w) * .8f
            val oy = (h - dh) / 2
            drawImage(
                sky, dstOffset = androidx.compose.ui.unit.IntOffset(ox.toInt(), oy.toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(dw.toInt(), dh.toInt()),
                filterQuality = FilterQuality.Medium,
            )
            // لایه‌ی تیره: وسط صفحه (جای کارت‌ها) کمی تیره‌تر، لبه‌ها روشن
            drawRect(Color.Black.copy(alpha = if (calm) .18f else .26f))
            drawRect(Brush.radialGradient(listOf(Color.Black.copy(alpha = .30f), Color.Transparent), Offset(w * .5f, h * .62f), w * .9f))
            // هاله‌ی رنگ اتصال خیلی کم‌رنگ بالای صفحه
            val g = Offset(w * .5f, h * .22f)
            val gr = w * .95f
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .05f + .03f * breathe), Color.Transparent), g, gr), gr, g)
            // ستاره‌های چشمک‌زن
            val u = 1.dp.toPx()
            for (i in 0 until count) {
                val m = motes[i]
                val tw = (sin((twinkle * m.speed + m.phase) * 6.2832f) + 1f) / 2f
                val pos = Offset(m.x * w, m.y * h)
                val c = if (m.warm) Color(0xFFFFD58A) else Color(0xFFDDE8FF)
                val a = .85f * tw * tw
                drawCircle(c.copy(alpha = a * .18f), m.r * 5f * u, pos)
                drawCircle(c.copy(alpha = a), m.r * u, pos)
                if (m.r > 1.1f) {
                    val l = m.r * 7f * u * (.6f + .4f * tw)
                    val sw = .6f * u
                    drawLine(c.copy(alpha = a * .7f), Offset(pos.x - l, pos.y), Offset(pos.x + l, pos.y), sw, StrokeCap.Round)
                    drawLine(c.copy(alpha = a * .7f), Offset(pos.x, pos.y - l), Offset(pos.x, pos.y + l), sw, StrokeCap.Round)
                }
            }
        }
        content()
    }
}

// ------------------------------------------------------------------ لوگوی سه‌بعدی

/** سپر مدرن با لبه‌های صاف؛ inset برای لبه‌ی داخلی */
private fun shieldPath(w: Float, h: Float, dx: Float = 0f, dy: Float = 0f, inset: Float = 0f) = Path().apply {
    val l = w * (.13f + inset); val r = w * (.87f - inset); val tp = h * (.05f + inset * 1.1f)
    moveTo(w * .5f + dx, tp + dy)
    lineTo(r + dx, h * (.15f + inset * .6f) + dy)
    lineTo(r + dx, h * .50f + dy)
    quadraticBezierTo(r + dx, h * (.79f - inset * .4f) + dy, w * .5f + dx, h * (.96f - inset * 1.4f) + dy)
    quadraticBezierTo(l + dx, h * (.79f - inset * .4f) + dy, l + dx, h * .50f + dy)
    lineTo(l + dx, h * (.15f + inset * .6f) + dy)
    close()
}

private fun kPath(w: Float, h: Float, dx: Float = 0f, dy: Float = 0f) = Path().apply {
    moveTo(w * .35f + dx, h * .29f + dy); lineTo(w * .45f + dx, h * .29f + dy); lineTo(w * .45f + dx, h * .45f + dy)
    lineTo(w * .58f + dx, h * .29f + dy); lineTo(w * .70f + dx, h * .29f + dy); lineTo(w * .53f + dx, h * .48f + dy)
    lineTo(w * .71f + dx, h * .69f + dy); lineTo(w * .59f + dx, h * .69f + dy); lineTo(w * .45f + dx, h * .53f + dy)
    lineTo(w * .45f + dx, h * .69f + dy); lineTo(w * .35f + dx, h * .69f + dy); close()
}

/**
 * لوگوی کانکی: سپر طلایی با لبه‌ی برجسته، داخل تیره و حرف K طلایی.
 * sweep: جای نوار نور روی سپر (۰ تا ۱؛ منفی یعنی بدون نوار). animate: نوار نور آرام و پیوسته.
 * glow: هاله‌ی طلایی پشت سپر (۰ تا ۱).
 */
@Composable
fun Logo3D(size: Dp, animate: Boolean = false, sweep: Float = -1f, glow: Float = 0f) {
    val t = rememberInfiniteTransition(label = "logo")
    val loop by t.animateFloat(0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "s")
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = if (sweep >= 0f) sweep else if (animate) loop * 2.4f - .4f else -1f
        if (glow > 0f) {
            val gr = w * .8f
            drawCircle(Brush.radialGradient(listOf(K.Gold.copy(alpha = .32f * glow), Color.Transparent), center, gr), gr, center)
        }
        // سایه برای عمق
        drawPath(shieldPath(w, h, w * .012f, h * .02f), Color.Black.copy(alpha = .45f))
        val face = shieldPath(w, h)
        drawPath(face, Brush.linearGradient(listOf(K.GoldLight, K.Gold, K.GoldDeep), Offset(0f, 0f), Offset(w, h)))
        // داخل تیره
        val inner = shieldPath(w, h, inset = .075f)
        drawPath(inner, Brush.verticalGradient(listOf(K.Surface2.copy(alpha = 1f), K.Ink)))
        drawPath(inner, K.GoldDeep.copy(alpha = .95f), style = Stroke(w * .014f, join = StrokeJoin.Round))
        // حرف K
        drawPath(kPath(w, h, w * .01f, h * .012f), Color.Black.copy(alpha = .55f))
        drawPath(kPath(w, h), Brush.verticalGradient(listOf(K.GoldLight, K.Gold, K.GoldDeep)))
        // براقی لبه‌ی بالا
        clipPath(face) {
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .22f), Color.Transparent)), size = Size(w, h * .22f))
        }
        // نوار نور
        if (sw > -.4f && sw < 1.4f) {
            clipPath(face) {
                val x = sw * w
                drawRect(
                    Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .36f), Color.Transparent), Offset(x - w * .22f, 0f), Offset(x + w * .22f, h * .5f)),
                    size = Size(w, h),
                )
            }
        }
    }
}

/** لوگوی ساده (بدون چرخش) برای جاهای کوچک */
@Composable
fun ShieldLogo(size: Dp) = Logo3D(size, animate = false)

@Composable
fun BrandTitle(size: TextUnit = 26.sp, text: String = "KANKI VPN") {
    T(text, size = size, color = K.Gold, weight = FontWeight.Black, font = Orbitron, shadow = glow(K.Gold, 28f), ltr = true)
}

// ------------------------------------------------------------------ نور نئونی

private val neonLayers = listOf(4.5f to .07f, 3.0f to .15f, 1.9f to .32f, 1.0f to 1f)

/** قوس نئونی با دنباله‌ی محوشونده (مثل ستاره‌ی دنباله‌دار) */
fun DrawScope.neonComet(color: Color, head: Float, length: Float, c: Offset, r: Float, w: Float) {
    val tl = Offset(c.x - r, c.y - r)
    val sz = Size(r * 2, r * 2)
    val seg = 16
    for (i in 0 until seg) {
        val f = (i + 1f) / seg
        val start = head - length + length * i / seg
        for ((m, a) in neonLayers) {
            drawArc(color.copy(alpha = a * f), start, length / seg + .6f, false, tl, sz, style = Stroke(w * m, cap = StrokeCap.Butt))
        }
    }
    val rad = Math.toRadians(head.toDouble())
    val p = Offset(c.x + r * cos(rad).toFloat(), c.y + r * sin(rad).toFloat())
    for ((m, a) in neonLayers) drawCircle(color.copy(alpha = a), w * m * .7f, p)
    drawCircle(Color.White, w * .5f, p)
}

/** دایره‌ی نئونی کامل */
fun DrawScope.neonRing(color: Color, c: Offset, r: Float, w: Float, strength: Float = 1f) {
    for ((m, a) in neonLayers) drawCircle(color.copy(alpha = a * strength), r, c, style = Stroke(w * m))
    drawCircle(Color.White.copy(alpha = .55f * strength), r, c, style = Stroke(w * .35f))
}

// ------------------------------------------------------------------ دکمه‌ی اتصال

enum class VState { OFF, CONNECTING, ON }

// ------------------------------------------------------------------ اجزای مشترک

/**
 * ظاهر شیشه‌ای براق طلایی برای کارت‌ها و کاشی‌ها (مثل طرح):
 * شیشه‌ی تیره با ته‌رنگ طلایی، براقی روشن بالای کارت، لبه‌ی طلایی که بالایش روشن‌تر است،
 * و وقتی selected است درخشش طلایی بیرون لبه.
 */
fun Modifier.goldGlass(shape: androidx.compose.ui.graphics.Shape, selected: Boolean = false, tint: Color = K.Gold): Modifier = this
    .drawBehind {
        if (selected) {
            val o = shape.createOutline(size, layoutDirection, this)
            for ((m, a) in listOf(9f to .06f, 5f to .12f, 2.5f to .22f)) drawOutline(o, tint.copy(alpha = a), style = Stroke(m.dp.toPx()))
        }
    }
    .clip(shape)
    .background(Brush.verticalGradient(listOf(tint.copy(alpha = if (selected) .20f else .09f), K.Surface.copy(alpha = .82f), K.Surface2.copy(alpha = .92f))))
    .drawWithContent {
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (selected) .13f else .08f), Color.Transparent), endY = size.height * .48f))
        drawContent()
    }
    .border(
        if (selected) 1.5.dp else 1.1.dp,
        Brush.verticalGradient(
            if (selected) listOf(K.Shine, tint, K.GoldDeep.copy(alpha = .8f))
            else listOf(tint.copy(alpha = .75f), tint.copy(alpha = .30f), tint.copy(alpha = .14f)),
        ),
        shape,
    )

@Composable
fun Glass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier
            .goldGlass(shape)
            .padding(18.dp),
        content = content,
    )
}

@Composable
fun GoldButton(text: String, modifier: Modifier = Modifier, loading: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier.height(58.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(K.Shine, K.Gold, K.GoldDeep)))
            .border(1.dp, K.GoldLight.copy(alpha = .7f), shape)
            .clickable(enabled = !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        T(if (loading) "لطفاً صبر کنید…" else text, size = 18.sp, color = K.OnGold, weight = FontWeight.Black)
    }
}

@Composable
fun LineButton(text: String, modifier: Modifier = Modifier, color: Color = K.Gold, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier.height(58.dp).clip(shape).background(K.Panel.copy(alpha = .8f))
            .border(1.5.dp, color.copy(alpha = .8f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T(text, size = 17.sp, color = color, weight = FontWeight.Black) }
}

@Composable
fun Pill(text: String, color: Color = K.Gold, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    var m = modifier.clip(shape).background(color.copy(alpha = .10f)).border(1.dp, color.copy(alpha = .7f), shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Box(m.padding(horizontal = 14.dp, vertical = 7.dp)) { T(text, size = 13.sp, color = color) }
}

@Composable
fun SectionTitle(text: String) {
    T(text, size = 17.sp, color = K.Gold, weight = FontWeight.Black, modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp))
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = K.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        T(label, size = 13.sp, color = K.Sub, weight = FontWeight.Normal)
        Spacer(Modifier.weight(1f))
        val latin = value.any { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } && value.none { it in '\u0600'..'\u06FF' }
        T(value, size = 14.sp, color = valueColor, font = if (latin) Poppins else Vazir, weight = FontWeight.SemiBold, ltr = latin)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 9.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}


// ------------------------------------------------------------------ گیج تست سرعت

private val gaugeTicks = listOf(0.0, 5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0)

/** موقعیت یک عدد روی گیج (۰ تا ۱) با مقیاس لگاریتمی مثل اسپیدتست */
fun gaugeFraction(v: Double): Float {
    val x = v.coerceIn(0.0, 1000.0)
    for (i in 0 until gaugeTicks.size - 1) {
        if (x <= gaugeTicks[i + 1]) {
            val f = (x - gaugeTicks[i]) / (gaugeTicks[i + 1] - gaugeTicks[i])
            return ((i + f) / (gaugeTicks.size - 1)).toFloat()
        }
    }
    return 1f
}

@Composable
fun SpeedGauge(value: Double, color: Color, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val anim by animateFloatAsState(gaugeFraction(value), tween(300), label = "g")
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height * .55f)
        // شعاع طوری که قوس از بالای کادر بیرون نزند
        val r = minOf(size.width / 2, size.height * .55f) * .9f
        val start = 145f
        val sweep = 250f
        val tl = Offset(c.x - r, c.y - r)
        val sz = Size(r * 2, r * 2)
        val sw = 14.dp.toPx()
        drawArc(Color(0x22FFFFFF), start, sweep, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        if (anim > 0.001f) {
            for ((m, a) in listOf(2.2f to .15f, 1.4f to .35f, 1f to 1f)) {
                drawArc(color.copy(alpha = a), start, sweep * anim, false, tl, sz, style = Stroke(sw * m, cap = StrokeCap.Round))
            }
        }
        val labelStyle = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = K.Sub)
        gaugeTicks.forEachIndexed { i, t ->
            val ang = Math.toRadians((start + sweep * i / (gaugeTicks.size - 1)).toDouble())
            val p1 = Offset(c.x + (r - sw * 1.3f) * cos(ang).toFloat(), c.y + (r - sw * 1.3f) * sin(ang).toFloat())
            val p2 = Offset(c.x + (r - sw * 2.1f) * cos(ang).toFloat(), c.y + (r - sw * 2.1f) * sin(ang).toFloat())
            drawLine(K.Gold.copy(alpha = .8f), p1, p2, 2.dp.toPx(), cap = StrokeCap.Round)
            val lp = Offset(c.x + (r - sw * 3.4f) * cos(ang).toFloat(), c.y + (r - sw * 3.4f) * sin(ang).toFloat())
            val layout = tm.measure(t.toInt().toString(), labelStyle)
            drawText(layout, topLeft = Offset(lp.x - layout.size.width / 2f, lp.y - layout.size.height / 2f))
        }
        // عقربه
        val na = Math.toRadians((start + sweep * anim).toDouble())
        val tip = Offset(c.x + (r - sw * 1.2f) * cos(na).toFloat(), c.y + (r - sw * 1.2f) * sin(na).toFloat())
        for ((m, a) in listOf(3f to .15f, 1.8f to .35f, 1f to 1f)) {
            drawLine(color.copy(alpha = a), c, tip, 4.dp.toPx() * m, cap = StrokeCap.Round)
        }
        drawCircle(K.Panel, 12.dp.toPx(), c)
        drawCircle(color, 12.dp.toPx(), c, style = Stroke(3.dp.toPx()))
    }
}


// ------------------------------------------------------------------ آیکون‌ها

/** آیکون گرد کوچک (بدون متن) */
@Composable
fun IconCircle(size: Dp = 40.dp, onClick: () -> Unit, draw: DrawScope.(Color) -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(Brush.verticalGradient(listOf(K.Surface.copy(alpha = 1f), K.Panel)))
            .border(1.2.dp, K.Gold.copy(alpha = .7f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * .5f)) { this.draw(K.Gold) }
    }
}

/** آیکون بروزرسانی */
fun DrawScope.refreshIcon(c: Color) {
    val w = size.width
    val sw = w * .13f
    drawArc(c, 40f, 280f, false, topLeft = Offset(w * .1f, w * .1f), size = Size(w * .8f, w * .8f), style = Stroke(sw, cap = StrokeCap.Round))
    val a = Math.toRadians(40.0)
    val tip = Offset(w / 2 + w * .4f * cos(a).toFloat(), w / 2 + w * .4f * sin(a).toFloat())
    drawLine(c, tip, Offset(tip.x + w * .02f, tip.y - w * .30f), sw, cap = StrokeCap.Round)
    drawLine(c, tip, Offset(tip.x - w * .28f, tip.y - w * .06f), sw, cap = StrokeCap.Round)
}

/** آیکون حساب کاربری */
fun DrawScope.accountIcon(c: Color) {
    val w = size.width
    drawCircle(c, w * .20f, Offset(w / 2, w * .32f))
    drawArc(c, 180f, 180f, true, topLeft = Offset(w * .14f, w * .60f), size = Size(w * .72f, w * .72f))
}

// ------------------------------------------------------------------ ربات کانکی

/** رنگ چشم‌های ربات (در همه‌ی تم‌ها آبی) */
private val RobotEye = Color(0xFF5CC8FF)

/** ربات بامزه‌ی طلایی با چشم‌های آبی؛ animate=false برای جاهای کوچک (مثل دکمه‌ی سربرگ) */
@Composable
fun RobotFace(size: Dp, highlight: Float = 1f, animate: Boolean = true) {
    val t = rememberInfiniteTransition(label = "robot")
    val bob by t.animateFloat(-2.5f, 2.5f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val blink by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "k")
    val wave by t.animateFloat(-14f, 14f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "w")
    Canvas(Modifier.size(size).graphicsLayer { translationY = if (animate) bob else 0f; alpha = highlight }) {
        drawRobot(if (animate) blink else 0f, if (animate) wave else 0f, body = true)
    }
}

/**
 * کشیدن ربات داخل بوم مربعی. body=false فقط سر را بزرگ‌تر می‌کشد (آیکون سربرگ).
 */
fun DrawScope.drawRobot(blink: Float = 0f, wave: Float = 0f, body: Boolean = true) {
    val w = size.width
    val deep = K.GoldDeep
    val hx = if (body) w * .15f else w * .10f
    val hy = if (body) w * .20f else w * .28f
    val hw = if (body) w * .66f else w * .80f
    val hh = if (body) w * .48f else w * .56f
    val cx = hx + hw / 2
    // آنتن
    drawLine(deep, Offset(cx, hy), Offset(cx, hy - w * .09f), w * .035f, StrokeCap.Round)
    drawCircle(K.GoldLight, w * .045f, Offset(cx, hy - w * .11f))
    // گوش‌ها
    drawRoundRect(deep, Offset(hx - w * .05f, hy + hh * .30f), Size(w * .07f, hh * .38f), CornerRadius(w * .03f))
    drawRoundRect(deep, Offset(hx + hw - w * .02f, hy + hh * .30f), Size(w * .07f, hh * .38f), CornerRadius(w * .03f))
    if (body) {
        // دست تکان‌دهنده
        val sh = Offset(hx + hw - w * .04f, hy + hh + w * .10f)
        rotate(wave, sh) {
            drawLine(deep, sh, Offset(w * .96f, hy + hh - w * .02f), w * .06f, StrokeCap.Round)
            drawCircle(K.Gold, w * .045f, Offset(w * .96f, hy + hh - w * .02f))
        }
        // بدن کوچک با دکمه‌ی آبی
        drawRoundRect(Brush.verticalGradient(listOf(K.Gold, deep)), Offset(cx - w * .17f, hy + hh + w * .03f), Size(w * .34f, w * .20f), CornerRadius(w * .08f))
        val bc = Offset(cx, hy + hh + w * .13f)
        drawCircle(Color(0xFF14202C), w * .055f, bc)
        drawCircle(RobotEye, w * .036f, bc)
    }
    // سر
    drawRoundRect(Brush.verticalGradient(listOf(K.GoldLight, K.Gold, deep)), Offset(hx, hy), Size(hw, hh), CornerRadius(hw * .30f))
    drawRoundRect(Color.White.copy(alpha = .22f), Offset(hx + hw * .14f, hy + hh * .06f), Size(hw * .72f, hh * .11f), CornerRadius(hh * .06f))
    // صفحه‌ی صورت
    val vx = hx + hw * .12f
    val vy = hy + hh * .25f
    val vw = hw * .76f
    val vh = hh * .58f
    drawRoundRect(Color(0xFF15100A), Offset(vx, vy), Size(vw, vh), CornerRadius(vh * .45f))
    // چشم‌ها
    val er = vh * .25f
    val eyeY = vy + vh * .44f
    for (ex in listOf(vx + vw * .30f, vx + vw * .70f)) {
        drawCircle(RobotEye.copy(alpha = .22f), er * 1.5f, Offset(ex, eyeY))
        if (blink > .95f) {
            drawLine(RobotEye, Offset(ex - er, eyeY), Offset(ex + er, eyeY), er * .4f, StrokeCap.Round)
        } else {
            drawCircle(RobotEye, er, Offset(ex, eyeY))
            drawCircle(Color.White.copy(alpha = .85f), er * .32f, Offset(ex - er * .3f, eyeY - er * .32f))
        }
    }
    // لبخند
    drawArc(
        RobotEye.copy(alpha = .9f), 20f, 140f, false,
        topLeft = Offset(cx - vw * .10f, vy + vh * .66f), size = Size(vw * .20f, vh * .16f),
        style = Stroke(w * .018f, cap = StrokeCap.Round),
    )
}

/** رنگ حباب گفتگوی ربات (کرم روشن مثل طرح) */
val BubbleBg = Color(0xFFF3E8CF)
val BubbleText = Color(0xFF2A2116)

/** حباب گفتگو؛ گوشه‌ی تیز سمت ربات است (robotAtStart: ربات در ابتدای ردیف) */
@Composable
fun SpeechBubble(text: String, robotAtStart: Boolean, modifier: Modifier = Modifier, size: TextUnit = 12.sp) {
    val shape = if (robotAtStart) RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
    else RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
    Box(
        modifier.clip(shape).background(BubbleBg).border(1.dp, K.Gold.copy(alpha = .55f), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) { T(text, size = size, color = BubbleText, weight = FontWeight.Bold) }
}

/** ربات با حباب گفتگو */
@Composable
fun RobotSays(text: String, modifier: Modifier = Modifier, robotSize: Dp = 62.dp, robotAtStart: Boolean = true, onClick: (() -> Unit)? = null) {
    var m = modifier.clip(RoundedCornerShape(18.dp))
    if (onClick != null) m = m.clickable(onClick = onClick)
    Row(m.padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (robotAtStart) { RobotFace(robotSize); Spacer(Modifier.width(6.dp)) }
        SpeechBubble(text, robotAtStart, Modifier.weight(1f, fill = false))
        if (!robotAtStart) { Spacer(Modifier.width(6.dp)); RobotFace(robotSize) }
    }
}


// ------------------------------------------------------------------ آیکون‌های نوار پایین

fun DrawScope.navIcon(kind: Int, c: Color) {
    val w = size.width
    val sw = w * .075f
    val st = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (kind) {
        0 -> { // خانه
            val p = Path().apply {
                moveTo(w * .12f, w * .48f); lineTo(w * .5f, w * .14f); lineTo(w * .88f, w * .48f)
                moveTo(w * .22f, w * .40f); lineTo(w * .22f, w * .86f); lineTo(w * .78f, w * .86f); lineTo(w * .78f, w * .40f)
                moveTo(w * .41f, w * .86f); lineTo(w * .41f, w * .62f); lineTo(w * .59f, w * .62f); lineTo(w * .59f, w * .86f)
            }
            drawPath(p, c, style = st)
        }
        1 -> { // سرورها (کره‌ی زمین)
            drawCircle(c, w * .38f, center, style = st)
            drawOval(c, topLeft = Offset(w * .32f, w * .12f), size = Size(w * .36f, w * .76f), style = Stroke(sw * .85f))
            drawLine(c, Offset(w * .13f, w * .5f), Offset(w * .87f, w * .5f), sw * .85f)
            drawLine(c, Offset(w * .20f, w * .32f), Offset(w * .80f, w * .32f), sw * .7f)
            drawLine(c, Offset(w * .20f, w * .68f), Offset(w * .80f, w * .68f), sw * .7f)
        }
        2 -> { // تست سرعت (سرعت‌سنج)
            drawArc(c, 160f, 220f, false, topLeft = Offset(w * .10f, w * .16f), size = Size(w * .80f, w * .80f), style = st)
            drawLine(c, Offset(w * .5f, w * .56f), Offset(w * .68f, w * .36f), sw, cap = StrokeCap.Round)
            drawCircle(c, w * .06f, Offset(w * .5f, w * .56f))
        }
        else -> { // تنظیمات (خورشید/چرخ‌دنده)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                drawLine(
                    c, Offset(w / 2 + w * .31f * cos(a).toFloat(), w / 2 + w * .31f * sin(a).toFloat()),
                    Offset(w / 2 + w * .43f * cos(a).toFloat(), w / 2 + w * .43f * sin(a).toFloat()), sw, cap = StrokeCap.Round,
                )
            }
            drawCircle(c, w * .20f, center, style = st)
        }
    }
}


// ------------------------------------------------------------------ آیکون‌های کوچک (به‌جای ایموجی)

fun DrawScope.miniIcon(kind: String, c: Color) {
    val w = size.width
    val sw = w * .11f
    when (kind) {
        "shield" -> {
            val p = Path().apply {
                moveTo(w * .5f, w * .08f); lineTo(w * .86f, w * .22f); lineTo(w * .86f, w * .50f)
                quadraticBezierTo(w * .86f, w * .78f, w * .5f, w * .94f)
                quadraticBezierTo(w * .14f, w * .78f, w * .14f, w * .50f); lineTo(w * .14f, w * .22f); close()
            }
            drawPath(p, c, style = Stroke(sw, join = StrokeJoin.Round))
            drawLine(c, Offset(w * .36f, w * .52f), Offset(w * .47f, w * .63f), sw, cap = StrokeCap.Round)
            drawLine(c, Offset(w * .47f, w * .63f), Offset(w * .66f, w * .40f), sw, cap = StrokeCap.Round)
        }
        "bolt" -> {
            val p = Path().apply {
                moveTo(w * .58f, w * .06f); lineTo(w * .22f, w * .56f); lineTo(w * .48f, w * .56f)
                lineTo(w * .40f, w * .94f); lineTo(w * .78f, w * .42f); lineTo(w * .52f, w * .42f); close()
            }
            drawPath(p, c)
        }
        "data" -> {
            drawOval(c, topLeft = Offset(w * .18f, w * .10f), size = Size(w * .64f, w * .24f), style = Stroke(sw * .9f))
            drawLine(c, Offset(w * .18f, w * .22f), Offset(w * .18f, w * .78f), sw * .9f)
            drawLine(c, Offset(w * .82f, w * .22f), Offset(w * .82f, w * .78f), sw * .9f)
            drawArc(c, 0f, 180f, false, topLeft = Offset(w * .18f, w * .38f), size = Size(w * .64f, w * .24f), style = Stroke(sw * .9f))
            drawArc(c, 0f, 180f, false, topLeft = Offset(w * .18f, w * .66f), size = Size(w * .64f, w * .24f), style = Stroke(sw * .9f))
        }
        "clock" -> {
            drawCircle(c, w * .38f, center, style = Stroke(sw))
            drawLine(c, center, Offset(w * .5f, w * .28f), sw, cap = StrokeCap.Round)
            drawLine(c, center, Offset(w * .68f, w * .58f), sw, cap = StrokeCap.Round)
        }
        "spark" -> { // ستاره‌ی چهارپر (انتخاب هوشمند)
            val p = Path().apply {
                moveTo(w * .5f, w * .04f); quadraticBezierTo(w * .56f, w * .44f, w * .96f, w * .5f)
                quadraticBezierTo(w * .56f, w * .56f, w * .5f, w * .96f); quadraticBezierTo(w * .44f, w * .56f, w * .04f, w * .5f)
                quadraticBezierTo(w * .44f, w * .44f, w * .5f, w * .04f); close()
            }
            drawPath(p, c)
            drawCircle(c, w * .08f, Offset(w * .84f, w * .16f))
        }
        "crown" -> {
            val p = Path().apply {
                moveTo(w * .1f, w * .78f); lineTo(w * .1f, w * .3f); lineTo(w * .32f, w * .52f); lineTo(w * .5f, w * .2f)
                lineTo(w * .68f, w * .52f); lineTo(w * .9f, w * .3f); lineTo(w * .9f, w * .78f); close()
            }
            drawPath(p, c)
        }
        "unlock" -> { // قفل باز (محافظت‌نشده)
            drawRoundRect(c, topLeft = Offset(w * .18f, w * .46f), size = Size(w * .64f, w * .46f), cornerRadius = CornerRadius(w * .1f))
            drawArc(c, 180f, 180f, false, topLeft = Offset(w * .36f, w * .08f), size = Size(w * .40f, w * .44f), style = Stroke(sw, cap = StrokeCap.Round))
            drawLine(c, Offset(w * .36f, w * .30f), Offset(w * .36f, w * .34f), sw, cap = StrokeCap.Round)
        }
        "down", "up" -> { // فلش دانلود / آپلود
            val up = kind == "up"
            val y0 = if (up) w * .88f else w * .12f
            val y1 = if (up) w * .14f else w * .86f
            drawLine(c, Offset(w * .5f, y0), Offset(w * .5f, y1), sw * 1.2f, cap = StrokeCap.Round)
            val d = if (up) w * .30f else -w * .30f
            drawLine(c, Offset(w * .5f, y1), Offset(w * .2f, y1 + d), sw * 1.2f, cap = StrokeCap.Round)
            drawLine(c, Offset(w * .5f, y1), Offset(w * .8f, y1 + d), sw * 1.2f, cap = StrokeCap.Round)
        }
        "ping" -> { // میله‌های سیگنال
            for (i in 0 until 3) {
                val h = w * (.30f + i * .28f)
                drawRoundRect(c, topLeft = Offset(w * (.10f + i * .30f), w * .92f - h), size = Size(w * .2f, h), cornerRadius = CornerRadius(w * .06f))
            }
        }
        else -> { // وضعیت: نقطه‌ی درخشان
            drawCircle(c.copy(alpha = .25f), w * .45f, center)
            drawCircle(c.copy(alpha = .5f), w * .32f, center)
            drawCircle(c, w * .20f, center)
        }
    }
}

@Composable
fun MiniIcon(kind: String, color: Color, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) { miniIcon(kind, color) }
}

// ------------------------------------------------------------------ آیکون‌های صفحه‌ی تنظیمات

/** آیکون تنظیمات داخل یک جعبه‌ی گرد با زمینه‌ی ملایم، هم‌رنگ تم برنامه */
@Composable
fun SettingIcon(kind: String, tint: Color = K.Gold, size: Dp = 38.dp) {
    val shape = RoundedCornerShape(13.dp)
    Box(
        Modifier.size(size).clip(shape)
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = .22f), tint.copy(alpha = .07f))))
            .border(1.dp, tint.copy(alpha = .45f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * .58f)) { settingIcon(kind, tint) }
    }
}

/** آیکون‌های خطی تنظیمات؛ روی شبکه‌ی ۲۴×۲۴ کشیده و به اندازه‌ی بوم بزرگ/کوچک می‌شوند */
fun DrawScope.settingIcon(kind: String, c: Color) {
    val u = size.width / 24f
    val st = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun pt(x: Float, y: Float) = Offset(x * u, y * u)
    fun poly(vararg v: Float, closed: Boolean = false): Path = Path().apply {
        moveTo(v[0] * u, v[1] * u)
        var i = 2
        while (i + 1 < v.size) { lineTo(v[i] * u, v[i + 1] * u); i += 2 }
        if (closed) close()
    }
    when (kind) {
        "dns" -> { // دو سرور روی هم (سیستم نام دامنه)
            for (y in listOf(3.5f, 13.5f)) {
                drawRoundRect(c, topLeft = pt(3f, y), size = Size(18f * u, 7f * u), cornerRadius = CornerRadius(2.6f * u), style = st)
                drawCircle(c, 1.2f * u, pt(7.2f, y + 3.5f))
                drawLine(c, pt(11f, y + 3.5f), pt(16.5f, y + 3.5f), st.width, StrokeCap.Round)
            }
        }
        "auto" -> { // حلقه‌ی خودکار + رعد
            drawArc(c, 140f, 270f, false, topLeft = pt(2.8f, 2.8f), size = Size(18.4f * u, 18.4f * u), style = st)
            drawPath(poly(13.4f, 5.6f, 8.2f, 12.8f, 11.7f, 12.8f, 10.7f, 18.4f, 15.9f, 11.2f, 12.4f, 11.2f, closed = true), c)
        }
        "sound" -> { // بلندگو با موج صدا
            drawPath(poly(3.5f, 9.5f, 7.5f, 9.5f, 12f, 5.5f, 12f, 18.5f, 7.5f, 14.5f, 3.5f, 14.5f, closed = true), c, style = st)
            drawArc(c, -45f, 90f, false, topLeft = pt(7.5f, 7.5f), size = Size(9f * u, 9f * u), style = st)
            drawArc(c, -50f, 100f, false, topLeft = pt(4.5f, 4.5f), size = Size(15f * u, 15f * u), style = st)
        }
        "palette" -> { // پالت رنگ (تم برنامه): بدنه‌ی گرد با گودی انگشت + چهار لکه‌ی رنگ
            fun rim(deg: Float): Offset {
                val rad = deg * 0.017453292f
                return pt(12f + 9.4f * cos(rad), 12f + 9.4f * sin(rad))
            }
            val body = Path().apply {
                for (k in 0..65) {
                    val p = rim(62f + 5f * k)
                    if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                val end = rim(388f)
                lineTo(end.x, end.y)
                val start = rim(62f)
                quadraticBezierTo(14.6f * u, 14.4f * u, start.x, start.y)
            }
            drawPath(body, c, style = st)
            drawCircle(c, 1.25f * u, pt(7.8f, 10.2f))
            drawCircle(c, 1.25f * u, pt(10.4f, 6.9f))
            drawCircle(c, 1.25f * u, pt(14.6f, 6.9f))
            drawCircle(c, 1.25f * u, pt(17f, 10.2f))
        }
        "split" -> { // مسیر دوشاخه (تونل‌زنی انتخابی)
            drawLine(c, pt(12f, 21f), pt(12f, 12.5f), st.width, StrokeCap.Round)
            drawPath(poly(12f, 12.5f, 6.5f, 7f), c, style = st)
            drawPath(poly(12f, 12.5f, 17.5f, 7f), c, style = st)
            drawPath(poly(6.5f, 11f, 6.5f, 7f, 10.5f, 7f), c, style = st)
            drawPath(poly(17.5f, 11f, 17.5f, 7f, 13.5f, 7f), c, style = st)
        }
        "kill" -> { // سپر با دکمه‌ی پاور (کیل‌سوییچ)
            val shield = Path().apply {
                moveTo(12f * u, 2.5f * u); lineTo(20f * u, 5.5f * u); lineTo(20f * u, 11.5f * u)
                quadraticBezierTo(20f * u, 18f * u, 12f * u, 21.5f * u)
                quadraticBezierTo(4f * u, 18f * u, 4f * u, 11.5f * u)
                lineTo(4f * u, 5.5f * u); close()
            }
            drawPath(shield, c, style = st)
            drawArc(c, -50f, 280f, false, topLeft = pt(8.6f, 9f), size = Size(6.8f * u, 6.8f * u), style = st)
            drawLine(c, pt(12f, 8.2f), pt(12f, 12f), st.width, StrokeCap.Round)
        }
        "cart" -> { // سبد خرید
            drawPath(poly(2.5f, 3.5f, 5.2f, 3.5f, 7.4f, 15f, 18.6f, 15f, 20.6f, 7f, 5.9f, 7f), c, style = st)
            drawCircle(c, 1.7f * u, pt(9.2f, 19.5f))
            drawCircle(c, 1.7f * u, pt(16.8f, 19.5f))
        }
        "support" -> { // هدست پشتیبانی
            drawArc(c, 180f, 180f, false, topLeft = pt(4f, 3.5f), size = Size(16f * u, 16f * u), style = st)
            drawRoundRect(c, topLeft = pt(2.8f, 11f), size = Size(4.4f * u, 6.6f * u), cornerRadius = CornerRadius(2f * u))
            drawRoundRect(c, topLeft = pt(16.8f, 11f), size = Size(4.4f * u, 6.6f * u), cornerRadius = CornerRadius(2f * u))
            val mic = Path().apply { moveTo(19f * u, 17.6f * u); quadraticBezierTo(19f * u, 20.8f * u, 14.5f * u, 20.8f * u) }
            drawPath(mic, c, style = st)
            drawCircle(c, 1.3f * u, pt(13.2f, 20.8f))
        }
        "share" -> { // اشتراک‌گذاری (سه گره)
            drawCircle(c, 2.6f * u, pt(18f, 5.5f), style = st)
            drawCircle(c, 2.6f * u, pt(6f, 12f), style = st)
            drawCircle(c, 2.6f * u, pt(18f, 18.5f), style = st)
            drawLine(c, pt(8.3f, 10.8f), pt(15.7f, 6.7f), st.width, StrokeCap.Round)
            drawLine(c, pt(8.3f, 13.2f), pt(15.7f, 17.3f), st.width, StrokeCap.Round)
        }
        "logout" -> { // در با فلش خروج
            drawPath(poly(10f, 3.5f, 4.5f, 3.5f, 4.5f, 20.5f, 10f, 20.5f), c, style = st)
            drawLine(c, pt(9.5f, 12f), pt(20f, 12f), st.width, StrokeCap.Round)
            drawPath(poly(16f, 8f, 20f, 12f, 16f, 16f), c, style = st)
        }
        "lang" -> { // کره‌ی زمین (زبان)
            drawCircle(c, 9f * u, pt(12f, 12f), style = st)
            drawOval(c, topLeft = pt(8f, 3f), size = Size(8f * u, 18f * u), style = st)
            drawLine(c, pt(3.5f, 9f), pt(20.5f, 9f), st.width, StrokeCap.Round)
            drawLine(c, pt(3.5f, 15f), pt(20.5f, 15f), st.width, StrokeCap.Round)
        }
        else -> drawCircle(c, 8f * u, pt(12f, 12f), style = st)
    }
}
