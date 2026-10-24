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
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// ربات راهنما: آموزش قدم‌به‌قدم برنامه برای کاربر تازه + هشدارهای ربات روی صفحه‌ی اتصال

// ------------------------------------------------------------------ آموزش قدم‌به‌قدم

/**
 * یک قدم آموزش: کدام بخش روشن شود، ربات چه بگوید (فارسی و انگلیسی) و کجا باشد (تب، پنل گیمینگ).
 * voice: شماره‌ی فایل صدای همین قدم (res/raw/guide_fa_NN و guide_en_NN)؛ ربات همان متن حباب را می‌خواند
 * (بدون ایموجی، اسم‌های لاتین به فارسی).
 */
class GuideStep(
    val target: String?,
    val title: String,
    val text: String,
    val titleEn: String,
    val textEn: String,
    val voice: Int,
    val tab: KTab = KTab.HOME,
    val sheet: String? = null,
)

val guideSteps = listOf(
    GuideStep(null, "سلام! من کانکی‌ام 👋", "دستیار کوچولوی تو! بیا با هم یه دور کامل توی برنامه بزنیم تا همه‌چیز رو بشناسی.",
        "Hi! I'm Kanki 👋", "Your little helper! Let's take a full tour of the app together, so you know where everything is.", 1),
    GuideStep("connect", "دکمه‌ی اتصال", "یه بار لمسش کن تا با حالتی که انتخاب کردی وصل شی. وقتی وصلی، حلقه‌ش می‌چرخه و وسطش نوشته «متصل»؛ یعنی اینترنتت امنه ✅",
        "Connect button", "Tap it once to connect with the mode you picked. When you're connected, the ring spins and the middle says Connected. That means your internet is safe ✅", 2),
    GuideStep("map", "نقشه", "اینجا می‌بینی کجایی و به کدوم کشور وصلی. خط نورانی، مسیر اتصالته؛ سرعت و پینگ زنده‌ت هم پایین صفحه‌ست.",
        "Map", "Here you see where you are and which country you're connected to. The glowing line is your route, and your live speed and ping are at the bottom.", 3),
    GuideStep("protocols", "حالت‌های اتصال", "چهار حالت داری: «هوشمند» پینگ همه رو می‌گیره و خودش به بهترین وصل می‌شه. «گیمینگ» برای بازیه؛ پروتکل و سرور رو خودت انتخاب می‌کنی. «ضد فیلتر» برای وقتیه که فیلترینگ شدیده. «DNS» وی‌پی‌ان نیست: فقط سایت‌ها و بازی‌های تحریمی از سرور کانکی رد می‌شن و بقیه با سرعت کامل مستقیم می‌رن.",
        "Connection modes", "You have four modes. Smart pings everything and connects to the best by itself. Gaming is for games: you pick the protocol and the server. Anti-filter is for when filtering is heavy. DNS is not a VPN: only sanctioned sites and games go through Kanki's server, and everything else goes direct at full speed.", 4),
    GuideStep("game_fams", "پروتکل‌های گیمینگ", "این چهار پروتکل مخصوص بازی‌ان، همه روی UDP. KANKI MIX پروتکل خود کانکیه و برای بازی از همه بهتره؛ کمترین لگ و پکت‌لاس. Hysteria2 خیلی سریعه و روی اینترنت ضعیف هم پایدار می‌مونه. Amnezia از فیلتر رد می‌شه. WireGuard سبک و سریعه و فقط از راه تانل میاد.",
        "Gaming protocols", "These four protocols are made for games, all on UDP. KANKI MIX is Kanki's own protocol and the best one for gaming, with the lowest lag and packet loss. Hysteria2 is very fast and stays steady even on weak internet. Amnezia gets past filters. WireGuard is light and fast, and comes only through the tunnel.", 5, sheet = "game"),
    GuideStep("game_list", "تانل یا مستقیم", "هر سرور یه ردیفه، با دو خونه: «تانل» از سرور ایران رد می‌شه و وقتی فیلترینگ زیاده بهتر جواب می‌ده؛ «مستقیم» یک‌راست به خود سرور می‌ره و معمولاً پینگ کمتری داره. «سریع‌ترین سرور» هم خودش کم‌پینگ‌ترین رو پیدا و وصل می‌کنه.",
        "Tunnel or direct", "Each server is one row with two cells. Tunnel goes through the Iran server and works better when filtering is heavy. Direct goes straight to the server and usually has a lower ping. Fastest server finds the lowest ping and connects by itself.", 6, sheet = "game"),
    GuideStep("filter_list", "پنل ضد فیلتر", "وقتی فیلترینگ شدیده این حالت رو بزن: با یه لمس خودش بهترین راه عبور رو پیدا می‌کنه. اگه کاشیش رو نگه داری یا دوباره بزنی، همین پنل باز می‌شه و خودت انتخاب می‌کنی: Xray برای فیلترینگ خیلی سخت، Amnezia با تانل یا مستقیم.",
        "Anti-filter panel", "Use this mode when filtering is heavy: one tap finds the best way through by itself. Hold its tile, or tap it again, to open this panel and pick yourself: Xray for the toughest filtering, Amnezia through the tunnel or direct.", 15, sheet = "filter"),
    GuideStep("sub", "اشتراک تو", "گیگ و روزهای باقی‌مونده‌ت اینجاست. هر وقت کم شد، خودم همین‌جا خبرت می‌کنم 😉",
        "Your subscription", "Your remaining data and days are right here. When they run low, I'll let you know right here 😉", 7),
    GuideStep("ping_all", "تب سرورها", "همه‌ی کشورها اینجان. «تست پینگ همه» پینگ واقعی همه‌ی کانفیگ‌ها رو با هم می‌گیره تا ببینی کدوم بهتره.",
        "Servers tab", "Every country is here. Ping all measures the real ping of every config at once, so you can see which one is best.", 8, tab = KTab.SERVERS),
    GuideStep("country", "کانفیگ‌های هر کشور", "روی پرچم هر کشور بزن تا کانفیگ‌هاش باز بشه. هر پروتکل یه ردیفه: تانل و مستقیم کنار هم، با پینگ هر کدوم. با یه لمس وصل می‌شی؛ اگه نگهش داری، جزئیات و MTU رو می‌بینی.",
        "Each country's configs", "Tap a country's flag to open its configs. Each protocol is one row, tunnel and direct side by side, each with its ping. One tap connects; hold it to see the details and MTU.", 9, tab = KTab.SERVERS),
    GuideStep("speed", "تست سرعت", "سرعت واقعی دانلود، آپلود و پینگت رو اینجا بسنج. اول وصل شو تا سرعت از داخل VPN اندازه گرفته بشه.",
        "Speed test", "Measure your real download, upload and ping here. Connect first, so the speed is measured through the VPN.", 10, tab = KTab.TEST),
    GuideStep("settings", "تنظیمات", "DNS، تم و رنگ برنامه، زبان، تونل‌زنی انتخابی برای برنامه‌هایی مثل بانک، و کیل‌سوییچ، همه اینجاست.",
        "Settings", "DNS, the app's theme and colors, the language, split tunneling for apps like your bank, and the kill switch are all here.", 11, tab = KTab.SETTINGS),
    GuideStep("account", "لینک اشتراک", "اطلاعات اشتراک، کپی لینک و تمدید سرویس، همه با یه لمس.",
        "Subscription link", "Your subscription details, copying your link and renewing, all with one tap.", 12),
    GuideStep("emergency", "ارتباط اضطراری 🆘", "این حباب گفتگو فقط برای وقتیه که اینترنت ملی شد. اون موقع از اینجا مستقیم با پشتیبانی حرف می‌زنی و کانفیگی که از فیلتر رد می‌شه رو می‌گیری.",
        "Emergency chat 🆘", "This chat bubble is only for when the internet goes national-only. Then you talk to support right here and get a config that passes the filter.", 13),
    GuideStep("robot", "من همیشه اینجام!", "هر وقت سوال داشتی روی من بزن تا دوباره راهنماییت کنم. اینترنت آزاد مبارکت 🚀",
        "I'm always here!", "Tap me whenever you have a question and I'll guide you again. Enjoy your free internet 🚀", 14),
)

