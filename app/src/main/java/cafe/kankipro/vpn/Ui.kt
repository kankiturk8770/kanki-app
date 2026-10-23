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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
) {
    /** نام قدیمی رنگ «وصل» */
    val ok: Color get() = connected
}

val Themes: List<Palette> = listOf(
    // زرشکی (پیش‌فرض): زرشکی غنی با طلایی شامپاینی (وصل، کشور شما، خط مسیر)؛ حالت‌ها زرشکی، آتشی، شامپاینی، آلویی
    Palette(
        "crimson", Color(0xFFE8203F), Color(0xFFFF5C74), Color(0xFF9E0E28), Color(0xFFFF7A8E),
        Color(0xFF000000), Color(0xFFFFF5F6), Color(0xFFA8949A),
        Color(0xFF000000), Color(0xFF000000), Color(0xFF000000),
        Color(0xE60A0305), Color(0xF0120509), Color(0xFF0A0305), Color(0xFFFFFFFF),
        accent = Color(0xFFF2C879), // طلایی شامپاینی
        connected = Color(0xFFF2C879),
        modeSmart = Color(0xFFE8203F), // زرشکی
        modeGame = Color(0xFFFF7A3D), // آتشی
        modeAnti = Color(0xFFF2C879), // شامپاینی
        modeDns = Color(0xFFB565D9), // آلویی
        mix = Color(0xFFFF7A3D),
        danger = Color(0xFFFF4D4D), warn = Color(0xFFFFB347),
        userCountry = Color(0xFFF2C879),
        toggleOn = Color(0xFFE8203F),
        gameAlt = Color(0xFFF2C879), tracer = Color(0xFFFFE08A),
        // تب انتخاب‌شده: زرشکی عمیق (نه صورتی روشن) با نوشته‌ی سفید
        navTop = Color(0xFFC0152F), navBottom = Color(0xFF6E0918), onNav = Color(0xFFFFFFFF),
    ),
    // زرد کانکی: زرد خالص روی مشکی با فیروزه‌ای برقی (وصل، کشور شما، خط مسیر)؛ حالت‌ها زرد، قرمز، فیروزه‌ای، بنفش
    Palette(
        "gold", Color(0xFFFFD60A), Color(0xFFFFE766), Color(0xFFB89600), Color(0xFFFFF6B0),
        Color(0xFF000000), Color(0xFFF7F6F0), Color(0xFF9C998E),
        Color(0xFF000000), Color(0xFF000000), Color(0xFF000000),
        Color(0xE60A0A09), Color(0xF0121210), Color(0xFF0A0A09), Color(0xFF111111),
        accent = Color(0xFF2FD9FF), // فیروزه‌ای برقی
        connected = Color(0xFF2FD9FF),
        modeSmart = Color(0xFFFFD60A), // زرد
        modeGame = Color(0xFFFF3B5C), // قرمز
        modeAnti = Color(0xFF2FD9FF), // فیروزه‌ای
        modeDns = Color(0xFFA78BFA), // بنفش ملایم
        mix = Color(0xFFFF3B5C),
        danger = Color(0xFFFF5A4D), warn = Color(0xFFFFB547),
        userCountry = Color(0xFF2FD9FF),
        toggleOn = Color(0xFFFFD60A),
        gameAlt = Color(0xFFFFD60A), tracer = Color(0xFFFFE766),
        navTop = Color(0xFFFFE766), navBottom = Color(0xFFFFD60A), onNav = Color(0xFF111111),
    ),
    // بنفش سلطنتی: بنفش روشن با نعنایی یخی (وصل، کشور شما، خط مسیر)؛ حالت‌ها بنفش، صورتی، نعنایی، کهربایی
    Palette(
        "purple", Color(0xFFA259FF), Color(0xFFC9A2FF), Color(0xFF5B21B6), Color(0xFFC9A2FF),
        Color(0xFF000000), Color(0xFFF6F2FF), Color(0xFF9E94B4),
        Color(0xFF000000), Color(0xFF000000), Color(0xFF000000),
        Color(0xE6080512), Color(0xF00F0A1C), Color(0xFF07040E), Color(0xFFFFFFFF),
        accent = Color(0xFF3DF5C8), // نعنایی یخی
        connected = Color(0xFF3DF5C8),
        modeSmart = Color(0xFFA259FF), // بنفش
        modeGame = Color(0xFFFF5FA2), // صورتی
        modeAnti = Color(0xFF3DF5C8), // نعنایی
        modeDns = Color(0xFFFFB84D), // کهربایی
        mix = Color(0xFFFF5FA2),
        danger = Color(0xFFFF5A6E), warn = Color(0xFFFFB84D),
        userCountry = Color(0xFF3DF5C8),
        toggleOn = Color(0xFFA259FF),
        gameAlt = Color(0xFFC9A2FF), tracer = Color(0xFF3DF5C8),
        navTop = Color(0xFFC9A2FF), navBottom = Color(0xFFA259FF), onNav = Color(0xFFFFFFFF),
    ),
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
    val Ok: Color get() = ThemeState.current.connected
    /** رنگ «وصل / فعال» (اسمش از قدیم مانده)؛ هر تم رنگ خودش را دارد: زمرد، نعنایی یخی یا آبی‌آسمانی نئونی */
    val Green: Color get() = ThemeState.current.connected
    /** خطا، با تُن همان تم */
    val Red: Color get() = ThemeState.current.danger
    /** هشدار (حجم یا زمان کم)، با تُن همان تم */
    val Warn: Color get() = ThemeState.current.warn
    val UserCountry: Color get() = ThemeState.current.userCountry
    val ToggleOn: Color get() = ThemeState.current.toggleOn
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
 * پس‌زمینه: مشکی خالص با چند ستاره‌ی تک چشمک‌زن و نور گرم خیلی ملایم وسط صفحه (پشت دکمه‌ی اتصال)،
 * تا کارت‌های شیشه‌ای و لبه‌های نئونی طلایی رویش خوب دیده شوند.
 * لایه‌ی نور ثابت است و فقط لایه‌ی سبک ستاره‌ها هر فریم کشیده می‌شود.
 * calm: صفحه‌ی ورود و صفحه‌های آرام (ستاره‌های کمتر).
 */
@Composable
fun KankiBackground(accent: Color = K.Gold, calm: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(K.Ink)) {
        // مشکی خالص (AMOLED) + بافت شش‌ضلعی خیلی کم‌رنگ + هاله‌ی رنگ تم پشت دکمه و رنگ دوم از گوشه‌ی پایین (همه با کد)
        Box(
            Modifier.fillMaxSize().graphicsLayer().drawBehind {
                drawRect(Brush.verticalGradient(listOf(K.BgTop, K.BgMid, K.BgBottom)))
                drawPath(hexGrid(size, 16.dp.toPx()), K.Gold.copy(alpha = .045f), style = Stroke(.7.dp.toPx()))
                val c = Offset(size.width / 2, size.height * .52f)
                drawCircle(
                    Brush.radialGradient(listOf(accent.copy(alpha = .13f), accent.copy(alpha = .03f), Color.Transparent), c, size.width * .75f),
                    size.width * .75f, c,
                )
                val c2 = Offset(0f, size.height)
                drawCircle(Brush.radialGradient(listOf(K.Accent.copy(alpha = .07f), Color.Transparent), c2, size.width * .85f), size.width * .85f, c2)
                val c3 = Offset(size.width, 0f)
                drawCircle(Brush.radialGradient(listOf(K.Gold.copy(alpha = .06f), Color.Transparent), c3, size.width * .6f), size.width * .6f, c3)
            },
        )
        Twinkles(if (calm) 12 else motes.size, accent)
        content()
    }
}

