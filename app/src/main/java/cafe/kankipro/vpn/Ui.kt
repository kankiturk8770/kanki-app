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
    /** طرح اول طلایی: کارت شیشه‌ای رنگی با خط نور پایین، تب شیشه‌ای، دکمه بدون لبه‌ی خط‌چین و زمینه با هاله و وینیت */
    val classic: Boolean = false,
    /** رنگ آدمک‌های گیمینگ (Unspecified = همان رنگ گیمینگ، مثل دکمه) */
    val runner: Color = Color.Unspecified,
    /** رنگ کلید وسط دکمه‌ی اتصال وقتی وصلی؛ عمداً از خانواده‌ی خود تم نیست تا «وصل» از دور معلوم باشد (۱.۲۹) */
    val power: Color = Color(0xFF3DDC84),
    /** تم «طلای فلزی» (۱.۲۹): لبه‌ها، دکمه‌ها و نوار پایین از جنس فلز براق لوگو، زمینه با شبکه‌ی شش‌ضلعی لوگو */
    val metal: Boolean = false,
) {
    /** نام قدیمی رنگ «وصل» */
    val ok: Color get() = connected
}

val Themes: List<Palette> = listOf(
    // زرد و مشکی (AMOLED، پیش‌فرض): مشکی خالص (پیکسل خاموش روی صفحه‌ی OLED) و فقط خانواده‌ی زرد: زرد زنده، کهربایی، خردلی و
    // کرم؛ هیچ سبز یا رنگ دیگری کنارش نیست (قبلاً سبز نئونی لوگو کنار زرد بود و با هم نمی‌خواند). قرمز فقط برای خطا.
    Palette(
        "yellow", Color(0xFFFFD60A), Color(0xFFFFE566), Color(0xFFC9A100), Color(0xFFFFF3B0),
        Color(0xFF000000), Color(0xFFFFFDF2), Color(0xFF9C9A8E),
        Color(0xFF000000), Color(0xFF000000), Color(0xFF000000),
        Color(0xE6000000), Color(0xF0070600), Color(0xFF000000), Color(0xFF0D0B00),
        accent = Color(0xFFFFF3B0), // کرم زرد
        connected = Color(0xFFFFE566), // زرد روشن درخشان
        modeSmart = Color(0xFFFFD60A), // زرد
        modeGame = Color(0xFFFF9F0A), // کهربایی
        modeAnti = Color(0xFFFFF3B0), // کرم
        modeDns = Color(0xFFE0AE2E), // خردلی
        mix = Color(0xFFFF9F0A),
        danger = Color(0xFFFF453A), warn = Color(0xFFFF9F0A),
        userCountry = Color(0xFFFFF3B0),
        toggleOn = Color(0xFFFFD60A),
        gameAlt = Color(0xFFFFE566), tracer = Color(0xFFFFFFFF),
        navTop = Color(0xFFFFE566), navBottom = Color(0xFFFFD60A), onNav = Color(0xFF0D0B00),
    ),
    // زرشکی: زرشکی با طلایی شامپاینی و هم‌خانواده‌هایشان (صورتی گلی، آلویی)؛ بدون نارنجی
    Palette(
        "crimson", Color(0xFFE8203F), Color(0xFFFF5C74), Color(0xFF9E0E28), Color(0xFFFF7A8E),
        Color(0xFF000000), Color(0xFFFFF5F6), Color(0xFFA8949A),
        Color(0xFF000000), Color(0xFF000000), Color(0xFF000000),
        Color(0xE6070203), Color(0xF00E0406), Color(0xFF070203), Color(0xFFFFFFFF),
        accent = Color(0xFFF2C879), // طلایی شامپاینی
        connected = Color(0xFFF2C879),
        modeSmart = Color(0xFFE8203F), // زرشکی
        modeGame = Color(0xFFF2C879), // طلایی شامپاینی (گیمینگ و KANKI MIX)
        modeAnti = Color(0xFFFF6B8E), // صورتی گلی
        modeDns = Color(0xFFB565D9), // آلویی
        mix = Color(0xFFF2C879),
        danger = Color(0xFFFF4D4D), warn = Color(0xFFF5B342),
        userCountry = Color(0xFFF2C879),
        toggleOn = Color(0xFFE8203F),
        gameAlt = Color(0xFFFF5C74), tracer = Color(0xFFFFE08A),
        // تب انتخاب‌شده: زرشکی عمیق (نه صورتی روشن) با نوشته‌ی سفید
        navTop = Color(0xFFC0152F), navBottom = Color(0xFF6E0918), onNav = Color(0xFFFFFFFF),
    ),
    // طلایی کانکی: طلای مات روی قهوه‌ای سوخته (نه مشکی خالص، تا با «زرد و مشکی» یکی نباشد)؛ فقط طلایی و تُن‌های گرم کنارش
    // (شامپاینی، مسی، برنزی)، کارت‌های شیشه‌ای طلایی با خط نور پایین و تب شیشه‌ای
    Palette(
        "gold", Color(0xFFE8B84A), Color(0xFFF6D88E), Color(0xFFA9761F), Color(0xFFFFF0C8),
        Color(0xFF120B03), Color(0xFFFFF6E3), Color(0xFFB3A68C),
        Color(0xFF1A1006), Color(0xFF110A04), Color(0xFF080502),
        Color(0xD61F150A), Color(0xE6281C0E), Color(0xF21A1208), Color(0xFF241700),
        accent = Color(0xFFEBD3A1), // شامپاینی
        connected = Color(0xFFF6D88E), // طلایی روشن
        modeSmart = Color(0xFFE8B84A), // طلایی
        modeGame = Color(0xFFD9824A), // مسی
        modeAnti = Color(0xFFEBD3A1), // شامپاینی
        modeDns = Color(0xFFB98A55), // برنزی
        mix = Color(0xFFD9824A),
        danger = Color(0xFFE5533D), warn = Color(0xFFE59A3A),
        userCountry = Color(0xFFFFF1CC), // کرم
        toggleOn = Color(0xFFE8B84A),
        gameAlt = Color(0xFFF6D88E), tracer = Color(0xFFFFF0C8),
        navTop = Color(0xFFF6D88E), navBottom = Color(0xFFE8B84A), onNav = Color(0xFF241700),
        classic = true,
    ),
    // بنفش سلطنتی: بنفش با سرخابی و هم‌خانواده‌هایشان (صورتی، یاسی، آبی‌بنفش)؛ همه از یک طیف
    Palette(
        "purple", Color(0xFFA259FF), Color(0xFFC9A2FF), Color(0xFF5B21B6), Color(0xFFE2CCFF),
        Color(0xFF000000), Color(0xFFF6F2FF), Color(0xFF9E94B4),
        Color(0xFF000000), Color(0xFF000000), Color(0xFF000000),
        Color(0xE605030B), Color(0xF00B0715), Color(0xFF05030B), Color(0xFFFFFFFF),
        accent = Color(0xFFFF5CC8), // سرخابی
        connected = Color(0xFFFF8AD8), // صورتی درخشان
        modeSmart = Color(0xFFA259FF), // بنفش
        modeGame = Color(0xFFFF4FC8), // سرخابی
        modeAnti = Color(0xFF7D8BFF), // آبی‌بنفش
        modeDns = Color(0xFFE0C2FF), // یاسی
        mix = Color(0xFFFF4FC8),
        danger = Color(0xFFFF5A6E), warn = Color(0xFFFFB84D),
        userCountry = Color(0xFFFF5CC8),
        toggleOn = Color(0xFFA259FF),
        gameAlt = Color(0xFFC9A2FF), tracer = Color(0xFFFFD1F0),
        navTop = Color(0xFFB57CFF), navBottom = Color(0xFF7B3FE4), onNav = Color(0xFFFFFFFF),
    ),
    // ---- تم‌های تک‌رنگ ۱.۲۸: هر تم فقط یک خانواده‌ی رنگ دارد (روشن، اصلی، تیره و یک لهجه‌ی نزدیک) و زمینه‌اش تیره‌ی همان رنگ است
    // سیاه و لیمویی: مشکی خالص AMOLED و لیمویی؛ کنارش فقط سبزهای هم‌خانواده (نعنایی، زیتونی روشن)
    Palette(
        "lime", Color(0xFFB6FF3C), Color(0xFFD4FF7A), Color(0xFF6FA80F), Color(0xFFEFFFD0),
        Color(0xFF000000), Color(0xFFF6FFE8), Color(0xFF9AA38C),
        Color(0xFF000000), Color(0xFF000000), Color(0xFF000000),
        Color(0xE6000000), Color(0xF0040700), Color(0xFF000000), Color(0xFF0A1200),
        accent = Color(0xFFE8FFB8), // لیمویی کم‌رنگ
        connected = Color(0xFFD4FF7A),
        modeSmart = Color(0xFFB6FF3C), // لیمویی
        modeGame = Color(0xFF3CFFA0), // نعنایی
        modeAnti = Color(0xFFE8FFB8), // لیمویی کم‌رنگ
        modeDns = Color(0xFF8FD94A), // زیتونی روشن
        mix = Color(0xFF3CFFA0),
        danger = Color(0xFFFF4D4D), warn = Color(0xFFFFC23C),
        userCountry = Color(0xFFE8FFB8),
        toggleOn = Color(0xFFB6FF3C),
        gameAlt = Color(0xFFD4FF7A), tracer = Color(0xFFFFFFFF),
        navTop = Color(0xFFD4FF7A), navBottom = Color(0xFFA6F02A), onNav = Color(0xFF0A1200),
        power = Color(0xFF38D9FF), // سبزِ «وصل» روی لیمویی دیده نمی‌شد: فیروزه‌ای روشن
    ),
    // یاقوت کبود: آبی روی سرمه‌ای؛ کنارش آبی یخی و نیلی
    Palette(
        "sapphire", Color(0xFF3D8BFF), Color(0xFF8FBFFF), Color(0xFF1749B8), Color(0xFFD6E7FF),
        Color(0xFF020814), Color(0xFFF2F7FF), Color(0xFF8C9BB8),
        Color(0xFF0A1A3D), Color(0xFF060F26), Color(0xFF020611),
        Color(0xD9081430), Color(0xE60C1C3E), Color(0xF2050C1C), Color(0xFFFFFFFF),
        accent = Color(0xFF7FD8FF), // آبی یخی
        connected = Color(0xFF8FD1FF),
        modeSmart = Color(0xFF3D8BFF), // آبی
        modeGame = Color(0xFF6C7CFF), // نیلی
        modeAnti = Color(0xFF7FD8FF), // آبی یخی
        modeDns = Color(0xFFB7C8FF), // آبی کم‌رنگ
        mix = Color(0xFF6C7CFF),
        danger = Color(0xFFFF5A6A), warn = Color(0xFFFFB547),
        userCountry = Color(0xFF7FD8FF),
        toggleOn = Color(0xFF3D8BFF),
        gameAlt = Color(0xFF8FBFFF), tracer = Color(0xFFE0F0FF),
        navTop = Color(0xFF5A9DFF), navBottom = Color(0xFF2563EB), onNav = Color(0xFFFFFFFF),
    ),
    // فیروزه: فیروزه‌ای روی سبزآبی خیلی تیره؛ کنارش سبزآبی و آبی آسمانی
    Palette(
        "turquoise", Color(0xFF22D3EE), Color(0xFF7CEBFA), Color(0xFF0E7C8C), Color(0xFFD2FAFF),
        Color(0xFF01090B), Color(0xFFEFFDFF), Color(0xFF88A8AD),
        Color(0xFF04252B), Color(0xFF021519), Color(0xFF01080A),
        Color(0xD9031A1F), Color(0xE6062A30), Color(0xF2021014), Color(0xFF00262C),
        accent = Color(0xFF5EEAD4), // سبزآبی
        connected = Color(0xFF7CEBFA),
        modeSmart = Color(0xFF22D3EE), // فیروزه‌ای
        modeGame = Color(0xFF2DD4BF), // سبزآبی
        modeAnti = Color(0xFFA5F3FC), // فیروزه‌ای کم‌رنگ
        modeDns = Color(0xFF38BDF8), // آبی آسمانی
        mix = Color(0xFF2DD4BF),
        danger = Color(0xFFFF5A6A), warn = Color(0xFFFFB547),
        userCountry = Color(0xFFA5F3FC),
        toggleOn = Color(0xFF22D3EE),
        gameAlt = Color(0xFF7CEBFA), tracer = Color(0xFFE6FEFF),
        navTop = Color(0xFF67E8F9), navBottom = Color(0xFF06B6D4), onNav = Color(0xFF00262C),
        power = Color(0xFFB6FF3C), // سبز کنار فیروزه‌ای گم می‌شد: لیمویی
    ),
    // آمتیست: بنفش روی بنفش خیلی تیره؛ فقط بنفش‌ها (یاسی، ارکیده، نیلی) و بدون سرخابی
    Palette(
        "amethyst", Color(0xFFA970FF), Color(0xFFCBA8FF), Color(0xFF5B2BB5), Color(0xFFEBDDFF),
        Color(0xFF05020C), Color(0xFFF7F2FF), Color(0xFFA196B8),
        Color(0xFF1A0D36), Color(0xFF0E0720), Color(0xFF05020D),
        Color(0xD90E0720), Color(0xE6180D33), Color(0xF208041A), Color(0xFFFFFFFF),
        accent = Color(0xFFE3C8FF), // یاسی
        connected = Color(0xFFCBA8FF),
        modeSmart = Color(0xFFA970FF), // بنفش
        modeGame = Color(0xFFC084FC), // ارکیده
        modeAnti = Color(0xFFE3C8FF), // یاسی
        modeDns = Color(0xFF8B8CFF), // نیلی
        mix = Color(0xFFC084FC),
        danger = Color(0xFFFF5A7A), warn = Color(0xFFFFB84D),
        userCountry = Color(0xFFE3C8FF),
        toggleOn = Color(0xFFA970FF),
        gameAlt = Color(0xFFCBA8FF), tracer = Color(0xFFF3E8FF),
        navTop = Color(0xFFB98AFF), navBottom = Color(0xFF7C3AED), onNav = Color(0xFFFFFFFF),
    ),
    // طلای فلزی (۱.۲۹): کپی جنس لوگو؛ مشکی براق، طلای صیقلی با نور سفید، فقط تُن‌های طلا
    Palette(
        "metal", Color(0xFFE9C25C), Color(0xFFF6D27A), Color(0xFF8A5A12), Color(0xFFFFF6C8),
        Color(0xFF000000), Color(0xFFFFF8E6), Color(0xFFB5A88A),
        Color(0xFF0B0805), Color(0xFF050302), Color(0xFF000000),
        Color(0xEB0A0704), Color(0xF2120D06), Color(0xFF050302), Color(0xFF1A1000),
        accent = Color(0xFFF2DFA0),
        connected = Color(0xFFF6D27A),
        modeSmart = Color(0xFFE9C25C),
        modeGame = Color(0xFFF2CF73),
        modeAnti = Color(0xFFFFF0C2),
        modeDns = Color(0xFFC8963A),
        mix = Color(0xFFF2CF73),
        danger = Color(0xFFFF5A4A), warn = Color(0xFFFFB547),
        userCountry = Color(0xFFFFF0C2),
        toggleOn = Color(0xFFE9C25C),
        gameAlt = Color(0xFFFFE7A0), tracer = Color(0xFFFFFFFF),
        navTop = Color(0xFFFFF0C2), navBottom = Color(0xFFC8963A), onNav = Color(0xFF1A1000),
        metal = true,
    ),
)