/** وضعیت آموزش و جای بخش‌هایی که باید روشن شوند */
object Guide {
    /** قدم فعلی؛ ‎-1 یعنی آموزش بسته است */
    var step by mutableIntStateOf(-1)
    /** بار اول بعد از ورود: ربات اول می‌پرسد آموزش بدهد یا نه (با صدا یا فقط متن) */
    var asking by mutableStateOf(false)
    val targets = mutableStateMapOf<String, Rect>()
    val active get() = step >= 0
    /** from = 1: بعد از سؤال اول، که سلام کرده، از دکمه‌ی اتصال شروع می‌شود */
    fun start(from: Int = 0) { asking = false; step = from }
    fun next() { step = if (step + 1 >= guideSteps.size) -1 else step + 1 }
    fun back() { if (step > 0) step-- }
    fun stop() { step = -1 }
    val current: GuideStep? get() = guideSteps.getOrNull(step)
}

/** این بخش را برای آموزش علامت می‌زند (جایش روی صفحه ثبت می‌شود) */
fun Modifier.guideTarget(id: String): Modifier = onGloballyPositioned { Guide.targets[id] = it.boundsInRoot() }

/**
 * پرده‌ی آموزش: همه‌جا تیره، بخش مورد نظر روشن با قاب طلایی، و ربات با حباب توضیح.
 * voiceOn / onVoice: دکمه‌ی بلندگوی حباب (پخش صدا بیرون از این فایل است: GuideVoice)؛ onVoice نباشد، دکمه هم نیست.
 * onAnswer: جواب سؤال اول (Guide.asking): true با صدا، false فقط متن، null بعداً.
 */
