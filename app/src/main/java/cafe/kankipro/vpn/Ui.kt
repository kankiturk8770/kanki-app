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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

// ------------------------------------------------------------------ رنگ‌ها

object K {
    val Gold = Color(0xFFF5C451)
    val GoldLight = Color(0xFFFFE9A6)
    val GoldDeep = Color(0xFFB8862B)
    val Ink = Color(0xFF060504)
    val Text = Color(0xFFF7F1E3)
    val Sub = Color(0xFFA89C82)
    val Line = Color(0x40F5C451)
    val Green = Color(0xFF3DDC97)
    val Red = Color(0xFFFF6B6B)
}

// ------------------------------------------------------------------ فونت‌ها
// فایل‌های فونت هنگام ساخت در گیت‌هاب دانلود می‌شوند (فایل build.yml)

val Vazir = FontFamily(
    Font(R.font.vazir_regular, FontWeight.Normal),
    Font(R.font.vazir_bold, FontWeight.Bold),
    Font(R.font.vazir_black, FontWeight.Black),
)

val Poppins = FontFamily(
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

val Orbitron = FontFamily(
    Font(R.font.orbitron, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900))),
)

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
) {
    Text(
        text, modifier = modifier,
        style = TextStyle(
            fontFamily = font, fontWeight = weight, fontSize = size, color = color, shadow = shadow,
            textDirection = if (ltr) TextDirection.Ltr else TextDirection.Content,
        ),
    )
}

// ------------------------------------------------------------------ پس‌زمینه‌ی متحرک

private class Star(val x: Float, val y: Float, val r: Float, val phase: Float, val color: Color)

private val stars: List<Star> = run {
    val rnd = java.util.Random(77)
    val cols = listOf(K.Gold, K.Green, Color(0xFF6FE7FF), K.GoldLight)
    List(38) { Star(rnd.nextFloat(), rnd.nextFloat(), 1.2f + rnd.nextFloat() * 2.2f, rnd.nextFloat(), cols[rnd.nextInt(cols.size)]) }
}

@Composable
fun KankiBackground(accent: Color = K.Gold, content: @Composable BoxScope.() -> Unit) {
    val t = rememberInfiniteTransition(label = "bg")
    val shift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "s")
    val breathe by t.animateFloat(.22f, .34f, infiniteRepeatable(tween(3000), RepeatMode.Reverse), label = "b")
    val twinkle by t.animateFloat(0f, 1f, infiniteRepeatable(tween(5000, easing = LinearEasing)), label = "tw")

    Box(Modifier.fillMaxSize().background(K.Ink)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val c = Offset(w / 2, h * 0.30f)
            drawRect(Brush.verticalGradient(listOf(Color(0xFF0C0A06), Color(0xFF17110A), Color(0xFF050403))))
            drawCircle(
                Brush.radialGradient(listOf(accent.copy(alpha = breathe), Color.Transparent), c, w * 0.95f),
                w * 0.95f, c,
            )
            for (i in 0 until 8) {
                drawCircle(
                    K.Gold.copy(alpha = (0.13f - i * 0.014f).coerceAtLeast(0.02f)),
                    w * (0.34f + i * 0.09f), c, style = Stroke(1.3f),
                )
            }
            val hy = h * 0.70f
            drawLine(
                Brush.horizontalGradient(listOf(Color.Transparent, K.Gold.copy(alpha = .7f), Color.Transparent)),
                Offset(0f, hy), Offset(w, hy), 2.5f,
            )
            val cx = w / 2
            for (i in -12..12) {
                drawLine(K.Gold.copy(alpha = .16f), Offset(cx + i * w * 0.03f, hy), Offset(cx + i * w * 0.32f, h), 1.3f)
            }
            for (k in 0 until 10) {
                val p = (k + shift) / 10f
                val y = hy + (h - hy) * p * p
                drawLine(K.Gold.copy(alpha = .04f + .22f * p), Offset(0f, y), Offset(w, y), 1.3f)
            }
            // ستاره‌های نئونی چشمک‌زن
            stars.forEach { st ->
                val tw = ((sin((twinkle + st.phase) * 6.2832f) + 1f) / 2f).coerceIn(0f, 1f)
                val pos = Offset(st.x * w, st.y * hy)
                val col = st.color
                drawCircle(col.copy(alpha = .10f * tw), st.r * 5f, pos)
                drawCircle(col.copy(alpha = .30f * tw), st.r * 2.2f, pos)
                drawCircle(Color.White.copy(alpha = .35f + .65f * tw), st.r, pos)
                if (st.r > 2.2f) {
                    drawLine(col.copy(alpha = .5f * tw), Offset(pos.x - st.r * 4, pos.y), Offset(pos.x + st.r * 4, pos.y), 1.2f)
                    drawLine(col.copy(alpha = .5f * tw), Offset(pos.x, pos.y - st.r * 4), Offset(pos.x, pos.y + st.r * 4), 1.2f)
                }
            }
        }
        content()
    }
}