/** طلای صیقلی لوگو: تیره، طلا، نور سفید، طلا، تیره، طلا (مثل لبه‌ی نشان K) */
val MetalStops = listOf(Color(0xFF7A4E0E), Color(0xFFE9C25C), Color(0xFFFFF6C8), Color(0xFFD4A537), Color(0xFF8A5A12), Color(0xFFF2CF73))
fun metalBrush(start: Offset = Offset.Zero, end: Offset = Offset.Infinite) = Brush.linearGradient(MetalStops, start, end)
fun metalVertical(h: Float) = Brush.verticalGradient(listOf(Color(0xFFFFF6C8), Color(0xFFF2CF73), Color(0xFFD4A537), Color(0xFF8A5A12), Color(0xFFE9C25C)), endY = h)

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
    classic = b.classic,
    runner = if (a.runner.isSpecified && b.runner.isSpecified) lerp(a.runner, b.runner, f) else b.runner,
    power = lerp(a.power, b.power, f),
    metal = b.metal,
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
        // AMOLED: زمینه مشکی خالص و فقط یک هاله‌ی کم‌رنگ رنگ تم پشت دکمه؛ طلایی (classic) همان گرادیان و وینیت طرح اول
        Box(
            Modifier.fillMaxSize().graphicsLayer().drawBehind {
                drawRect(Brush.verticalGradient(listOf(K.BgTop, K.BgMid, K.BgBottom)))
                if (ThemeState.current.classic) {
                    val c = Offset(size.width / 2, size.height * .40f)
                    drawCircle(
                        Brush.radialGradient(listOf(accent.copy(alpha = .10f), accent.copy(alpha = .03f), Color.Transparent), c, size.width * .95f),
                        size.width * .95f, c,
                    )
                    // وینیت: تیره شدن ملایم لبه‌ها برای عمق بیشتر
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = .38f)),
                            Offset(size.width / 2, size.height * .52f),
                            size.maxDimension * .72f,
                        ),
                    )
                } else {
                    val c = Offset(size.width / 2, size.height * .52f)
                    drawCircle(
                        Brush.radialGradient(listOf(accent.copy(alpha = .09f), accent.copy(alpha = .02f), Color.Transparent), c, size.width * .62f),
                        size.width * .62f, c,
                    )
                }
            },
        )
        if (ThemeState.current.metal) HexNet()
        Twinkles(if (calm || Motion.lowEnd) 12 else motes.size, accent)
        content()
    }
}

