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
        "gold", Color(0xFFF5C451), Color(0xFFFFE9A6), Color(0xFFB8862B), Color(0xFFFFE08A),
        Color(0xFF060504), Color(0xFFF7F1E3), Color(0xFFA89C82),
        Color(0xFF0C0A06), Color(0xFF17110A), Color(0xFF050403),
        Color(0xE81C170E), Color(0xF00C0A06), Color(0xFF0E0B07), Color(0xFF1A1206), Color(0xFF6FE7FF),
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
) {
    val s = tr(text)
    Text(
        s, modifier = modifier,
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

private class Star(val x: Float, val y: Float, val r: Float, val phase: Float, val ci: Int)

private val stars: List<Star> = run {
    val rnd = java.util.Random(77)
    List(38) { Star(rnd.nextFloat(), rnd.nextFloat(), 1.2f + rnd.nextFloat() * 2.2f, rnd.nextFloat(), rnd.nextInt(4)) }
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
            drawRect(Brush.verticalGradient(listOf(ThemeState.current.bgTop, ThemeState.current.bgMid, ThemeState.current.bgBottom)))
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
            // شهاب‌ها
            for (k in 0 until 2) {
                val ph = ((twinkle * 2f + k * .5f) % 1f)
                if (ph < .22f) {
                    val q = ph / .22f
                    val sx = w * (.15f + .5f * k) + q * w * .45f
                    val sy = hy * (.10f + .18f * k) + q * hy * .22f
                    val tail = Offset(sx - w * .16f, sy - hy * .08f)
                    drawLine(
                        Brush.linearGradient(listOf(Color.Transparent, K.Accent.copy(alpha = .8f * (1f - q))), start = tail, end = Offset(sx, sy)),
                        tail, Offset(sx, sy), 2.5f, cap = StrokeCap.Round,
                    )
                    drawCircle(Color.White.copy(alpha = 1f - q), 2.6f, Offset(sx, sy))
                }
            }
            // ستاره‌های نئونی چشمک‌زن
            stars.forEach { st ->
                val tw = ((sin((twinkle + st.phase) * 6.2832f) + 1f) / 2f).coerceIn(0f, 1f)
                val pos = Offset(st.x * w, st.y * hy)
                val col = when (st.ci) { 0 -> K.Gold; 1 -> K.Green; 2 -> K.Accent; else -> K.GoldLight }
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

/** لوگوی سه‌بعدی کانکی: سپر دو لبه با نیم‌رخ نورخورده، حرف K برجسته و درخشش */
@Composable
fun Logo3D(size: Dp, animate: Boolean = true) {
    val t = rememberInfiniteTransition(label = "logo")
    val rot by t.animateFloat(-22f, 22f, infiniteRepeatable(tween(3600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "r")
    val shine by t.animateFloat(-0.6f, 1.6f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "s")
    Canvas(
        Modifier.size(size).graphicsLayer {
            rotationY = if (animate) rot else 0f
            cameraDistance = 14f * density
        }
    ) {
        val w = this.size.width
        val h = this.size.height
        // عمق
        for (i in 8 downTo 1) {
            val k = 1f - i * .06f
            drawPath(shieldPath(w, h, i * w * .005f, i * h * .006f), K.GoldDeep.copy(red = K.GoldDeep.red * .5f * k, green = K.GoldDeep.green * .5f * k, blue = K.GoldDeep.blue * .5f * k))
        }
        val face = shieldPath(w, h)
        drawPath(face, Brush.linearGradient(listOf(K.GoldLight, K.Gold, K.GoldDeep, K.GoldDeep.copy(red = K.GoldDeep.red * .7f, green = K.GoldDeep.green * .7f, blue = K.GoldDeep.blue * .7f)), Offset(0f, 0f), Offset(w, h)))
        // صفحه‌ی تیره‌ی داخلی با دو نیمه‌ی نورخورده
        val inner = shieldPath(w, h, inset = .07f)
        clipPath(inner) {
            drawRect(K.Surface.copy(alpha = 1f), size = Size(w / 2, h))
            drawRect(K.Panel, topLeft = Offset(w / 2, 0f), size = Size(w / 2, h))
            // نوار درخشش در حال حرکت
            val x = shine * w
            drawRect(
                Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .16f), Color.Transparent), Offset(x - w * .25f, 0f), Offset(x + w * .25f, h)),
                size = Size(w, h),
            )
        }
        drawPath(inner, K.Gold.copy(alpha = .9f), style = Stroke(w * .018f, join = StrokeJoin.Round))
        // حرف K
        drawPath(kPath(w, h, w * .014f, h * .016f), K.OnGold)
        drawPath(kPath(w, h), Brush.verticalGradient(listOf(K.GoldLight, K.Gold, K.GoldDeep)))
        // براقی بالای سپر
        clipPath(face) {
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .26f), Color.Transparent)), size = Size(w, h * .30f))
        }
        // ستاره‌ی درخشان گوشه
        val sp = Offset(w * .80f, h * .13f)
        drawLine(Color.White.copy(alpha = .9f), Offset(sp.x - w * .07f, sp.y), Offset(sp.x + w * .07f, sp.y), w * .012f, cap = StrokeCap.Round)
        drawLine(Color.White.copy(alpha = .9f), Offset(sp.x, sp.y - w * .07f), Offset(sp.x, sp.y + w * .07f), w * .012f, cap = StrokeCap.Round)
        drawCircle(Color.White.copy(alpha = .5f), w * .03f, sp)
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

@Composable
fun ConnectButton(state: VState, size: Dp = 230.dp, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "cb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "a")
    val chase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "c")
    val breathe by t.animateFloat(.55f, 1f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "b")
    val on = state == VState.ON
    val acc = if (on) K.Ok else K.Gold

    Box(
        Modifier.size(size).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = this.size.minDimension / 2
            // هاله‌ی نرم
            drawCircle(Brush.radialGradient(listOf(acc.copy(alpha = (if (on) .32f else .18f) * breathe), Color.Transparent), c, r), r, c)
            // حلقه‌ی درجه‌دار (مثل صفحه‌ی ساعت)؛ وصل: همه روشن، در حال اتصال: تعقیب
            val ticks = 60
            for (i in 0 until ticks) {
                val ang = Math.toRadians(i * 6.0 - 90)
                val lit = when (state) {
                    VState.ON -> 1f
                    VState.CONNECTING -> {
                        val d = ((i / ticks.toFloat()) - chase + 1f) % 1f
                        (1f - d * 3f).coerceIn(0f, 1f)
                    }
                    VState.OFF -> if (i % 5 == 0) .55f else .18f
                }
                val r1 = r * .86f
                val r2 = r * (if (i % 5 == 0) .95f else .92f)
                val p1 = Offset(c.x + r1 * cos(ang).toFloat(), c.y + r1 * sin(ang).toFloat())
                val p2 = Offset(c.x + r2 * cos(ang).toFloat(), c.y + r2 * sin(ang).toFloat())
                val col = if (state == VState.OFF) K.Gold else acc
                if (lit > .5f && state != VState.OFF) drawLine(col.copy(alpha = .25f * lit), p1, p2, 7.dp.toPx(), cap = StrokeCap.Round)
                drawLine(col.copy(alpha = .25f + .75f * lit), p1, p2, 2.2.dp.toPx(), cap = StrokeCap.Round)
            }
            // قوس چرخان نازک
            if (state != VState.OFF) {
                rotate(spin, c) {
                    drawArc(
                        Brush.sweepGradient(0f to Color.Transparent, .25f to acc, .26f to Color.Transparent, 1f to Color.Transparent, center = c),
                        0f, 90f, false, Offset(c.x - r * .80f, c.y - r * .80f), Size(r * 1.6f, r * 1.6f),
                        style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
            // حلقه‌ی فلزی
            drawCircle(
                Brush.sweepGradient(listOf(K.GoldDeep, K.GoldLight, K.Gold, K.GoldDeep, K.GoldLight, K.GoldDeep), c),
                r * .74f, c, style = Stroke(5.dp.toPx()),
            )
            // دیسک شیشه‌ای
            drawCircle(Brush.radialGradient(listOf(K.Surface.copy(alpha = 1f), K.Panel), c, r * .70f), r * .70f, c)
            drawCircle(Brush.verticalGradient(listOf(Color.White.copy(alpha = .10f), Color.Transparent), startY = c.y - r * .7f, endY = c.y), r * .70f, c)
            drawCircle(acc.copy(alpha = .35f), r * .70f, c, style = Stroke(1.dp.toPx()))
            // نماد پاور با درخشش
            val pr = r * .20f
            val sw = 6.dp.toPx()
            val tl = Offset(c.x - pr, c.y - pr)
            val sz = Size(pr * 2, pr * 2)
            for ((m, a) in neonLayers) {
                val al = a * (if (state == VState.OFF) .65f else 1f)
                drawArc(acc.copy(alpha = al), -60f, 300f, false, tl, sz, style = Stroke(sw * m, cap = StrokeCap.Round))
                drawLine(acc.copy(alpha = al), Offset(c.x, c.y - pr * 1.3f), Offset(c.x, c.y - pr * .15f), sw * m, cap = StrokeCap.Round)
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
            .background(Brush.verticalGradient(listOf(K.Surface, K.Surface2)))
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
        Modifier.width(46.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(col.copy(alpha = .22f), Color(0x22000000))))
            .border(1.2.dp, col.copy(alpha = .8f), shape)
            .clickable(enabled = !busy, onClick = onClick)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(16.dp)) {
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
            size = 9.sp, color = col, font = Poppins, weight = FontWeight.Bold, ltr = true,
        )
    }
}

// ------------------------------------------------------------------ ربات تلگرام

private val robotLines = listOf(
    "سلام! چطوری؟ 👋",
    "امروز روز خوبیه ☀️",
    "برای خرید کانفیگ روی من بزن",
    "تست رایگان هم داریم 🎁",
    "اینترنت آزاد حق همه‌ست 🌍",
    "تانل بسته شد؟ برو روی مستقیم ⚡",
    "اینترنت بی‌مرز، زندگی بی‌دغدغه 🚀",
    "پینگت رو از تب سرورها چک کن 📡",
    "امروز با کدوم سرور حال می‌کنی؟ 🌍",
    "وصل شو، آزاد باش ✨",
    "پینگ پایین، گیم بدون لگ 🎮",
    "امنزیا فیلتر رو دور می‌زنه 🛡",
    "یوتیوب و اینستا بدون قطعی 📺",
    "حریم خصوصیت با ما امنه 🔐",
    "سرورهای پرسرعت آلمان 🇩🇪",
    "تمدید سریع از ربات 🚀",
    "پشتیبانی ۲۴ ساعته 💬",
)

/** خود ربات: سر طلایی با چشم‌های نئونی، لوگوی کانکی روی پیشانی و تلگرام روی سینه */
@Composable
fun RobotFace(size: Dp, highlight: Float = 1f) {
    val t = rememberInfiniteTransition(label = "robot")
    val bob by t.animateFloat(-3f, 3f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val blink by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3400, easing = LinearEasing)), label = "k")
    val wave by t.animateFloat(-18f, 18f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "w")
    Canvas(Modifier.size(size).graphicsLayer { translationY = bob; alpha = highlight }) {
        val w = this.size.width
        val cr = androidx.compose.ui.geometry.CornerRadius(w * .22f)
        // آنتن با چراغ
        drawLine(K.Gold, Offset(w * .5f, w * .06f), Offset(w * .5f, w * .18f), w * .045f, cap = StrokeCap.Round)
        drawCircle(K.Accent.copy(alpha = .3f), w * .1f, Offset(w * .5f, w * .06f))
        drawCircle(K.Accent, w * .045f, Offset(w * .5f, w * .06f))
        // دست تکان‌دهنده
        rotate(wave, Offset(w * .86f, w * .62f)) {
            drawLine(K.GoldDeep, Offset(w * .86f, w * .62f), Offset(w * .98f, w * .44f), w * .07f, cap = StrokeCap.Round)
            drawCircle(K.Gold, w * .05f, Offset(w * .98f, w * .44f))
        }
        // سر (سایه برای عمق + بدنه‌ی طلایی)
        drawRoundRect(K.GoldDeep.copy(red = K.GoldDeep.red * .6f, green = K.GoldDeep.green * .6f, blue = K.GoldDeep.blue * .6f), topLeft = Offset(w * .17f, w * .21f), size = Size(w * .70f, w * .56f), cornerRadius = cr)
        drawRoundRect(
            Brush.verticalGradient(listOf(K.GoldLight, K.Gold, K.GoldDeep)),
            topLeft = Offset(w * .13f, w * .18f), size = Size(w * .70f, w * .56f), cornerRadius = cr,
        )
        drawRoundRect(Color.White.copy(alpha = .25f), topLeft = Offset(w * .19f, w * .21f), size = Size(w * .58f, w * .07f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .05f))
        // صفحه‌ی صورت
        drawRoundRect(K.Panel, topLeft = Offset(w * .20f, w * .33f), size = Size(w * .56f, w * .30f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .12f))
        val eyeH = if (blink > .95f) w * .02f else w * .085f
        for (ex in listOf(.36f, .60f)) {
            drawRoundRect(K.Accent.copy(alpha = .3f), topLeft = Offset(w * ex - w * .07f, w * .48f - eyeH / 2 - w * .03f), size = Size(w * .14f, eyeH + w * .06f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .05f))
            drawRoundRect(K.Accent, topLeft = Offset(w * ex - w * .04f, w * .48f - eyeH / 2), size = Size(w * .08f, eyeH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .04f))
        }
        // لبخند
        drawArc(K.Accent, 20f, 140f, false, topLeft = Offset(w * .40f, w * .50f), size = Size(w * .16f, w * .08f), style = Stroke(w * .02f, cap = StrokeCap.Round))
        // لوگوی کانکی روی پیشانی (سپر کوچک)
        val sx = w * .43f; val sy = w * .215f; val ss = w * .10f
        val sh = Path().apply {
            moveTo(sx + ss * .5f, sy); lineTo(sx + ss, sy + ss * .18f); lineTo(sx + ss, sy + ss * .55f)
            quadraticBezierTo(sx + ss, sy + ss * .85f, sx + ss * .5f, sy + ss * 1.05f)
            quadraticBezierTo(sx, sy + ss * .85f, sx, sy + ss * .55f); lineTo(sx, sy + ss * .18f); close()
        }
        drawPath(sh, K.OnGold)
        drawPath(sh, K.GoldLight, style = Stroke(w * .012f))
        // بدن کوچک + لوگوی تلگرام روی سینه
        drawRoundRect(Brush.verticalGradient(listOf(K.Gold, K.GoldDeep)), topLeft = Offset(w * .30f, w * .76f), size = Size(w * .36f, w * .20f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .08f))
        val cx = w * .48f; val cy = w * .86f
        drawCircle(Color(0xFF29A9EB), w * .085f, Offset(cx, cy))
        val plane = Path().apply {
            moveTo(cx - w * .05f, cy); lineTo(cx + w * .05f, cy - w * .04f); lineTo(cx + w * .02f, cy + w * .05f)
            lineTo(cx + w * .005f, cy + w * .01f); close()
        }
        drawPath(plane, Color.White)
    }
}