/** شبکه‌ی شش‌ضلعی (بافت زمینه)؛ r شعاع هر شش‌ضلعی */
private fun hexGrid(size: Size, r: Float): Path = Path().apply {
    val w = r * 1.7320508f
    var row = 0
    var y = -r
    while (y < size.height + r) {
        var x = if (row % 2 == 0) 0f else w / 2
        while (x < size.width + w) {
            for (k in 0..6) {
                val a = Math.toRadians(60.0 * k - 30.0)
                val px = x + r * cos(a).toFloat(); val py = y + r * sin(a).toFloat()
                if (k == 0) moveTo(px, py) else lineTo(px, py)
            }
            x += w
        }
        y += r * 1.5f; row++
    }
}

/** ستاره‌های چشمک‌زن در لایه‌ی جدا (عکس پشتش دوباره کشیده نمی‌شود) */
@Composable
private fun Twinkles(count: Int, accent: Color) {
    val t = rememberInfiniteTransition(label = "bg")
    val twinkle by t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "tw")
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        val w = size.width
        val h = size.height
        val u = 1.dp.toPx()
        for (i in 0 until count) {
            val m = motes[i]
            val tw = (sin((twinkle * m.speed + m.phase) * 6.2832f) + 1f) / 2f
            val pos = Offset(m.x * w, m.y * h)
            val c = if (m.warm) K.GoldLight else K.Accent
            val a = .85f * tw * tw
            drawCircle(c.copy(alpha = a * .18f), m.r * 5f * u, pos)
            drawCircle(c.copy(alpha = a), m.r * u, pos)
            if (m.r > 1.1f) {
                val l = m.r * 7f * u * (.6f + .4f * tw)
                drawLine(c.copy(alpha = a * .7f), Offset(pos.x - l, pos.y), Offset(pos.x + l, pos.y), .6f * u, StrokeCap.Round)
                drawLine(c.copy(alpha = a * .7f), Offset(pos.x, pos.y - l), Offset(pos.x, pos.y + l), .6f * u, StrokeCap.Round)
            }
        }
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
 * پنل شیشه‌ای براق با لبه‌ی نئونی طلایی (مثل طرح): شیشه‌ی مشکی نیمه‌شفاف با کمی ته‌رنگ طلایی مورب،
 * براقی سفید بالای کارت، لبه‌ی طلایی روشن، درخشش طلایی بیرون لبه و یک خط نور طلایی وسط لبه‌ی پایین.
 * selected: لبه‌ی روشن‌تر و ضخیم‌تر، درخشش بیشتر و ته‌رنگ طلایی داخل کارت.
 */