/** شبکه‌ی شش‌ضلعی طلایی کم‌رنگ پشت تم طلای فلزی (همان طرح داخل لوگو)، با گره‌های کوچک؛ ثابت و یک بار کشیده می‌شود */
@Composable
private fun HexNet() {
    Spacer(Modifier.fillMaxSize().drawWithCache {
        val r = 34.dp.toPx()
        val hw = r * kotlin.math.sqrt(3f)
        val path = Path()
        val nodes = ArrayList<Offset>()
        var row = 0
        var y = -r
        while (y < size.height + r) {
            var x = if (row % 2 == 0) 0f else hw / 2
            while (x < size.width + hw) {
                for (k in 0 until 6) {
                    val a1 = Math.toRadians((60.0 * k - 30)).toFloat()
                    val a2 = Math.toRadians((60.0 * (k + 1) - 30)).toFloat()
                    val p1 = Offset(x + r * cos(a1), y + r * sin(a1))
                    path.moveTo(p1.x, p1.y); path.lineTo(x + r * cos(a2), y + r * sin(a2))
                    if (k % 2 == 0) nodes.add(p1)
                }
                x += hw
            }
            y += r * 1.5f; row++
        }
        val line = Brush.verticalGradient(listOf(Color(0xFFE9C25C).copy(alpha = .10f), Color(0xFFE9C25C).copy(alpha = .03f), Color(0xFFE9C25C).copy(alpha = .08f)))
        onDrawBehind {
            drawPath(path, line, style = Stroke(1.dp.toPx()))
            for (n in nodes) drawCircle(Color(0xFFF2CF73).copy(alpha = .14f), 1.6.dp.toPx(), n)
            // وینیت: وسط روشن‌تر، لبه‌ها مشکی
            drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .75f)), Offset(size.width / 2, size.height * .45f), size.maxDimension * .7f))
        }
    })
}