// ------------------------------------------------------------------ لوگوی سه‌بعدی

private fun shieldPath(w: Float, h: Float, dx: Float = 0f, dy: Float = 0f) = Path().apply {
    moveTo(w * .50f + dx, h * .05f + dy)
    lineTo(w * .88f + dx, h * .18f + dy)
    quadraticBezierTo(w * .88f + dx, h * .68f + dy, w * .50f + dx, h * .93f + dy)
    quadraticBezierTo(w * .12f + dx, h * .68f + dy, w * .12f + dx, h * .18f + dy)
    close()
}

private fun kPath(w: Float, h: Float, dx: Float = 0f, dy: Float = 0f) = Path().apply {
    moveTo(w * .36f + dx, h * .28f + dy); lineTo(w * .45f + dx, h * .28f + dy); lineTo(w * .45f + dx, h * .45f + dy)
    lineTo(w * .59f + dx, h * .28f + dy); lineTo(w * .70f + dx, h * .28f + dy); lineTo(w * .53f + dx, h * .48f + dy)
    lineTo(w * .71f + dx, h * .70f + dy); lineTo(w * .60f + dx, h * .70f + dy); lineTo(w * .45f + dx, h * .52f + dy)
    lineTo(w * .45f + dx, h * .70f + dy); lineTo(w * .36f + dx, h * .70f + dy); close()
}

/** سپر طلایی سه‌بعدی که آرام دور خودش می‌چرخد */
@Composable
fun Logo3D(size: Dp, animate: Boolean = true) {
    val t = rememberInfiniteTransition(label = "logo")
    val rot by t.animateFloat(-24f, 24f, infiniteRepeatable(tween(3400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "r")
    Canvas(
        Modifier.size(size).graphicsLayer {
            rotationY = if (animate) rot else 0f
            cameraDistance = 14f * density
        }
    ) {
        val w = this.size.width
        val h = this.size.height
        // عمق (لایه‌های پشتی)
        for (i in 9 downTo 1) {
            val shade = 0.35f + i * 0.02f
            drawPath(shieldPath(w, h, i * w * .005f, i * h * .006f), Color(0xFF8A6418).copy(alpha = 1f).let {
                Color(it.red * (1.1f - shade), it.green * (1.1f - shade), it.blue * (1.1f - shade), 1f)
            })
        }
        val face = shieldPath(w, h)
        drawPath(face, Brush.verticalGradient(listOf(Color(0xFF3A2C12), Color(0xFF120D06))))
        drawPath(face, Brush.linearGradient(listOf(K.GoldLight, K.Gold, K.GoldDeep, K.GoldLight)), style = Stroke(w * .06f, join = StrokeJoin.Round))
        // حرف K برجسته
        drawPath(kPath(w, h, w * .012f, h * .014f), Color(0xFF5A3F0E))
        drawPath(kPath(w, h), Brush.verticalGradient(listOf(Color(0xFFFFF4C8), K.Gold, K.GoldDeep)))
        // براقی شیشه‌ای
        clipPath(face) {
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .30f), Color.Transparent)), size = Size(w, h * .42f))
        }
    }
}

/** لوگوی ساده (بدون چرخش) برای جاهای کوچک */
@Composable
fun ShieldLogo(size: Dp) = Logo3D(size, animate = false)

