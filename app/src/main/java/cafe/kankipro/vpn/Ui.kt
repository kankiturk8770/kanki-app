@file:OptIn(ExperimentalTextApi::class)

package cafe.kankipro.vpn

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

// ------------------------------------------------------------------ رنگ‌ها

/**
 * یک تم رنگی کامل. هر تم یک خانواده‌ی رنگی بسته است و همه‌ی رنگ‌های معنایی (وصل، چهار حالت، KANKI MIX، خطا،
 * هشدار، کشور شما روی نقشه، کلید روشن، آدمک‌ها و تیرهای گیمینگ) را خودش و از همان خانواده می‌دهد.
 */
class Palette(
    val id: String,
    val gold: Color, val goldLight: Color, val goldDeep: Color, val shine: Color,
    val ink: Color, val text: Color, val sub: Color,
    val bgTop: Color, val bgMid: Color, val bgBottom: Color,
    val surface: Color, val surface2: Color, val panel: Color, val onGold: Color, val accent: Color,
    /** حالت «وصل» (نقطه‌ها، خط مسیر روی نقشه، کارت سرور وصل، نوشته‌ی «متصل») */
    val connected: Color,
    /** رنگ چهار حالت: چهار رنگ هماهنگ با تم (هم‌شدت و هم‌روشنی) تا کاشی‌ها از هم جدا و کنار هم یکدست باشند */
    val modeSmart: Color, val modeGame: Color, val modeAnti: Color, val modeDns: Color,
    /** KANKI MIX (و پروتکل‌های دیگر از همین تُن‌ها؛ protoColor) */
    val mix: Color,
    /** خطا (فقط جای خطا) و هشدار (حجم یا زمان کم، پینگ متوسط) */
    val danger: Color, val warn: Color,
    /** کشور خود کاربر روی نقشه */
    val userCountry: Color,
    /** کلید روشن */
    val toggleOn: Color,
    /** نئون دوم لبه‌ی دکمه‌ی گیمینگ (کنار modeGame) و رنگ تیرهای گیمینگ */
    val gameAlt: Color, val tracer: Color,
    /** تب انتخاب‌شده‌ی نوار پایین: گرادیان بالا به پایین و رنگ نوشته‌ی رویش */
    val navTop: Color, val navBottom: Color, val onNav: Color,
    /** رنگ آدمک‌های گیمینگ (Unspecified = همان رنگ گیمینگ، مثل دکمه) */
    val runner: Color = Color.Unspecified,
    /** رنگ «وصل» وسط دکمه‌ی اتصال (نشان پاور و نوشته‌ی حالت) */
    val power: Color = Color(0xFF3DDC84),
    /** رنگ دوم هر تم (۱.۳۴): گرادیان نورها، لبه‌های شیشه و نوارها از رنگ اصلی به این رنگ می‌رود تا ظاهر یکنواخت نباشد */
    val gold2: Color = gold,
) {
    /** نام قدیمی رنگ «وصل» */
    val ok: Color get() = connected
}

/**
 * تم‌های شیشه‌ای (۱.۳۲، طرح «Kanki Glass»): هر تم فقط یک زمینه و یک رنگ نئونی دارد و همه‌ی رنگ‌های معنایی
 * (وصل، چهار حالت، KANKI MIX، نوار پایین، …) از همین دو ساخته می‌شوند، تا کل برنامه یکدست بماند.
 * خطا و هشدار در همه‌ی تم‌ها یکی‌اند.
 */
private fun glassTheme(id: String, bg: Long, accent: Long, accent2: Long, ink: Long = 0xFF0A0D0B, text: Long = 0xFFF2F5F1, sub: Long = 0xFFA9B3AB): Palette {
    val b = Color(bg)
    val a = Color(accent)
    val hi = lerp(a, Color.White, .40f)
    val deep = lerp(a, Color.Black, .45f)
    val on = Color(ink)
    return Palette(
        id, a, hi, deep, lerp(a, Color.White, .75f),
        lerp(b, Color.Black, .55f), Color(text), Color(sub),
        lerp(b, Color.White, .035f), b, lerp(b, Color.Black, .45f),
        lerp(b, Color.White, .07f).copy(alpha = .90f), lerp(b, Color.White, .11f).copy(alpha = .94f), lerp(b, Color.Black, .30f).copy(alpha = .96f), on,
        accent = hi,
        connected = a,
        modeSmart = a, modeGame = a, modeAnti = a, modeDns = a,
        mix = a,
        danger = Color(0xFFFF6C65), warn = Color(0xFFFFC24A),
        userCountry = hi,
        toggleOn = a,
        gameAlt = hi, tracer = Color.White,
        navTop = hi, navBottom = a, onNav = on,
        power = a,
        gold2 = Color(accent2),
    )
}