fun Modifier.goldGlass(shape: androidx.compose.ui.graphics.Shape, selected: Boolean = false, tint: Color = K.Gold): Modifier = this
    // شیشه‌ی تیره روی زمینه‌ی مشکی: سایه‌ی نرم زیر کارت و درخشش کم‌رنگ لبه (انتخاب‌شده پررنگ‌تر)
    .drawBehind {
        val o = shape.createOutline(size, layoutDirection, this)
        val layers = if (selected) listOf(12f to .06f, 7f to .12f, 3.5f to .24f) else listOf(8f to .03f, 3f to .06f)
        for ((m, a) in layers) drawOutline(o, tint.copy(alpha = a), style = Stroke(m.dp.toPx()))
    }
    .clip(shape)
    .background(K.Surface)
    .background(
        Brush.linearGradient(
            if (selected) listOf(tint.copy(alpha = .20f), tint.copy(alpha = .05f), Color.Black.copy(alpha = .25f))
            else listOf(Color.White.copy(alpha = .06f), Color.White.copy(alpha = .01f), Color.Black.copy(alpha = .25f)),
        ),
    )
    .drawWithContent {
        // براقی خیلی کم شیشه در بالای کارت
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (selected) .10f else .05f), Color.Transparent), endY = size.height * .45f))
        drawContent()
    }
    // لبه‌ی ظریف: از گوشه‌ی بالا پررنگ، وسط کم‌رنگ و دوباره کمی پررنگ در گوشه‌ی مقابل
    .border(
        if (selected) 1.5.dp else 1.dp,
        Brush.linearGradient(
            if (selected) listOf(K.Shine, tint, tint.copy(alpha = .45f), tint)
            else listOf(tint.copy(alpha = .85f), tint.copy(alpha = .15f), Color.White.copy(alpha = .08f), tint.copy(alpha = .45f)),
        ),
        shape,
    )

@Composable
fun Glass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(D.RX)
    Column(
        modifier
            .goldGlass(shape)
            .padding(D.S4),
        content = content,
    )
}

@Composable
fun GoldButton(text: String, modifier: Modifier = Modifier, loading: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(D.RL)
    Box(
        modifier.height(D.BtnH)
            // درخشش طلایی دور دکمه (مثل طرح)
            .drawBehind {
                val o = shape.createOutline(size, layoutDirection, this)
                for ((m, a) in listOf(9f to .08f, 5f to .16f)) drawOutline(o, K.Gold.copy(alpha = a), style = Stroke(m.dp.toPx()))
            }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(K.Shine, K.Gold, K.GoldDeep)))
            .border(1.dp, K.GoldLight.copy(alpha = .7f), shape)
            .pressable(enabled = !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        T(if (loading) "لطفاً صبر کنید…" else text, size = 15.sp, color = K.OnGold, weight = FontWeight.Black)
    }
}

@Composable
fun LineButton(text: String, modifier: Modifier = Modifier, color: Color = K.Gold, onClick: () -> Unit) {
    val shape = RoundedCornerShape(D.RL)
    Box(
        modifier.height(D.BtnH).clip(shape).background(K.Panel.copy(alpha = .8f))
            .border(1.5.dp, color.copy(alpha = .8f), shape)
            .pressable(onClick = onClick),
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
        else -> { // تنظیمات (سه کشویی تنظیم)
            for ((y, k) in listOf(.26f to .64f, .5f to .34f, .74f to .58f)) {
                drawLine(c, Offset(w * .12f, w * y), Offset(w * .88f, w * y), sw, cap = StrokeCap.Round)
                drawCircle(Color.Black, w * .11f, Offset(w * k, w * y))
                drawCircle(c, w * .11f, Offset(w * k, w * y), style = st)
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