@Composable
fun BrandTitle(size: TextUnit = 26.sp) {
    T("KANKI VPN", size = size, color = K.Gold, weight = FontWeight.Black, font = Orbitron, shadow = glow(K.Gold, 28f), ltr = true)
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

@Composable
fun ConnectButton(state: VState, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "cb")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "a")
    val pulse by t.animateFloat(.94f, 1.03f, infiniteRepeatable(tween(750), RepeatMode.Reverse), label = "p")
    val breathe by t.animateFloat(.55f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "b")
    val accent = if (state == VState.ON) K.Green else K.Gold
    val scale = if (state == VState.CONNECTING) pulse else 1f

    Box(
        Modifier.size(230.dp).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = this.size.minDimension / 2 * scale
            val w = 3.dp.toPx()
            // هاله‌ی پشت دکمه
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .40f * breathe), Color.Transparent), c, r * 1.05f), r * 1.05f, c)
            // حلقه‌ی نئونی ثابت (فقط وقتی وصل است)
            if (state == VState.ON) neonRing(accent, c, r * .86f, w * .8f, breathe)
            // حلقه‌ی طلایی
            drawCircle(
                Brush.sweepGradient(listOf(K.GoldDeep, K.GoldLight, K.Gold, K.GoldDeep, K.GoldLight, K.GoldDeep), c),
                r * .72f, c, style = Stroke(10.dp.toPx()),
            )
            // دو نور نئونی چرخان
            if (state != VState.OFF) {
                neonComet(accent, angle, 120f, c, r * .95f, w)
                neonComet(accent, angle + 180f, 120f, c, r * .95f, w)
            }
            // دیسک داخلی
            drawCircle(Brush.radialGradient(listOf(Color(0xFF2A241A), Color(0xFF0B0A08)), c, r * .66f), r * .66f, c)
            drawCircle(Color.White.copy(alpha = .07f), r * .66f, c, style = Stroke(1.5.dp.toPx()))
            // آیکون پاور نئونی
            val pr = r * .21f
            val sw = 7.dp.toPx()
            val tl = Offset(c.x - pr, c.y - pr)
            val sz = Size(pr * 2, pr * 2)
            val glowOn = if (state == VState.OFF) .5f else 1f
            for ((m, a) in neonLayers) {
                drawArc(accent.copy(alpha = a * glowOn), -60f, 300f, false, tl, sz, style = Stroke(sw * m, cap = StrokeCap.Round))
                drawLine(accent.copy(alpha = a * glowOn), Offset(c.x, c.y - pr * 1.3f), Offset(c.x, c.y - pr * .15f), sw * m, cap = StrokeCap.Round)
            }
        }
    }
}

// ------------------------------------------------------------------ اجزای مشترک

@Composable
fun Glass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xE81C170E), Color(0xF00C0A06))))
            .border(1.2.dp, Brush.verticalGradient(listOf(K.Gold.copy(alpha = .6f), K.Gold.copy(alpha = .12f))), shape)
            .padding(18.dp),
        content = content,
    )
}

@Composable
fun GoldButton(text: String, modifier: Modifier = Modifier, loading: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier.height(58.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFFFFE08A), K.Gold, K.GoldDeep)))
            .border(1.dp, K.GoldLight.copy(alpha = .7f), shape)
            .clickable(enabled = !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        T(if (loading) "لطفاً صبر کنید…" else text, size = 18.sp, color = Color(0xFF1A1206), weight = FontWeight.Black)
    }
}

@Composable
fun LineButton(text: String, modifier: Modifier = Modifier, color: Color = K.Gold, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier.height(58.dp).clip(shape).background(Color(0xCC0E0B07))
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
        val r = minOf(size.width, size.height * 1.4f) / 2 * .86f
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
        drawCircle(Color(0xFF15110A), 12.dp.toPx(), c)
        drawCircle(color, 12.dp.toPx(), c, style = Stroke(3.dp.toPx()))
    }
}


// ------------------------------------------------------------------ آیکون‌ها

/** آیکون گرد کوچک (بدون متن) */
@Composable
fun IconCircle(size: Dp = 40.dp, onClick: () -> Unit, draw: DrawScope.(Color) -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF2A2212), Color(0xFF0E0B07))))
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