/** ستاره‌های چشمک‌زن در لایه‌ی جدا (عکس پشتش دوباره کشیده نمی‌شود) */
@Composable
private fun Twinkles(count: Int, accent: Color) {
    val clock by motionTime()
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        val w = size.width
        val h = size.height
        val u = 1.dp.toPx()
        val twinkle = clock / 9f
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

/**
 * عکس لوگو برای هر تم (۱.۲۹): همان نشان فلزی، رنگ‌شده با خانواده‌ی همان تم (`kanki_badge_<تم>`)؛ طلایی و طلای فلزی اصل لوگو.
 * آیکون برنامه هم با همین رنگ‌ها عوض می‌شود (`LauncherIcon`).
 */
fun logoRes(theme: String): Int = when (theme) {
    "yellow" -> R.drawable.kanki_badge_yellow
    "crimson" -> R.drawable.kanki_badge_crimson
    "purple" -> R.drawable.kanki_badge_purple
    "lime" -> R.drawable.kanki_badge_lime
    "sapphire" -> R.drawable.kanki_badge_sapphire
    "turquoise" -> R.drawable.kanki_badge_turquoise
    "amethyst" -> R.drawable.kanki_badge_amethyst
    else -> R.drawable.kanki_badge
}

/** طلایی لوگوی اصل (هاله‌ی پشت لوگو در تم‌های طلایی) */
val LogoGold = Color(0xFFE8B84A)

/**
 * لوگوی کانکی (از ۱.۲۹): نشان طلایی K روی زمینه‌ی مشکی با شبکه‌ی سپر (عکس رندرشده‌ی `kanki_badge`، همان آیکون برنامه).
 * رنگش با تم عوض نمی‌شود. sweep: جای نوار نور روی لوگو (۰ تا ۱؛ منفی یعنی بدون نوار). animate: نوار نور آرام و پیوسته.
 * glow: هاله‌ی طلایی پشت لوگو (۰ تا ۱).
 */
@Composable
fun Logo3D(size: Dp, animate: Boolean = false, sweep: Float = -1f, glow: Float = 0f) {
    // ساعت فقط برای لوگوی متحرک؛ لوگوی ثابت هیچ فریمی نمی‌گیرد
    val clock = if (animate && sweep < 0f) motionTime() else null
    val img = ImageBitmap.imageResource(logoRes(ThemeState.targetId))
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = if (sweep >= 0f) sweep else if (clock != null) (clock.value / 5.2f % 1f) * 2.4f - .4f else -1f
        if (glow > 0f) {
            val gr = w * .8f
            drawCircle(Brush.radialGradient(listOf((if (ThemeState.current.metal || ThemeState.current.id == "gold") LogoGold else K.Gold).copy(alpha = .34f * glow), Color.Transparent), center, gr), gr, center)
        }
        // سایه‌ی نرم زیر نشان (برجستگی)
        drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = .55f), Color.Transparent), center + Offset(0f, h * .05f), w * .56f), w * .56f, center + Offset(0f, h * .05f))
        drawImage(img, dstSize = IntSize(w.toInt(), h.toInt()), filterQuality = FilterQuality.High)
        // نوار نور
        if (sw > -.4f && sw < 1.4f) {
            val face = Path().apply { addOval(Rect(center, w * .49f)) }
            clipPath(face) {
                val x = sw * w
                drawRect(
                    Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .32f), Color.Transparent), Offset(x - w * .22f, 0f), Offset(x + w * .22f, h * .5f)),
                    size = Size(w, h),
                )
            }
        }
    }
}

