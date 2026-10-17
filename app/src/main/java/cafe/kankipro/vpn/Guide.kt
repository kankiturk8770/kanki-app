package cafe.kankipro.vpn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ربات راهنما: آموزش قدم‌به‌قدم برنامه برای کاربر تازه + هشدارهای ربات روی صفحه‌ی اتصال

// ------------------------------------------------------------------ آموزش قدم‌به‌قدم

/** یک قدم آموزش: کدام بخش روشن شود و ربات چه بگوید */
class GuideStep(val target: String?, val title: String, val text: String)

val guideSteps = listOf(
    GuideStep(null, "سلام! من کانکی‌ام 👋", "دستیار کوچولوی تو! بیا در یک دقیقه همه‌جای برنامه رو با هم ببینیم."),
    GuideStep("connect", "دکمه‌ی اتصال", "یه بار لمسش کن تا با حالتی که انتخاب کردی وصل شی. وقتی وصلی حلقه‌ش به رنگ همون حالت می‌چرخه؛ «وصل است» یعنی اینترنتت امنه ✅"),
    GuideStep("map", "نقشه", "اینجا می‌بینی کجایی و به کدوم کشور وصلی؛ سرعت و پینگ زنده‌ت هم پایین صفحه‌ست."),
    GuideStep("protocols", "حالت‌های اتصال", "«هوشمند» پینگ همه رو می‌گیره و به بهترین وصل می‌شه. «گیمینگ» پروتکل و کشور رو خودت انتخاب می‌کنی. «ضد فیلتر» با یه لمس خودش بهترین راه رو پیدا می‌کنه؛ دوباره بزنی، از سرورها دستی انتخاب می‌کنی. «DNS» فقط بازی‌ها و برنامه‌هایی که انتخاب کردی رو رفع تحریم می‌کنه."),
    GuideStep("sub", "اشتراک تو", "گیگ و روزهای باقی‌مونده‌ت اینجاست. هر وقت کم شد، خودم همین‌جا خبرت می‌کنم 😉"),
    GuideStep("nav_servers", "تب سرورها", "همه‌ی کشورها و کانفیگ‌ها؛ «تست پینگ همه» پینگ UDP و TCP همه رو با هم می‌گیره و با یه لمس روی هر کانفیگ وصل می‌شی."),
    GuideStep("nav_test", "تست سرعت", "سرعت واقعی دانلود، آپلود و پینگت رو با سرورهای Speedtest بسنج."),
    GuideStep("nav_settings", "تنظیمات", "DNS، تم، زبان، تونل‌زنی انتخابی و کیل‌سوییچ همه اینجاست."),
    GuideStep("account", "لینک اشتراک", "اطلاعات اشتراک، کپی لینک و تمدید سرویس با یه لمس."),
    GuideStep("emergency", "ارتباط اضطراری 🆘", "این حباب گفتگو فقط برای وقتیه که اینترنت ملی شد. اون موقع از اینجا مستقیم با ادمین حرف می‌زنی، اسکرین‌شات می‌فرستی و کانفیگی که از فیلتر رد می‌شه رو می‌گیری. در حالت عادی لازمش نداری."),
    GuideStep("robot", "من همیشه اینجام!", "هر وقت سوال داشتی روی من بزن تا دوباره راهنماییت کنم. اینترنت آزاد مبارکت 🚀"),
)

/** وضعیت آموزش و جای بخش‌هایی که باید روشن شوند */
object Guide {
    /** قدم فعلی؛ ‎-1 یعنی آموزش بسته است */
    var step by mutableIntStateOf(-1)
    val targets = mutableStateMapOf<String, Rect>()
    val active get() = step >= 0
    fun start() { step = 0 }
    fun next() { step = if (step + 1 >= guideSteps.size) -1 else step + 1 }
    fun back() { if (step > 0) step-- }
    fun stop() { step = -1 }
}

/** این بخش را برای آموزش علامت می‌زند (جایش روی صفحه ثبت می‌شود) */
fun Modifier.guideTarget(id: String): Modifier = onGloballyPositioned { Guide.targets[id] = it.boundsInRoot() }