/** نشان UDP / TCP برای دکمه‌های تست پینگ */
@Composable
fun ProtoBadge(kind: String, result: Long?, busy: Boolean, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "pb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "s")
    val col = when {
        result == null -> K.Gold
        result < 0 -> K.Red
        result < 90 -> K.Green
        result < 200 -> K.Gold
        else -> K.Red
    }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.width(62.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(col.copy(alpha = .22f), Color(0x22000000))))
            .border(1.2.dp, col.copy(alpha = .8f), shape)
            .clickable(enabled = !busy, onClick = onClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width
            if (busy) {
                rotate(spin) { drawArc(col, 0f, 270f, false, style = Stroke(w * .12f, cap = StrokeCap.Round)) }
            } else if (kind == "UDP") {
                // امواج (ارسال بدون اتصال)
                drawCircle(col, w * .10f, Offset(w * .2f, w * .8f))
                for (i in 1..3) {
                    val r = w * .22f * i
                    drawArc(col, 270f, 90f, false, topLeft = Offset(w * .2f - r, w * .8f - r), size = Size(r * 2, r * 2), style = Stroke(w * .10f, cap = StrokeCap.Round))
                }
            } else {
                // دو حلقه‌ی زنجیر (اتصال دست‌دادنی)
                drawRoundRect(col, topLeft = Offset(w * .05f, w * .30f), size = Size(w * .52f, w * .40f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .2f), style = Stroke(w * .11f))
                drawRoundRect(col, topLeft = Offset(w * .43f, w * .30f), size = Size(w * .52f, w * .40f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .2f), style = Stroke(w * .11f))
            }
        }
        T(
            when { busy -> kind; result == null -> kind; result < 0 -> "✕"; else -> "${result}ms" },
            size = 10.sp, color = col, font = Poppins, weight = FontWeight.Bold, ltr = true,
        )
    }
}

// ------------------------------------------------------------------ ربات تلگرام

private val robotLines = listOf(
    "سلام! 👋",
    "برای خرید کانفیگ روی من بزن",
    "تست رایگان هم داریم 🎁",
    "تمدید اشتراک؟ من اینجام!",
    "پشتیبانی ۲۴ ساعته 💬",
)

/** ربات کوچک سه‌بعدی با حباب گفتگو که به ربات تلگرام می‌برد */
@Composable
fun RobotMascot(onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "robot")
    val bob by t.animateFloat(-4f, 4f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val blink by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "k")
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(3000); idx = (idx + 1) % robotLines.size }
    }
    Row(
        Modifier.clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(58.dp).graphicsLayer { translationY = bob }) {
            val w = size.width
            val eyeH = if (blink > .95f) w * .02f else w * .09f
            // آنتن
            drawLine(K.Gold, Offset(w * .5f, w * .08f), Offset(w * .5f, w * .22f), w * .05f, cap = StrokeCap.Round)
            drawCircle(Color(0xFF6FE7FF), w * .06f, Offset(w * .5f, w * .08f))
            drawCircle(Color(0xFF6FE7FF).copy(alpha = .3f), w * .12f, Offset(w * .5f, w * .08f))
            // سر (با سایه برای عمق)
            drawRoundRect(Color(0xFF6B4A12), topLeft = Offset(w * .16f, w * .25f), size = Size(w * .72f, w * .58f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .2f))
            drawRoundRect(
                Brush.verticalGradient(listOf(K.GoldLight, K.Gold, K.GoldDeep)),
                topLeft = Offset(w * .12f, w * .22f), size = Size(w * .72f, w * .58f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .2f),
            )
            // صفحه‌ی صورت
            drawRoundRect(Color(0xFF15110A), topLeft = Offset(w * .20f, w * .32f), size = Size(w * .56f, w * .34f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .12f))
            // چشم‌های نئونی
            for (ex in listOf(.36f, .60f)) {
                drawRoundRect(Color(0xFF6FE7FF).copy(alpha = .35f), topLeft = Offset(w * ex - w * .07f, w * .49f - eyeH), size = Size(w * .14f, eyeH * 2 + w * .02f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .05f))
                drawRoundRect(Color(0xFF6FE7FF), topLeft = Offset(w * ex - w * .04f, w * .49f - eyeH / 1.5f), size = Size(w * .08f, eyeH * 1.3f + w * .01f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .04f))
            }
            // آیکون تلگرام روی سینه
            val cx = w * .48f
            val cy = w * .90f
            drawCircle(Color(0xFF29A9EB), w * .10f, Offset(cx, cy))
            val plane = Path().apply {
                moveTo(cx - w * .06f, cy); lineTo(cx + w * .06f, cy - w * .05f); lineTo(cx + w * .02f, cy + w * .06f); close()
            }
            drawPath(plane, Color.White)
        }
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier.clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 4.dp))
                .background(Color(0xE61C170E)).border(1.dp, K.Gold.copy(alpha = .6f), RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 4.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) { T(robotLines[idx], size = 11.sp, color = K.Text) }
    }
}