val Themes: List<Palette> = listOf(
    // رنگ اصلی همان قبلی؛ رنگ دوم: Kanki سبزآبی، Cyber بنفش، Tiffany آبی یخی، Vulcanico قرمز صورتی آتشی، Malt کهربایی، Cyprus نقره‌ای
    glassTheme("kanki", 0xFF141414, 0xFF2BEE34, 0xFF19E6C1),
    glassTheme("cyber", 0xFF3C1A47, 0xFFB6FF00, 0xFFB44CFF),
    glassTheme("tiffany", 0xFF171717, 0xFF21F1A8, 0xFF4FACFE),
    glassTheme("vulcan", 0xFF001621, 0xFFFF4103, 0xFFFF2A6D),
    glassTheme("malt", 0xFF2A2312, 0xFFFFBE0B, 0xFFFF8C00),
    glassTheme("cyprus", 0xFF004741, 0xFFF0EDE4, 0xFFB8C4C2, ink = 0xFF062A27, sub = 0xFFA6C2BD),
)

/** نام هر تم در تنظیمات (همان نام‌های طرح) */
val ThemeNames = mapOf(
    "kanki" to "Kanki Default", "cyber" to "Cyber Gaming", "tiffany" to "Tiffany Pro",
    "vulcan" to "Vulcanico", "malt" to "Gold Malt", "cyprus" to "Cyprus Sand",
)

/** ترکیب دو پالت (برای تغییر نرم تم) */
fun lerpPalette(a: Palette, b: Palette, f: Float) = Palette(
    b.id,
    lerp(a.gold, b.gold, f), lerp(a.goldLight, b.goldLight, f), lerp(a.goldDeep, b.goldDeep, f), lerp(a.shine, b.shine, f),
    lerp(a.ink, b.ink, f), lerp(a.text, b.text, f), lerp(a.sub, b.sub, f),
    lerp(a.bgTop, b.bgTop, f), lerp(a.bgMid, b.bgMid, f), lerp(a.bgBottom, b.bgBottom, f),
    lerp(a.surface, b.surface, f), lerp(a.surface2, b.surface2, f), lerp(a.panel, b.panel, f),
    lerp(a.onGold, b.onGold, f), lerp(a.accent, b.accent, f),
    connected = lerp(a.connected, b.connected, f),
    modeSmart = lerp(a.modeSmart, b.modeSmart, f), modeGame = lerp(a.modeGame, b.modeGame, f),
    modeAnti = lerp(a.modeAnti, b.modeAnti, f), modeDns = lerp(a.modeDns, b.modeDns, f),
    mix = lerp(a.mix, b.mix, f), danger = lerp(a.danger, b.danger, f), warn = lerp(a.warn, b.warn, f),
    userCountry = lerp(a.userCountry, b.userCountry, f), toggleOn = lerp(a.toggleOn, b.toggleOn, f),
    gameAlt = lerp(a.gameAlt, b.gameAlt, f), tracer = lerp(a.tracer, b.tracer, f),
    navTop = lerp(a.navTop, b.navTop, f), navBottom = lerp(a.navBottom, b.navBottom, f), onNav = lerp(a.onNav, b.onNav, f),
    runner = if (a.runner.isSpecified && b.runner.isSpecified) lerp(a.runner, b.runner, f) else b.runner,
    power = lerp(a.power, b.power, f),
    gold2 = lerp(a.gold2, b.gold2, f),
)

object ThemeState {
    var current: Palette by androidx.compose.runtime.mutableStateOf(Themes[0])
        private set
    /** تم هدف؛ current با انیمیشن به آن می‌رسد (animate را در قاب اصلی صدا بزنید) */
    var targetId: String by androidx.compose.runtime.mutableStateOf(Themes[0].id)
        private set
    private var from: Palette = Themes[0]

    fun set(id: String) {
        val t = Themes.firstOrNull { it.id == id } ?: Themes[0]
        if (t.id == targetId && current.id == t.id) return
        from = current
        targetId = t.id
    }

    /** عوض شدن تم را نرم می‌کند (رنگ‌ها در ۴۲۰ میلی‌ثانیه از تم قبلی به تم تازه می‌رسند) */
    @Composable
    fun animate() {
        val target = Themes.firstOrNull { it.id == targetId } ?: Themes[0]
        androidx.compose.runtime.LaunchedEffect(targetId) {
            if (current.id == target.id) return@LaunchedEffect
            val start = from
            androidx.compose.animation.core.Animatable(0f).animateTo(1f, tween(420, easing = FastOutSlowInEasing)) {
                current = lerpPalette(start, target, value)
            }
            current = target
        }
    }
}

