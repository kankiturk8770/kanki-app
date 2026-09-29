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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
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
) {
    Text(
        text, modifier = modifier,
        style = TextStyle(fontFamily = font, fontWeight = weight, fontSize = size, color = color, shadow = shadow),
    )
}

// ------------------------------------------------------------------ پس‌زمینه‌ی متحرک

@Composable
fun KankiBackground(accent: Color = K.Gold, content: @Composable BoxScope.() -> Unit) {
    val t = rememberInfiniteTransition(label = "bg")
    val shift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "s")
    val breathe by t.animateFloat(.22f, .34f, infiniteRepeatable(tween(3000), RepeatMode.Reverse), label = "b")

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
        }
        content()
    }
}

// ------------------------------------------------------------------ لوگو

@Composable
fun ShieldLogo(size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val shield = Path().apply {
            moveTo(w * .5f, h * .04f)
            lineTo(w * .92f, h * .18f)
            quadraticBezierTo(w * .92f, h * .70f, w * .5f, h * .97f)
            quadraticBezierTo(w * .08f, h * .70f, w * .08f, h * .18f)
            close()
        }
        drawPath(shield, Brush.verticalGradient(listOf(Color(0xFF2A2110), Color(0xFF0D0A05))))
        drawPath(
            shield,
            Brush.linearGradient(listOf(K.GoldLight, K.Gold, K.GoldDeep, K.GoldLight)),
            style = Stroke(w * .065f, join = StrokeJoin.Round),
        )
        // حرف K
        val k = Path().apply {
            moveTo(w * .36f, h * .28f); lineTo(w * .45f, h * .28f); lineTo(w * .45f, h * .46f)
            lineTo(w * .60f, h * .28f); lineTo(w * .71f, h * .28f); lineTo(w * .53f, h * .49f)
            lineTo(w * .72f, h * .72f); lineTo(w * .61f, h * .72f); lineTo(w * .45f, h * .53f)
            lineTo(w * .45f, h * .72f); lineTo(w * .36f, h * .72f); close()
        }
        drawPath(k, Brush.verticalGradient(listOf(K.GoldLight, K.Gold, K.GoldDeep)))
    }
}

@Composable
fun BrandTitle(size: TextUnit = 26.sp) {
    T("KANKI VPN", size = size, color = K.Gold, weight = FontWeight.Black, font = Orbitron, shadow = glow(K.Gold, 28f))
}

// ------------------------------------------------------------------ دکمه‌ی اتصال

enum class VState { OFF, CONNECTING, ON }

@Composable
fun ConnectButton(state: VState, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "cb")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "a")
    val pulse by t.animateFloat(.95f, 1.04f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "p")
    val accent = if (state == VState.ON) K.Green else K.Gold
    val scale = if (state == VState.CONNECTING) pulse else 1f

    Box(
        Modifier.size(250.dp).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = this.size.minDimension / 2 * scale
            // هاله
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .38f), Color.Transparent), c, r), r, c)
            // حلقه‌ی طلایی ثابت
            drawCircle(
                Brush.sweepGradient(listOf(K.GoldDeep, K.GoldLight, K.Gold, K.GoldDeep, K.GoldLight, K.GoldDeep), c),
                r * .76f, c, style = Stroke(11.dp.toPx()),
            )
            // حلقه‌ی چرخان (هنگام اتصال و وصل بودن)
            if (state != VState.OFF) {
                val ar = r * .90f
                for (off in listOf(0f, 180f)) {
                    rotate(angle + off, c) {
                        drawArc(
                            Brush.sweepGradient(
                                0f to Color.Transparent, 0.30f to accent, 0.31f to Color.Transparent, 1f to Color.Transparent,
                                center = c,
                            ),
                            0f, 110f, false,
                            topLeft = Offset(c.x - ar, c.y - ar), size = Size(ar * 2, ar * 2),
                            style = Stroke(5.dp.toPx(), cap = StrokeCap.Round),
                        )
                        val rad = Math.toRadians(110.0)
                        drawCircle(accent, 5.dp.toPx(), Offset(c.x + ar * cos(rad).toFloat(), c.y + ar * sin(rad).toFloat()))
                    }
                }
            }
            // دیسک داخلی
            drawCircle(Brush.radialGradient(listOf(Color(0xFF2A241A), Color(0xFF0B0A08)), c, r * .70f), r * .70f, c)
            drawCircle(Color.White.copy(alpha = .07f), r * .70f, c, style = Stroke(1.5.dp.toPx()))
            // آیکون پاور
            val pr = r * .22f
            val sw = 9.dp.toPx()
            drawArc(
                accent, -60f, 300f, false,
                topLeft = Offset(c.x - pr, c.y - pr), size = Size(pr * 2, pr * 2),
                style = Stroke(sw, cap = StrokeCap.Round),
            )
            drawLine(accent, Offset(c.x, c.y - pr * 1.3f), Offset(c.x, c.y - pr * .15f), sw, cap = StrokeCap.Round)
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
        T(value, size = 14.sp, color = valueColor, font = if (value.any { it in 'a'..'z' || it in '0'..'9' }) Poppins else Vazir, weight = FontWeight.SemiBold)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 9.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}