/** پرده‌ی آموزش: همه‌جا تیره، بخش مورد نظر روشن با قاب طلایی، و ربات با حباب توضیح */
@Composable
fun GuideOverlay(onFinish: () -> Unit) {
    // آخرین قدم معتبر نگه داشته می‌شود تا هنگام محو شدن پرده، متن قدم اول چشمک نزند
    var idx by remember { mutableIntStateOf(0) }
    if (Guide.step >= 0) idx = Guide.step
    AnimatedVisibility(visible = Guide.step >= 0, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
        val step = guideSteps[idx.coerceIn(0, guideSteps.lastIndex)]
        val last = idx == guideSteps.lastIndex
        val density = LocalDensity.current
        var origin by remember { mutableStateOf(Offset.Zero) }
        var box by remember { mutableStateOf(Size.Zero) }
        val pad = with(density) { 8.dp.toPx() }
        val raw = step.target?.let { Guide.targets[it] }?.takeIf { it.width > 2f && it.height > 2f }
        val hole = raw?.let { Rect(it.left - origin.x - pad, it.top - origin.y - pad, it.right - origin.x + pad, it.bottom - origin.y + pad) }
        val cx = box.width / 2; val cy = box.height / 2
        val spec = tween<Float>(380, easing = FastOutSlowInEasing)
        val l by animateFloatAsState(hole?.left ?: cx, spec, label = "l")
        val t by animateFloatAsState(hole?.top ?: cy, spec, label = "t")
        val r by animateFloatAsState(hole?.right ?: cx, spec, label = "r")
        val b by animateFloatAsState(hole?.bottom ?: cy, spec, label = "b")
        val holeAlpha by animateFloatAsState(if (hole != null) 1f else 0f, tween(250), label = "ha")
        val pulse by rememberInfiniteTransition(label = "gp").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "p")
        fun next() { if (last) { Guide.stop(); onFinish() } else Guide.next() }

        Box(
            Modifier.fillMaxSize()
                .onGloballyPositioned { origin = it.positionInRoot(); box = Size(it.size.width.toFloat(), it.size.height.toFloat()) }
                .pointerInput(idx) { detectTapGestures { next() } },
        ) {
            Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
                drawRect(Color(0xD9000000))
                if (holeAlpha > 0f) {
                    val cr = CornerRadius(20.dp.toPx())
                    val tl = Offset(l, t); val sz = Size(r - l, b - t)
                    drawRoundRect(Color.Black, tl, sz, cr, blendMode = BlendMode.Clear, alpha = holeAlpha)
                    // قاب طلایی تپنده دور بخش روشن
                    val grow = pulse * 10.dp.toPx()
                    drawRoundRect(K.Gold.copy(alpha = .45f * (1f - pulse) * holeAlpha), Offset(l - grow, t - grow), Size(sz.width + grow * 2, sz.height + grow * 2), CornerRadius(cr.x + grow), style = Stroke(2.dp.toPx()))
                    drawRoundRect(K.Gold.copy(alpha = holeAlpha), tl, sz, cr, style = Stroke(2.dp.toPx()))
                }
            }

            // حباب ربات: اگر بخش روشن بالای صفحه است، زیرش؛ وگرنه بالایش
            val placeBelow = hole != null && hole.center.y < box.height * .5f
            val bubbleMod = when {
                hole == null -> Modifier.align(Alignment.Center)
                placeBelow -> Modifier.align(Alignment.TopCenter).padding(top = with(density) { (hole.bottom + 14.dp.toPx()).coerceAtLeast(0f).toDp() })
                else -> Modifier.align(Alignment.BottomCenter).padding(bottom = with(density) { (box.height - hole.top + 14.dp.toPx()).coerceAtLeast(0f).toDp() })
            }
            GuideBubble(step, idx, last, bubbleMod.padding(horizontal = 16.dp), onNext = { next() }, onBack = { Guide.back() }, onSkip = { Guide.stop(); onFinish() })
        }
    }
}

@Composable
private fun GuideBubble(step: GuideStep, idx: Int, last: Boolean, modifier: Modifier, onNext: () -> Unit, onBack: () -> Unit, onSkip: () -> Unit) {
    KCard(modifier.widthIn(max = 420.dp).fillMaxWidth(), padding = 16.dp, border = K.Gold.copy(alpha = .7f)) {
        Row(verticalAlignment = Alignment.Top) {
            RobotFace(58.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                T(step.title, size = 17.sp, color = K.Gold, weight = FontWeight.Black)
                Spacer(Modifier.height(4.dp))
                T(step.text, size = 14.sp, color = K.Text, weight = FontWeight.Normal)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // نقطه‌های پیشرفت
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                guideSteps.indices.forEach { i ->
                    Box(Modifier.height(6.dp).width(if (i == idx) 16.dp else 6.dp).clip(CircleShape).background(if (i == idx) K.Gold else K.Sub.copy(alpha = .35f)))
                }
            }
            Spacer(Modifier.weight(1f))
            if (!last) {
                T("رد کردن", size = 12.sp, color = K.Sub, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onSkip).padding(horizontal = 8.dp, vertical = 6.dp))
                Spacer(Modifier.width(4.dp))
            }
            if (idx > 0) { SmallLine("قبلی", onClick = onBack); Spacer(Modifier.width(8.dp)) }
            SmallGold(if (last) "بزن بریم!" else "بعدی", onClick = onNext)
        }
    }
}

// ------------------------------------------------------------------ هشدارهای ربات