object K {
    val Gold: Color get() = ThemeState.current.gold
    val GoldLight: Color get() = ThemeState.current.goldLight
    val GoldDeep: Color get() = ThemeState.current.goldDeep
    /** رنگ دوم تم (گرادیان نورها و لبه‌ها) */
    val Gold2: Color get() = ThemeState.current.gold2
    val Shine: Color get() = ThemeState.current.shine
    val Ink: Color get() = ThemeState.current.ink
    val Text: Color get() = ThemeState.current.text
    val Sub: Color get() = ThemeState.current.sub
    /** پله‌های گرادیان پس‌زمینه‌ی هر تم (بالا، وسط، پایین) */
    val BgTop: Color get() = ThemeState.current.bgTop
    val BgMid: Color get() = ThemeState.current.bgMid
    val BgBottom: Color get() = ThemeState.current.bgBottom
    val Line: Color get() = ThemeState.current.gold.copy(alpha = .25f)
    val Surface: Color get() = ThemeState.current.surface
    val Surface2: Color get() = ThemeState.current.surface2
    val Panel: Color get() = ThemeState.current.panel
    val OnGold: Color get() = ThemeState.current.onGold
    val Accent: Color get() = ThemeState.current.accent
    val Ok: Color get() = Good
    /** سبز «خوب» ثابت در همه‌ی تم‌ها (پینگ، پایداری، وضعیت فعال)؛ رنگ‌های وضعیت با تم عوض نمی‌شوند: سبز خوب، زرد متوسط، قرمز بد */
    val Good = Color(0xFF3BE36B)
    /** رنگ «وصل / فعال» (اسمش از قدیم مانده)؛ هر تم رنگ خودش را دارد: زمرد، نعنایی یخی یا آبی‌آسمانی نئونی */
    val Green: Color get() = ThemeState.current.connected
    /** خطا، با تُن همان تم */
    val Red: Color get() = ThemeState.current.danger
    /** هشدار (حجم یا زمان کم)، با تُن همان تم */
    val Warn: Color get() = ThemeState.current.warn
    val UserCountry: Color get() = ThemeState.current.userCountry
    val ToggleOn: Color get() = ThemeState.current.toggleOn
    /** کلید «وصل» وسط دکمه‌ی اتصال */
    val Power: Color get() = ThemeState.current.power
}

fun glow(c: Color, r: Float = 22f) = Shadow(color = c.copy(alpha = .75f), offset = Offset.Zero, blurRadius = r)

// ------------------------------------------------------------------ توکن‌های دیزاین

/**
 * سیستم اندازه‌های ثابت کل برنامه:
 * فاصله‌گذاری فقط از این پله‌ها (۴، ۸، ۱۲، ۱۶، ۲۰، ۲۴) و گردی گوشه‌ها فقط از RT تا RX.
 * هر اندازه‌ی دیگری (ارتفاع ردیف، دکمه، آیکون، پرچم) هم فقط از همین‌جا خوانده می‌شود.
 */
object D {
    val S1 = 4.dp
    val S2 = 8.dp
    val S3 = 12.dp
    val S4 = 16.dp
    val S5 = 20.dp
    val S6 = 24.dp
    /** گردی گوشه‌ها: T برچسب، S چیپ و ردیف، M دکمه و کارت کوچک، L کارت، XL پنل و کارت بزرگ */
    val RT = 8.dp
    val RS = 12.dp
    val RM = 16.dp
    val RL = 20.dp
    val RX = 26.dp
    /** ارتفاع دکمه‌ی اصلی هر صفحه (تست دوباره، تمدید سرویس، …) */
    val BtnH = 48.dp
    /** ارتفاع دکمه‌های کوچک (دوباره، بروزرسانی، کپی سابلینک، …) */
    val BtnS = 36.dp
    /** حاشیه‌ی کناری همه‌ی صفحه‌ها */
    val PagePad = 16.dp
    /** padding داخلی همه‌ی کارت‌ها و فاصله‌ی بین کارت‌ها */
    val CardPad = 12.dp
    val CardGap = 8.dp
    /** ارتفاع ثابت ردیف‌های لیستی: ردیف اصلی (کشور، تنظیمات، سرور پنل) و ردیف داخلی (کانفیگ داخل کارت باز) */
    val RowH = 52.dp
    val RowS = 40.dp
    /** آیکون ردیف‌ها، ظرف آیکون و پرچم */
    val Icon = 20.dp
    val IconBox = 36.dp
    val IconBoxS = 28.dp
    val Flag = 32.dp
    /** متن‌ها: عنوان صفحه، عنوان کارت/ردیف، زیرعنوان */
    val TxtPage = 20.sp
    val TxtTitle = 14.sp
    val TxtSub = 11.sp
    /** صفحه‌ی خانه (S: گوشی کوتاه، LocalCompact): کاشی حالت، کارت سرور وصل، کارت پینگ/دانلود/آپلود، نوار اشتراک */
    val Tile = 52.dp
    val TileS = 46.dp
    val HomeRow = 44.dp
    val HomeRowS = 40.dp
    val HomeStat = 48.dp
    val HomeStatS = 42.dp
    val HomeUsage = 34.dp
    val HomeUsageS = 30.dp
}