/** حباب گفتگوی ربات؛ هر جمله ۳.۵ ثانیه */
@Composable
fun RobotBubble(small: Boolean = false) {
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(3500); idx = (idx + 1) % robotLines.size } }
    val shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 4.dp)
    Box(
        Modifier.clip(shape).background(K.Surface).border(1.dp, K.Gold.copy(alpha = .6f), shape)
            .padding(horizontal = if (small) 8.dp else 10.dp, vertical = if (small) 4.dp else 6.dp),
    ) { T(robotLines[idx], size = if (small) 10.sp else 11.sp, color = K.Text) }
}

/** ربات با حباب گفتگو (صفحه‌ی ورود و تنظیمات) */
@Composable
fun RobotMascot(onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(24.dp)).clickable { onClick() }.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RobotFace(60.dp)
        Spacer(Modifier.width(4.dp))
        RobotBubble()
    }
}


// ------------------------------------------------------------------ آیکون‌های نوار پایین

fun DrawScope.navIcon(kind: Int, c: Color) {
    val w = size.width
    val sw = w * .09f
    when (kind) {
        0 -> { // خانه
            val p = Path().apply {
                moveTo(w * .14f, w * .48f); lineTo(w * .5f, w * .16f); lineTo(w * .86f, w * .48f)
                moveTo(w * .24f, w * .40f); lineTo(w * .24f, w * .84f); lineTo(w * .76f, w * .84f); lineTo(w * .76f, w * .40f)
            }
            drawPath(p, c, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawRoundRect(c, topLeft = Offset(w * .42f, w * .60f), size = Size(w * .16f, w * .24f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .04f))
        }
        1 -> { // سرورها (کره‌ی زمین)
            drawCircle(c, w * .36f, center, style = Stroke(sw))
            drawOval(c, topLeft = Offset(w * .33f, w * .14f), size = Size(w * .34f, w * .72f), style = Stroke(sw * .8f))
            drawLine(c, Offset(w * .14f, w * .5f), Offset(w * .86f, w * .5f), sw * .8f)
        }
        2 -> { // تست (سرعت‌سنج)
            drawArc(c, 150f, 240f, false, topLeft = Offset(w * .12f, w * .16f), size = Size(w * .76f, w * .76f), style = Stroke(sw, cap = StrokeCap.Round))
            drawLine(c, Offset(w * .5f, w * .54f), Offset(w * .70f, w * .34f), sw, cap = StrokeCap.Round)
            drawCircle(c, w * .07f, Offset(w * .5f, w * .54f))
        }
        else -> { // تنظیمات (چرخ‌دنده)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                drawLine(c, Offset(w / 2 + w * .26f * cos(a).toFloat(), w / 2 + w * .26f * sin(a).toFloat()),
                    Offset(w / 2 + w * .40f * cos(a).toFloat(), w / 2 + w * .40f * sin(a).toFloat()), sw * 1.3f, cap = StrokeCap.Round)
            }
            drawCircle(c, w * .27f, center, style = Stroke(sw))
            drawCircle(c, w * .09f, center)
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