@Composable
fun BrandTitle(size: TextUnit = 26.sp, text: String = "KANKI VPN") {
    if (ThemeState.current.metal) {
        // طلای فلزی: نوشته با همان گرادیان صیقلی لوگو
        Text(text, style = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Black, fontSize = size, textDirection = TextDirection.Ltr,
            brush = Brush.verticalGradient(listOf(Color(0xFFFFF6C8), Color(0xFFF2CF73), Color(0xFFB8862A), Color(0xFFF6D27A))),
            shadow = Shadow(Color(0xFFE9C25C).copy(alpha = .55f), Offset.Zero, 22f)))
    } else T(text, size = size, color = K.Gold, weight = FontWeight.Black, font = Orbitron, shadow = glow(K.Gold, 28f), ltr = true)
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
 * پنل شیشه‌ای براق با لبه‌ی نئونی طلایی (مثل طرح): شیشه‌ی مشکی نیمه‌شفاف با کمی ته‌رنگ طلایی مورب،
 * براقی سفید بالای کارت، لبه‌ی طلایی روشن، درخشش طلایی بیرون لبه و یک خط نور طلایی وسط لبه‌ی پایین.
 * از ۱.۲۹ سه‌بعدی: سایه‌ی نرم زیر کارت و لبه‌ی برجسته (نور بالا، سایه پایین).
 * selected: لبه‌ی روشن‌تر و ضخیم‌تر، درخشش بیشتر و ته‌رنگ طلایی داخل کارت.
 */