/**
 * لمسی با فیدبک فشردن (کمی کوچک می‌شود و با فنر برمی‌گردد)؛ به‌جای clickable برای دکمه‌ها و کارت‌های مهم.
 */
@Composable
fun Modifier.pressable(enabled: Boolean = true, scale: Float = .965f, onClick: () -> Unit): Modifier {
    val s = remember { Animatable(1f) }
    return this
        .graphicsLayer { scaleX = s.value; scaleY = s.value }
        .pointerInput(enabled) {
            detectTapGestures(
                onPress = {
                    if (!enabled) return@detectTapGestures
                    s.animateTo(scale, tween(90))
                    tryAwaitRelease()
                    s.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
                },
                onTap = { if (enabled) onClick() },
            )
        }
}

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

/**
 * پس‌زمینه‌ی شیشه‌ای (۱.۳۲): گرادیان آرام زمینه‌ی تم از بالا به پایین و دو هاله‌ی خیلی کم‌رنگ رنگ تم
 * (بالا سمت راست پشت نقشه، وسط سمت چپ پشت دکمه)، تا کارت‌های شیشه‌ای رویش عمق بگیرند. ثابت است و هر فریم کشیده نمی‌شود.
 * calm: صفحه‌های آرام (ورود، شروع): هاله‌ها کم‌رنگ‌تر.
 */
@Composable
fun KankiBackground(accent: Color = K.Gold, calm: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(K.Ink)) {
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                val w = size.width
                val h = size.height
                drawRect(Brush.verticalGradient(listOf(K.BgTop, K.BgMid, K.BgBottom)))
                val k = if (calm) .7f else 1f
                val c1 = Offset(w * .72f, h * .17f)
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .10f * k), accent.copy(alpha = .03f * k), Color.Transparent), c1, w * .85f), w * .85f, c1)
                val c2 = Offset(w * .25f, h * .55f)
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .07f * k), Color.Transparent), c2, w * .75f), w * .75f, c2)
                // وینیت ملایم: لبه‌ها کمی تیره‌تر
                drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .35f)), Offset(w / 2, h * .5f), size.maxDimension * .75f))
            },
        )
        content()
    }
}

// ------------------------------------------------------------------ لوگو

/**
 * لوگوی کانکی (۱.۳۳، همان طرح آیکون برنامه): حلقه‌ی شیشه‌ای تیره با لبه‌ی نئونی و حرف K بولد هندسی (مسیر، نه فونت) با گرادیان
 * رنگ تم. animate: حلقه‌ی نور دور لبه آرام می‌چرخد. sweep: جای نوار نور روی شیشه (۰ تا ۱؛ منفی = بدون نوار). glow: هاله‌ی پشت (۰ تا ۱).
 */
@Composable
fun Logo3D(size: Dp, animate: Boolean = false, sweep: Float = -1f, glow: Float = 0f) {
    val clock = if (animate) motionTime() else null
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val r = w / 2
        val c = center
        val a = K.Gold
        if (glow > 0f) drawCircle(Brush.radialGradient(listOf(a.copy(alpha = .38f * glow), Color.Transparent), c, r * 1.7f), r * 1.7f, c)
        // سایه‌ی نرم زیر نشان
        drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = .6f), Color.Transparent), c + Offset(0f, r * .12f), r * 1.15f), r * 1.15f, c + Offset(0f, r * .12f))
        // بدنه‌ی شیشه‌ی تیره
        drawCircle(Brush.linearGradient(listOf(lerp(K.BgMid, Color.White, .10f), lerp(K.BgMid, Color.Black, .55f)), Offset(w * .2f, 0f), Offset(w * .7f, w)), r, c)
        drawCircle(Brush.radialGradient(listOf(a.copy(alpha = .14f), Color.Transparent), Offset(c.x, w * 1.02f), r), r, c)
        // لبه‌ی نئونی: روشن بالا-چپ، رنگ تم، تیره‌تر پایین-راست
        val rimW = maxOf(1.3.dp.toPx(), w * .045f)
        drawCircle(a.copy(alpha = .22f), r - rimW / 2, c, style = Stroke(rimW * 2.2f))
        drawCircle(
            Brush.linearGradient(listOf(lerp(a, Color.White, .55f), a, K.Gold2), Offset(w * .1f, w * .05f), Offset(w * .9f, w)),
            r - rimW / 2, c, style = Stroke(rimW),
        )
        drawCircle(a.copy(alpha = .25f), r * .86f, c, style = Stroke(maxOf(.6.dp.toPx(), w * .012f)))
        // برق شیشه بالا
        drawOval(Brush.verticalGradient(listOf(Color.White.copy(alpha = .22f), Color.Transparent), startY = c.y - r * .78f, endY = c.y - r * .2f),
            Offset(c.x - r * .6f, c.y - r * .8f), Size(r * 1.2f, r * .62f))
        // حرف K: مسیر هندسی در کادر ۳۱×۳۲ (همان آیکون برنامه)، وسط دایره
        val k = r * 1.08f / 32f
        val ox = c.x - 15.5f * k
        val oy = c.y - 16f * k
        val pts = floatArrayOf(0f, 0f, 7.5f, 0f, 7.5f, 12.2f, 20f, 0f, 30.2f, 0f, 15.6f, 14.2f, 31f, 32f, 21f, 32f, 10.6f, 19.6f, 7.5f, 22.6f, 7.5f, 32f, 0f, 32f)
        val kp = Path().apply {
            moveTo(ox + pts[0] * k, oy + pts[1] * k)
            var i = 2
            while (i < pts.size) { lineTo(ox + pts[i] * k, oy + pts[i + 1] * k); i += 2 }
            close()
        }
        drawPath(kp, a.copy(alpha = .30f), style = Stroke(w * .05f, join = StrokeJoin.Round))
        drawPath(kp, Brush.linearGradient(listOf(lerp(a, Color.White, .6f), a, lerp(a, Color.Black, .25f)), Offset(ox, oy), Offset(ox + 22f * k, oy + 32f * k)))
        drawPath(kp, Color.White.copy(alpha = .35f), style = Stroke(maxOf(.5.dp.toPx(), w * .008f), join = StrokeJoin.Round))
        if (clock != null) {
            val rot = clock.value * 110f % 360f
            rotate(rot, c) {
                drawCircle(Brush.sweepGradient(listOf(Color.Transparent, Color.Transparent, a.copy(alpha = .9f), lerp(a, Color.White, .6f), Color.Transparent), c),
                    r + 2.5.dp.toPx(), c, style = Stroke(1.6.dp.toPx()))
            }
        }
        if (sweep in -.4f..1.4f) {
            val face = Path().apply { addOval(Rect(c, r)) }
            clipPath(face) {
                val x = sweep * w
                drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .28f), Color.Transparent), Offset(x - w * .22f, 0f), Offset(x + w * .22f, w * .5f)), size = Size(w, w))
            }
        }
    }
}