/** پیام ربات روی صفحه‌ی اتصال. level: ۰ راهنما، ۱ هشدار، ۲ خطر */
data class RobotAlert(
    val id: String,
    val level: Int,
    val text: String,
    val action: String? = null,
    val actionId: String? = null,
    val detail: String? = null,
)

private const val DAY_MS = 86_400_000L

/** پیام مناسب ربات بر اساس وضعیت اشتراک و آخرین خطا (یا null اگر همه‌چیز خوب است) */
fun robotAlertFor(a: Account?, now: Long, error: String?, errorDetail: String?, closedNotice: String? = null): RobotAlert? {
    if (error != null) {
        return when {
            // خطای DNS: پیشنهاد وصل شدن به VPN داده نمی‌شود، فقط پشتیبانی
            errorDetail != null && errorDetail.startsWith("dns:") -> RobotAlert("err:$errorDetail", 2, error, "پشتیبانی", "support", errorDetail)
            error == "اشتراک شما DNS ندارد" -> RobotAlert("err:$error", 1, error)
            errorDetail != null -> RobotAlert("err:$errorDetail", 2, error, "انتخاب هوشمند", "smart", errorDetail)
            else -> RobotAlert("err:$error", 2, error)
        }
    }
    a ?: return null
    // پیام ادمین تا وقتی زمانش تمام نشده و کاربر نبسته‌اش
    a.notice?.let { n ->
        if (n.id != closedNotice && (n.until == 0L || n.until > now)) return RobotAlert("msg:${n.id}", 0, n.text)
    }
    when (a.status.lowercase()) {
        "expired" -> return RobotAlert("expired", 2, "اشتراکت تموم شده 😔 برای ادامه از ربات تمدیدش کن.", "تمدید", "renew")
        "limited" -> return RobotAlert("limited", 2, "حجم اشتراکت تموم شده! با تمدید دوباره وصل می‌شی 🚀", "تمدید", "renew")
        "disabled", "inactive", "frozen", "suspended" -> return RobotAlert("inactive", 2, "اشتراکت غیرفعاله. با پشتیبانی در تماس باش 💬", "پشتیبانی", "support")
    }
    val limit = a.limitGb
    val used = a.usedGb
    if (limit != null && used != null && limit > 0) {
        val left = (limit - used).coerceAtLeast(0.0)
        if (left <= maxOf(1.0, limit * .10)) {
            // فاصله‌ی نشکن بین عدد و واحد، تا «MB» به خط بعد نرود
            val txt = trf("حجمت داره تموم می‌شه! فقط %s مونده ⚠️", ltr(formatBytes((left * 1073741824.0).toLong())))
            return RobotAlert("lowdata", 1, txt, "تمدید", "renew")
        }
    }
    val exp = a.expiresAt
    if (exp != null && exp - now <= 3 * DAY_MS) {
        val days = ((exp - now + DAY_MS - 1) / DAY_MS).coerceAtLeast(0)
        val txt = if (days <= 1) tr("کمتر از یک روز از اشتراکت مونده! ⏳") else trf("فقط %d روز از اشتراکت مونده ⏳", days)
        return RobotAlert("fewdays", 1, txt, "تمدید", "renew")
    }
    return null
}

/** حباب پیام ربات (روی صفحه‌ی اتصال و راهنمای اول هر تب) */
@Composable
fun RobotBanner(alert: RobotAlert, modifier: Modifier = Modifier, onAction: (String) -> Unit = {}, onClose: () -> Unit) {
    val c = when (alert.level) { 2 -> K.Red; 1 -> K.Warn; else -> K.Gold }
    val shape = RoundedCornerShape(22.dp)
    var showDetail by remember(alert.id) { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().clip(shape)
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(c.copy(alpha = .16f), K.Panel.copy(alpha = .92f))))
            .border(1.dp, c.copy(alpha = .55f), shape)
            .padding(start = 8.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RobotFace(44.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            T(alert.text, size = 13.sp, color = K.Text, weight = FontWeight.Bold)
            alert.detail?.let { d ->
                T(if (showDetail) "▲ جزئیات" else "▼ جزئیات", size = 11.sp, color = K.Sub, modifier = Modifier.clickable { showDetail = !showDetail }.padding(vertical = 2.dp))
                if (showDetail) T(d, size = 10.sp, color = K.Sub, font = Poppins, weight = FontWeight.SemiBold, ltr = true)
            }
        }
        alert.action?.let { a ->
            Spacer(Modifier.width(6.dp))
            if (alert.level == 0) SmallLine(a, onClick = { onAction(alert.actionId ?: "") })
            else SmallGold(a, onClick = { onAction(alert.actionId ?: "") })
        }
        Box(
            Modifier.padding(start = 2.dp).size(28.dp).clip(CircleShape).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { T("✕", size = 12.sp, color = K.Sub, font = Poppins, ltr = true) }
    }
}