fun Modifier.goldGlass(shape: androidx.compose.ui.graphics.Shape, selected: Boolean = false, tint: Color = K.Gold): Modifier =
    if (ThemeState.current.classic) classicGlass(shape, selected, tint) else this
    // سایه‌ی زیر کارت و درخشش کم‌رنگ لبه (انتخاب‌شده پررنگ‌تر)
    .drawBehind {
        val o = shape.createOutline(size, layoutDirection, this)
        liftShadow(o, 5.dp.toPx(), if (selected) .65f else .55f, tint)
        val layers = if (selected) listOf(12f to .06f, 7f to .12f, 3.5f to .24f) else listOf(8f to .03f, 3f to .07f)
        for ((m, a) in layers) drawOutline(o, tint.copy(alpha = a), style = Stroke(m.dp.toPx()))
    }
    .clip(shape)
    .background(K.Surface)
    .background(
        Brush.linearGradient(
            if (selected) listOf(tint.copy(alpha = .20f), tint.copy(alpha = .05f), Color.Black.copy(alpha = .25f))
            else listOf(tint.copy(alpha = .07f), Color.White.copy(alpha = .015f), Color.Black.copy(alpha = .25f)),
        ),
    )
    .drawWithContent {
        // براقی شیشه در بالای کارت و لبه‌ی برجسته
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (selected) .11f else .07f), Color.Transparent), endY = size.height * .45f))
        bevel(shape.createOutline(size, layoutDirection, this), if (selected) 1f else .8f, lerp(tint, Color.White, .6f))
        drawContent()
    }
    // لبه‌ی ظریف: از گوشه‌ی بالا پررنگ، وسط کم‌رنگ و دوباره کمی پررنگ در گوشه‌ی مقابل (طلای فلزی: لبه‌ی فلز صیقلی)
    .then(
        if (ThemeState.current.metal) Modifier.border(if (selected) 2.dp else 1.4.dp, metalBrush(), shape)
        else Modifier.border(
            if (selected) 1.5.dp else 1.dp,
            Brush.linearGradient(
                if (selected) listOf(K.Shine, tint, tint.copy(alpha = .45f), tint)
                else listOf(tint.copy(alpha = .90f), tint.copy(alpha = .30f), tint.copy(alpha = .18f), tint.copy(alpha = .55f)),
            ),
            shape,
        ),
    )