@Composable
fun BrandTitle(size: TextUnit = 26.sp, text: String = "KANKI VPN") {
    T(text, size = size, color = K.Gold, weight = FontWeight.Black, font = Orbitron, shadow = glow(K.Gold, 28f), ltr = true, maxLines = 1)
}


// ------------------------------------------------------------------ نور نئونی

private val neonLayers = listOf(4.5f to .07f, 3.0f to .15f, 1.9f to .32f, 1.0f to 1f)

/** قوس نئونی با دنباله‌ی محوشونده (مثل ستاره‌ی دنباله‌دار) */
fun DrawScope.neonComet(color: Color, head: Float, length: Float, c: Offset, r: Float, w: Float, alpha: Float = 1f) {
    val tl = Offset(c.x - r, c.y - r)
    val sz = Size(r * 2, r * 2)
    val seg = 16
    for (i in 0 until seg) {
        val f = (i + 1f) / seg
        val start = head - length + length * i / seg
        for ((m, a) in neonLayers) {
            drawArc(color.copy(alpha = a * f * alpha), start, length / seg + .6f, false, tl, sz, style = Stroke(w * m, cap = StrokeCap.Butt))
        }
    }
    val rad = Math.toRadians(head.toDouble())
    val p = Offset(c.x + r * cos(rad).toFloat(), c.y + r * sin(rad).toFloat())
    for ((m, a) in neonLayers) drawCircle(color.copy(alpha = a * alpha), w * m * .7f, p)
    drawCircle(Color.White.copy(alpha = alpha), w * .5f, p)
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
 * سایه‌ی نرم واقعی (با محو شدن) زیر یک شکل، کمی پایین‌تر از خودش: هر کارت و دکمه انگار از صفحه بیرون آمده (۱.۲۹).
 * depth فاصله‌ی سایه تا شکل؛ glow رنگ تم که کم‌رنگ دور سایه پخش می‌شود (Unspecified = بدون رنگ).
 */
fun DrawScope.liftShadow(o: Outline, depth: Float, alpha: Float = .55f, glow: Color = Color.Unspecified) {
    val path = Path().apply { addOutline(o) }.asAndroidPath()
    drawIntoCanvas { cv ->
        val nc = cv.nativeCanvas
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        if (glow.isSpecified) {
            paint.color = glow.copy(alpha = glow.alpha * .22f).toArgb()
            paint.maskFilter = android.graphics.BlurMaskFilter(depth * 2.4f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            nc.drawPath(path, paint)
        }
        paint.color = Color.Black.copy(alpha = alpha).toArgb()
        paint.maskFilter = android.graphics.BlurMaskFilter(depth * 1.6f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        nc.save(); nc.translate(0f, depth); nc.drawPath(path, paint); nc.restore()
    }
}

/**
 * لبه‌ی برجسته (bevel) داخل یک شکل: خط نور نازک بالای لبه و سایه‌ی ملایم پایین، تا سطح مثل دکمه‌ی فیزیکی دیده شود.
 * باید داخل clip همان شکل کشیده شود.
 */
fun DrawScope.bevel(o: Outline, strength: Float = 1f, light: Color = Color.White) {
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .30f * strength)), startY = h * .55f, endY = h))
    drawOutline(
        o,
        Brush.verticalGradient(listOf(light.copy(alpha = .38f * strength), light.copy(alpha = .06f * strength), Color.Transparent), startY = 0f, endY = h * .5f),
        style = Stroke(1.4.dp.toPx()),
    )
}