@Composable
fun GuideOverlay(voiceOn: Boolean = false, onVoice: (() -> Unit)? = null, onAnswer: (Boolean?) -> Unit = {}, onFinish: () -> Unit) {
    // آخرین قدم معتبر نگه داشته می‌شود تا هنگام محو شدن پرده، متن قدم اول چشمک نزند
    var idx by remember { mutableIntStateOf(0) }
    if (Guide.step >= 0) idx = Guide.step
    // سؤال اول یا قدم‌ها: همان که آخر نشان داده شد می‌ماند تا هنگام محو شدن، کارت دیگری چشمک نزند
    var showAsk by remember { mutableStateOf(false) }
    if (Guide.step >= 0) showAsk = false else if (Guide.asking) showAsk = true
    AnimatedVisibility(visible = Guide.step >= 0 || Guide.asking, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
        if (showAsk) {
            WelcomeAsk(onAnswer)
            return@AnimatedVisibility
        }
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
            GuideBubble(
                step, idx, last, bubbleMod.padding(horizontal = 16.dp), voiceOn, onVoice,
                onNext = { next() }, onBack = { Guide.back() }, onSkip = { Guide.stop(); onFinish() },
            )
        }
    }
}

/** سؤال اول ربات بعد از ورود: «برنامه رو بهت یاد بدم؟» با صدا، فقط متن، یا بعداً */
@Composable
private fun WelcomeAsk(onAnswer: (Boolean?) -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xD9000000)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        KCard(Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp).fillMaxWidth(), padding = 18.dp, border = K.Gold.copy(alpha = .7f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RobotFace(64.dp)
                Spacer(Modifier.width(12.dp))
                T("سلام! من کانکی‌ام 👋", size = 18.sp, color = K.Gold, weight = FontWeight.Black, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            T("می‌خوای برنامه رو قدم‌به‌قدم بهت یاد بدم؟ همه‌جا رو نشونت می‌دم: اتصال، حالت‌ها، گیمینگ و ضد فیلتر، سرورها، تست سرعت، تنظیمات، لینک اشتراک و چت اضطراری.",
                size = 14.sp, color = K.Text, weight = FontWeight.Normal)
            Spacer(Modifier.height(16.dp))
            GoldButton("آره، با صدا", Modifier.fillMaxWidth()) { onAnswer(true) }
            Spacer(Modifier.height(8.dp))
            SmallLine("آره، فقط متن", Modifier.fillMaxWidth()) { onAnswer(false) }
            Spacer(Modifier.height(4.dp))
            T("بعداً", size = 13.sp, color = K.Sub,
                modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(10.dp)).clickable { onAnswer(null) }.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun GuideBubble(
    step: GuideStep, idx: Int, last: Boolean, modifier: Modifier, voiceOn: Boolean,
    onVoice: (() -> Unit)?, onNext: () -> Unit, onBack: () -> Unit, onSkip: () -> Unit,
) {
    KCard(modifier.widthIn(max = 420.dp).fillMaxWidth(), padding = 16.dp, border = K.Gold.copy(alpha = .7f)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RobotFace(58.dp)
                // صدای ربات زیر خودش: «صدا» / «بی‌صدا» (یادش می‌ماند)
                if (onVoice != null) {
                    Spacer(Modifier.height(8.dp))
                    VoicePill(voiceOn, onVoice)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                GuideText(if (LangState.en) step.titleEn else step.title, 17.sp, K.Gold, FontWeight.Black)
                Spacer(Modifier.height(4.dp))
                GuideText(if (LangState.en) step.textEn else step.text, 14.sp, K.Text, FontWeight.Normal)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // پیشرفت: «۵/۱۴» و یک خط کوتاه (۱۴ نقطه روی گوشی باریک جای دکمه‌ها را می‌گرفت)
            Column {
                T("${idx + 1}/${guideSteps.size}", size = 11.sp, color = K.Gold, font = Poppins, weight = FontWeight.SemiBold, ltr = true)
                Box(Modifier.padding(top = 3.dp).width(44.dp).height(3.dp).clip(CircleShape).background(K.Sub.copy(alpha = .25f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((idx + 1f) / guideSteps.size).clip(CircleShape).background(K.Gold))
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
    val shape = RoundedCornerShape(D.RM)
    var showDetail by remember(alert.id) { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().goldGlass(shape, tint = c)
            .padding(start = D.S2, end = D.S1, top = D.S2, bottom = D.S2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RobotFace(D.IconBox)
        Spacer(Modifier.width(D.S2))
        Column(Modifier.weight(1f)) {
            T(alert.text, size = 12.sp, color = K.Text, weight = FontWeight.Bold, maxLines = 2)
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
            Modifier.size(D.S6 + D.S1).clip(CircleShape).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { T("✕", size = 12.sp, color = K.Sub, font = Poppins, ltr = true) }
    }
}

/** نشانه‌هایی که در متن ربات به آیکون هم‌رنگ تم کشیده می‌شوند (نه ایموجی رنگی ثابت): تیک، صاعقه، چت اضطراری */
private val guideIcons = listOf("✅", "🚀", "🆘")

/** متن حباب ربات؛ مثل T، فقط ✅ / 🚀 / 🆘 آیکون‌های رنگ تم می‌شوند */
@Composable
private fun GuideText(text: String, size: TextUnit, color: Color, weight: FontWeight) {
    val s = tr(text)
    if (guideIcons.none { s.contains(it) }) {
        T(s, size = size, color = color, weight = weight)
        return
    }
    val ac = K.Gold
    val str = buildAnnotatedString {
        var i = 0
        while (i < s.length) {
            val hit = guideIcons.firstOrNull { s.startsWith(it, i) }
            if (hit != null) { appendInlineContent(hit, hit); i += hit.length } else { append(s[i]); i++ }
        }
    }
    val inline = guideIcons.associateWith { id ->
        InlineTextContent(Placeholder(1.15.em, 1.15.em, PlaceholderVerticalAlign.TextCenter)) {
            Canvas(Modifier.fillMaxSize()) {
                when (id) {
                    "✅" -> checkBadge(ac)
                    "🚀" -> boltBadge(ac)
                    else -> emergencyChatIcon(ac)
                }
            }
        }
    }
    Text(
        str, inlineContent = inline,
        style = TextStyle(
            fontFamily = Vazir, fontWeight = weight, fontSize = size, color = color,
            textDirection = if (s.any { it in '\u0600'..'\u06FF' }) TextDirection.Rtl else TextDirection.Content,
        ),
    )
}

/** تیک داخل دایره‌ی کم‌رنگ، به رنگ تم */
private fun DrawScope.checkBadge(c: Color) {
    val w = size.minDimension
    drawCircle(c.copy(alpha = .18f), w / 2)
    drawCircle(c.copy(alpha = .75f), w / 2 - w * .04f, style = Stroke(w * .08f))
    val p = Path().apply { moveTo(w * .28f, w * .52f); lineTo(w * .44f, w * .67f); lineTo(w * .73f, w * .36f) }
    drawPath(p, c, style = Stroke(w * .12f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

/** صاعقه (سرعت / شروع)، به رنگ تم */
private fun DrawScope.boltBadge(c: Color) {
    val w = size.minDimension
    val p = Path().apply {
        moveTo(w * .60f, w * .04f); lineTo(w * .20f, w * .56f); lineTo(w * .47f, w * .56f)
        lineTo(w * .40f, w * .96f); lineTo(w * .80f, w * .42f); lineTo(w * .53f, w * .42f); close()
    }
    drawPath(p, c)
}

/** دکمه‌ی کوچک صدای ربات: بلندگوی طلایی با موج و «صدا»، یا بلندگوی خاموش با ضربدر و «بی‌صدا» */
@Composable
private fun VoicePill(on: Boolean, onClick: () -> Unit) {
    val c = if (on) K.Gold else K.Sub
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.height(26.dp).clip(shape)
            .background(if (on) K.Gold.copy(alpha = .12f) else Color.Transparent)
            .border(1.dp, c.copy(alpha = if (on) .75f else .4f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(13.dp)) { speakerIcon(c, on) }
        Spacer(Modifier.width(4.dp))
        T(if (on) "صدا" else "بی‌صدا", size = 10.sp, color = c, weight = FontWeight.Bold, maxLines = 1)
    }
}

/** آیکون بلندگو؛ on: با موج صدا، خاموش: با خط ضربدر */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.speakerIcon(c: Color, on: Boolean) {
    val w = size.width
    val body = androidx.compose.ui.graphics.Path().apply {
        moveTo(w * .08f, w * .36f); lineTo(w * .28f, w * .36f); lineTo(w * .52f, w * .14f)
        lineTo(w * .52f, w * .86f); lineTo(w * .28f, w * .64f); lineTo(w * .08f, w * .64f); close()
    }
    drawPath(body, c)
    val st = Stroke(w * .09f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    if (on) {
        drawArc(c, -45f, 90f, false, Offset(w * .44f, w * .3f), Size(w * .28f, w * .4f), style = st)
        drawArc(c, -50f, 100f, false, Offset(w * .44f, w * .14f), Size(w * .52f, w * .72f), style = st)
    } else {
        drawLine(c, Offset(w * .66f, w * .34f), Offset(w * .94f, w * .66f), w * .09f, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(c, Offset(w * .94f, w * .34f), Offset(w * .66f, w * .66f), w * .09f, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}