/** شیشه‌ی طرح اول طلایی: رنگ تم در خود شیشه، براقی نیمه‌ی بالا و خط نور طلایی وسط لبه‌ی پایین */
private fun Modifier.classicGlass(shape: androidx.compose.ui.graphics.Shape, selected: Boolean, tint: Color): Modifier = this
    .drawBehind {
        val o = shape.createOutline(size, layoutDirection, this)
        liftShadow(o, 5.dp.toPx(), .6f, tint)
        val layers = if (selected) listOf(12f to .07f, 7f to .14f, 3.5f to .30f) else listOf(9f to .05f, 4.5f to .10f, 2f to .16f)
        for ((m, a) in layers) drawOutline(o, tint.copy(alpha = a), style = Stroke(m.dp.toPx()))
    }
    .clip(shape)
    .background(K.Surface)
    .background(
        Brush.linearGradient(
            if (selected) listOf(tint.copy(alpha = .26f), tint.copy(alpha = .06f), Color.Black.copy(alpha = .25f), tint.copy(alpha = .12f))
            else listOf(tint.copy(alpha = .11f), Color.White.copy(alpha = .02f), Color.Black.copy(alpha = .35f), tint.copy(alpha = .06f)),
        ),
    )
    .drawWithContent {
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (selected) .16f else .10f), Color.Transparent), endY = size.height * .5f))
        bevel(shape.createOutline(size, layoutDirection, this), .8f, K.Shine)
        drawContent()
        val y = size.height - 1.dp.toPx()
        val line = Brush.horizontalGradient(listOf(Color.Transparent, K.GoldLight, Color.Transparent), startX = size.width * .16f, endX = size.width * .84f)
        drawLine(line, Offset(size.width * .16f, y), Offset(size.width * .84f, y), 3.dp.toPx(), alpha = .35f)
        drawLine(line, Offset(size.width * .16f, y), Offset(size.width * .84f, y), 1.2.dp.toPx())
    }
    .border(
        if (selected) 1.5.dp else 1.dp,
        Brush.linearGradient(
            if (selected) listOf(K.Shine, K.GoldLight, tint, K.GoldLight)
            else listOf(tint.copy(alpha = .85f), tint.copy(alpha = .45f), tint.copy(alpha = .70f)),
        ),
        shape,
    )