/**
 * شیشه‌ی کریستالی (۱.۳۲، طرح «Kanki Glass»؛ از ۱.۳۲.۳ برق سفید نصف شد تا رویه تیره و شفاف بماند و فقط لبه بدرخشد): رویه‌ی نیمه‌شفاف تیره با نور مورب، برق لبه‌ی بالا (specular)، سایه‌ی
 * عمیق زیر کارت برای جدا شدن لایه‌ها و لبه‌ی گرادیانی (سفید در گوشه‌ی بالا، کم‌رنگ وسط، رنگ تم در گوشه‌ی پایین).
 * selected: ته‌رنگ تم داخل شیشه، لبه‌ی نئونی کامل و درخشش ملایم بیرون لبه (فقط برای حالت فعال).
 */
fun Modifier.goldGlass(shape: androidx.compose.ui.graphics.Shape, selected: Boolean = false, tint: Color = K.Gold): Modifier = this
    .drawBehind {
        val o = shape.createOutline(size, layoutDirection, this)
        liftShadow(o, 6.dp.toPx(), .5f)
        if (selected) for ((m, a) in listOf(12f to .05f, 7f to .10f, 3.5f to .18f)) drawOutline(o, tint.copy(alpha = a), style = Stroke(m.dp.toPx()))
    }
    .clip(shape)
    .drawBehind {
        // همه (دکمه، چیپ، کارت): شیشه‌ی کریستالی مثل طرح؛ رویه‌ی روشن‌تر بالا-چپ، تیره پایین-راست
        val small = size.height < 76.dp.toPx()
        val w = size.width
        val h = size.height
        drawRect(
            Brush.linearGradient(
                if (selected) listOf(lerp(K.BgMid, tint, .24f), lerp(K.BgMid, tint, .09f), lerp(K.BgMid, Color.Black, .4f))
                else listOf(lerp(K.BgMid, Color.White, if (small) .065f else .05f), lerp(K.BgMid, Color.White, .018f), lerp(K.BgMid, Color.Black, .36f)),
                Offset(0f, 0f), Offset(w * .45f, h * 1.1f),
            ),
        )
        // نور نرم گوشه‌ی بالا
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = if (small) .10f else .06f), Color.Transparent), Offset(w * .28f, 0f), maxOf(w, h) * .75f), maxOf(w, h) * .75f, Offset(w * .28f, 0f))
    }
    .drawWithContent {
        val small = size.height < 76.dp.toPx()
        val w = size.width
        val h = size.height
        // کلاهک براق شیشه (نیم‌بیضی بالا)، مثل دکمه‌های طرح؛ روی کارت بزرگ پهن‌تر و کم‌ارتفاع‌تر
        val capH = if (small) h * .8f else minOf(h * .5f, 70.dp.toPx())
        val capW = if (small) w * .72f else w * .9f
        drawOval(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = if (small) .14f else .08f), Color.Transparent), startY = 0f, endY = capH * .55f),
            Offset((w - capW) / 2, -capH * .45f), Size(capW, capH),
        )
        // سایه‌ی داخلی پایین و خط نور لبه‌ی بالا
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (small) .30f else .24f)), startY = h * .6f, endY = h))
        val o = shape.createOutline(size, layoutDirection, this)
        drawOutline(o, Brush.verticalGradient(listOf(Color.White.copy(alpha = .5f), Color.Transparent), endY = minOf(h * .35f, 40.dp.toPx())), style = Stroke(1.2.dp.toPx()))
        drawContent()
    }
    .border(
        if (selected) 1.4.dp else 1.dp,
        Brush.linearGradient(
            if (selected) listOf(lerp(tint, Color.White, .5f), tint, tint.copy(alpha = .35f), if (tint == K.Gold) K.Gold2 else tint)
            else listOf(Color.White.copy(alpha = .50f), Color.White.copy(alpha = .08f), Color.Black.copy(alpha = .35f), (if (tint == K.Gold) K.Gold2 else tint).copy(alpha = .5f)),
        ),
        shape,
    )

/**
 * دکمه‌ی پر براق (۱.۳۲): گرادیان رنگ تم (روشن بالا، تیره پایین)، برق شیشه‌ای نیمه‌ی بالا، لبه‌ی نور سفید، سایه‌ی عمیق
 * و درخشش نئونی ملایم. همه‌ی دکمه‌های اصلی و کوچک پر از همین ساخته می‌شوند.
 */