/**
 * دکمه‌ی پر برجسته (۱.۲۹): گرادیان رنگ تم، سایه‌ی نرم زیرش، نور لبه‌ی بالا و سایه‌ی پایین.
 * همه‌ی دکمه‌های طلایی (اصلی و کوچک) از همین ساخته می‌شوند.
 */
fun Modifier.goldFill(shape: androidx.compose.ui.graphics.Shape, enabled: Boolean = true): Modifier = this
    .drawBehind {
        val o = shape.createOutline(size, layoutDirection, this)
        liftShadow(o, 4.dp.toPx(), if (enabled) .6f else .3f, K.Gold)
    }
    .clip(shape)
    .drawBehind {
        // طلای فلزی: رویه‌ی فلز صیقلی با نوار نور مورب، مثل حرف K لوگو
        if (ThemeState.current.metal) {
            drawRect(metalVertical(size.height))
            drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .35f), Color.Transparent), Offset(size.width * .2f, 0f), Offset(size.width * .55f, size.height)))
        } else drawRect(Brush.verticalGradient(listOf(K.Shine, K.GoldLight, K.Gold, K.GoldDeep)))
    }
    .drawWithContent {
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .30f), Color.Transparent), endY = size.height * .48f))
        bevel(shape.createOutline(size, layoutDirection, this), 1f)
        drawContent()
    }
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .75f), K.GoldLight.copy(alpha = .5f), K.GoldDeep)), shape)

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

@Composable
fun LineButton(text: String, modifier: Modifier = Modifier, color: Color = K.Gold, onClick: () -> Unit) {
    val shape = RoundedCornerShape(D.RL)
    Box(
        modifier.height(D.BtnH)
            .drawBehind { liftShadow(shape.createOutline(size, layoutDirection, this), 4.dp.toPx(), .5f, color) }
            .clip(shape).background(K.Panel.copy(alpha = .9f))
            .background(Brush.verticalGradient(listOf(color.copy(alpha = .14f), Color.Transparent)))
            .drawWithContent { bevel(shape.createOutline(size, layoutDirection, this), .9f, color); drawContent() }
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