fun Modifier.goldFill(shape: androidx.compose.ui.graphics.Shape, enabled: Boolean = true): Modifier = this
    .drawBehind {
        val o = shape.createOutline(size, layoutDirection, this)
        liftShadow(o, 5.dp.toPx(), if (enabled) .5f else .25f, if (enabled) K.Gold else Color.Unspecified)
    }
    .clip(shape)
    .drawBehind { drawRect(Brush.linearGradient(listOf(K.GoldLight, K.Gold, lerp(K.Gold2, K.GoldDeep, .35f)), Offset(0f, 0f), Offset(size.width * .6f, size.height * 1.2f))) }
    .drawWithContent {
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .22f), Color.White.copy(alpha = .04f), Color.Transparent), endY = size.height * .5f))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, K.GoldDeep.copy(alpha = .45f)), startY = size.height * .55f, endY = size.height))
        drawContent()
    }
    .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .9f), K.Gold, K.Gold2, K.GoldLight)), shape)

@Composable
fun GoldButton(text: String, modifier: Modifier = Modifier, loading: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(D.RL)
    Box(
        modifier.height(D.BtnH)
            .goldFill(shape, enabled = !loading)
            .pressable(enabled = !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        T(if (loading) "لطفاً صبر کنید…" else text, size = 15.sp, color = K.OnGold, weight = FontWeight.Black)
    }
}

/** دکمه‌ی شیشه‌ای دوم (۱.۳۲): همان شیشه‌ی کریستالی دکمه‌ها با نوشته‌ی رنگ تم */
@Composable
fun LineButton(text: String, modifier: Modifier = Modifier, color: Color = K.Gold, onClick: () -> Unit) {
    val shape = RoundedCornerShape(D.RL)
    Box(
        modifier.height(D.BtnH).goldGlass(shape).pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T(text, size = 15.sp, color = color, weight = FontWeight.Black) }
}

@Composable
fun Pill(text: String, color: Color = K.Gold, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    var m = modifier.clip(shape).background(color.copy(alpha = .10f)).border(1.dp, color.copy(alpha = .7f), shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Box(m.padding(horizontal = D.S3, vertical = D.S1)) { T(text, size = 12.sp, color = color) }
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

/** آیکون حساب کاربری */
fun DrawScope.accountIcon(c: Color) {
    val w = size.width
    drawCircle(c, w * .20f, Offset(w / 2, w * .32f))
    drawArc(c, 180f, 180f, true, topLeft = Offset(w * .14f, w * .60f), size = Size(w * .72f, w * .72f))
}

// ------------------------------------------------------------------ ربات کانکی

/** رنگ چشم‌های ربات: لهجه‌ی همان تم (یاقوت کبود، آبی فولادی یا سرخابی) */
private val RobotEye: Color get() = K.Accent

/** ربات بامزه‌ی طلایی با چشم‌های به رنگ لهجه‌ی تم؛ animate=false برای جاهای کوچک (مثل دکمه‌ی سربرگ) */
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
        drawCircle(K.Panel.copy(alpha = 1f), w * .055f, bc)
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
    drawRoundRect(lerp(K.Ink, K.GoldDeep, .12f), Offset(vx, vy), Size(vw, vh), CornerRadius(vh * .45f))
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
val BubbleBg: Color get() = K.Shine
val BubbleText: Color get() = K.OnGold

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

private val svgCache = HashMap<String, Path>()

/** مسیر آیکون از متن SVG روی شبکه‌ی ۲۴×۲۴ (یک بار ساخته و نگه داشته می‌شود) */
fun svgPath(d: String): Path = svgCache.getOrPut(d) { androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toPath() }

/** کشیدن یک آیکون SVG (شبکه‌ی ۲۴) به اندازه‌ی بوم: خط با ضخامت stroke و پر شدن اختیاری */
fun DrawScope.iconPath(d: String, c: Color, stroke: Float = 2f, fill: Brush? = null) {
    val k = size.width / 24f
    withTransform({ scale(k, k, pivot = Offset.Zero) }) {
        val path = svgPath(d)
        fill?.let { drawPath(path, it) }
        drawPath(path, c, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** آیکون‌های نوار پایین (۱.۲۹): خطی و دوتُن؛ fill (۰..۱) تب انتخاب‌شده را پر می‌کند */
fun DrawScope.navIcon(kind: Int, c: Color, fill: Float = 0f) {
    val f = Brush.verticalGradient(listOf(c.copy(alpha = .15f + .40f * fill), c.copy(alpha = .04f + .16f * fill)), endY = size.height)
    val u = size.width / 24f
    when (kind) {
        0 -> { // خانه: سقف، بدنه و در
            iconPath("M4.5 10.2V19a1.8 1.8 0 0 0 1.8 1.8h11.4a1.8 1.8 0 0 0 1.8-1.8v-8.8", c, 1.9f, f)
            iconPath("M2.6 11.4 12 3.3l9.4 8.1", c, 2.1f)
            drawRoundRect(c.copy(alpha = .55f + .45f * fill), Offset(9.7f * u, 14f * u), Size(4.6f * u, 6.8f * u), CornerRadius(1.3f * u))
        }
        1 -> { // سرورها: کره با نصف‌النهار و مدار
            iconPath("M12 2.8a9.2 9.2 0 1 0 0 18.4a9.2 9.2 0 1 0 0-18.4z", c, 1.9f, f)
            iconPath("M12 2.8c-2.6 2.5-3.9 5.6-3.9 9.2s1.3 6.7 3.9 9.2c2.6-2.5 3.9-5.6 3.9-9.2S14.6 5.3 12 2.8z", c, 1.5f)
            iconPath("M3.2 9h17.6M3.2 15h17.6", c, 1.5f)
        }
        2 -> { // تست سرعت: سرعت‌سنج با درجه‌ها و عقربه
            iconPath("M4.4 18.6a9 9 0 1 1 15.2 0", c, 2f)
            iconPath("M4.4 18.6a9 9 0 1 1 15.2 0z", Color.Transparent, 0f, f)
            for (a in listOf(-150f, -120f, -90f, -60f, -30f)) {
                val r = Math.toRadians(a.toDouble())
                val o = Offset(12f * u, 13f * u)
                drawLine(c.copy(alpha = .7f), o + Offset(6.2f * u * cos(r).toFloat(), 6.2f * u * sin(r).toFloat()), o + Offset(7.4f * u * cos(r).toFloat(), 7.4f * u * sin(r).toFloat()), 1.3f * u, StrokeCap.Round)
            }
            drawLine(c, Offset(12f * u, 13f * u), Offset(16.4f * u, 8.4f * u), 2.1f * u, StrokeCap.Round)
            drawCircle(c, 2f * u, Offset(12f * u, 13f * u))
        }
        else -> { // تنظیمات: سه کشویی با دستگیره‌ی دوتُن
            for ((y, k) in listOf(6f to 15.5f, 12f to 8.5f, 18f to 13.5f)) {
                drawLine(c.copy(alpha = .75f), Offset(3f * u, y * u), Offset(21f * u, y * u), 1.9f * u, StrokeCap.Round)
                drawCircle(lerp(K.Panel.copy(alpha = 1f), c, .25f + .45f * fill), 2.7f * u, Offset(k * u, y * u))
                drawCircle(c, 2.7f * u, Offset(k * u, y * u), style = Stroke(1.8f * u))
            }
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
fun SettingIcon(kind: String, tint: Color = K.Gold, size: Dp = D.IconBox) {
    val shape = RoundedCornerShape(D.RS)
    Box(
        // ۱.۲۹ برجسته: سایه زیر جعبه، نور لبه‌ی بالا و سایه‌ی پایین
        Modifier.size(size)
            .drawBehind { liftShadow(shape.createOutline(this.size, layoutDirection, this), 3.dp.toPx(), .55f, tint) }
            .clip(shape)
            .background(K.Panel.copy(alpha = 1f))
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = .30f), tint.copy(alpha = .08f))))
            .drawWithContent { bevel(shape.createOutline(this.size, layoutDirection, this), 1f, lerp(tint, Color.White, .5f)); drawContent() }
            .border(1.dp, Brush.verticalGradient(listOf(lerp(tint, Color.White, .3f), tint.copy(alpha = .35f))), shape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * .58f)) { settingIcon(kind, lerp(tint, Color.White, .12f)) }
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

/**
 * کلید شیشه‌ای (۱.۳۲، مثل طرح): شیار تو رفته‌ی تیره، دستگیره‌ی سفید براق؛ روشن: شیار گرادیان رنگ تم با درخشش و دستگیره
 * به سمت پایان صفحه. به‌جای Switch خود اندروید در همه‌ی برنامه.
 */
@Composable
fun GlassSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier, small: Boolean = false) {
    // ۱.۳۲.۷: همان Switch استاندارد اندروید (مثل ۱.۳۱) با رنگ‌های تم؛ کلید دست‌ساز ۱.۳۲.۰ (کلیک بی‌نقش و بی‌بازخورد) کنار گذاشته شد
    androidx.compose.material3.Switch(
        checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
        modifier = if (small) modifier.graphicsLayer { scaleX = .85f; scaleY = .85f } else modifier,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = K.OnGold, checkedTrackColor = K.Gold, checkedBorderColor = K.Gold,
            uncheckedThumbColor = K.Sub, uncheckedTrackColor = Color(0x33FFFFFF), uncheckedBorderColor = Color(0x33FFFFFF),
        ),
    )
}
