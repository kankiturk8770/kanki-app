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
 * یک قدم آموزش: کدام بخش روشن شود، ربات چه بگوید (فارسی و انگلیسی) و کجا باشد (تب، پنل گیمینگ / ضد فیلتر).
 * points: چند خط کوتاه «اسم: توضیح» زیر متن (مثل حالت‌ها یا پروتکل‌ها)، هر کدام جدا تا متن یکجا شلوغ نشود.
 */
class GuideStep(
    val target: String?,
    val title: String,
    val text: String,
    val titleEn: String,
    val textEn: String,
    val points: List<Pair<String, String>> = emptyList(),
    val pointsEn: List<Pair<String, String>> = emptyList(),
    val tab: KTab = KTab.HOME,
    val sheet: String? = null,
)

val guideSteps = listOf(
    GuideStep(null, "سلام، من کانکی‌ام", "یه دور کوتاه توی برنامه می‌زنیم تا بدونی هر چیزی کجاست. هر جای صفحه رو بزنی، می‌ریم قدم بعد.",
        "Hi, I'm Kanki", "Let's take a short tour so you know where everything is. Tap anywhere to go to the next step."),
    GuideStep("connect", "دکمه‌ی اتصال", "یه بار بزن تا با حالتی که پایین انتخاب کردی وصل شی. وقتی وصلی وسطش «متصل» و زمان اتصال نوشته می‌شه. وسط اتصال دوباره بزنی، لغو می‌شه.",
        "Connect button", "Tap once to connect with the mode you picked below. When you're connected it says Connected with the time. Tap again while connecting to cancel."),
    GuideStep("map", "نقشه", "می‌بینی کجایی و به کدوم کشور وصلی. پینگ، دانلود و آپلود زنده زیر نقشه‌ست.",
        "Map", "Shows where you are and which country you're connected to. Live ping, download and upload are below the map."),
    GuideStep("protocols", "حالت‌های اتصال", "هر حالت برای یه کاره:",
        "Connection modes", "Each mode has one job:",
        points = listOf(
            "هوشمند" to "پینگ همه رو می‌گیره و به کم‌پینگ‌ترین وصل می‌شه؛ اگه شبکه بد باشه خودش می‌ره سراغ تانل.",
            "گیمینگ" to "برای بازی؛ پروتکل و سرور رو خودت انتخاب می‌کنی.",
            "ضد فیلتر" to "وقتی فیلترینگ شدیده؛ با یه لمس بهترین راه رو پیدا می‌کنه.",
            "DNS" to "VPN نیست؛ فقط سایت‌ها و بازی‌های تحریمی رو باز می‌کنه و بقیه مستقیم می‌رن.",
        ),
        pointsEn = listOf(
            "Smart" to "Pings everything and connects to the lowest ping; on a bad network it moves to a tunnel by itself.",
            "Gaming" to "For games; you pick the protocol and the server.",
            "Anti-filter" to "For heavy filtering; one tap finds the best way through.",
            "DNS" to "Not a VPN; it only unblocks sanctioned sites and games, everything else goes direct.",
        )),
    GuideStep("game_fams", "پروتکل‌های گیمینگ", "همه روی UDP‌ان، مثل خود بازی. فقط اونایی که توی اشتراکت هست دیده می‌شن:",
        "Gaming protocols", "All run on UDP, like the game itself. Only the ones in your subscription are shown:",
        points = listOf(
            "KANKI MIX" to "پروتکل خود کانکی؛ کمترین لگ و پکت‌لاس. با «ارسال دوتایی» هر بسته‌ی بازی دو بار می‌ره و پکت‌لاس اثر نمی‌کنه.",
            "microtun" to "سبک و کم‌حجم؛ بسته‌های کوچیک بازی دو بار می‌رن و دانلود یه بار.",
            "Hysteria2" to "پرسرعت و روی اینترنت ضعیف هم پایدار.",
            "Amnezia / WireGuard" to "Amnezia از فیلتر رد می‌شه؛ WireGuard سبک و سریعه.",
        ),
        pointsEn = listOf(
            "KANKI MIX" to "Kanki's own protocol, the lowest lag and packet loss. With Duplicate packets every game packet goes twice, so packet loss doesn't hurt.",
            "microtun" to "Light and small; small game packets go twice, downloads once.",
            "Hysteria2" to "Fast, and steady even on weak internet.",
            "Amnezia / WireGuard" to "Amnezia gets past filters; WireGuard is light and fast.",
        ), sheet = "game"),
    GuideStep("game_only", "فقط بازی اینترنت داشته باشد", "با KANKI MIX و microtun روشنش کن: تا وقتی وصلی فقط بازی‌هات اینترنت دارن و بقیه‌ی برنامه‌ها پهنای باند رو نمی‌گیرن، پس پینگ پایدارتر می‌مونه. سرویس‌های گوگل (برای ورود به بازی) باز می‌مونن. با «انتخاب بازی‌ها» فهرست رو عوض کن. اندروید ۱۰ به بالا.",
        "Only the game gets internet", "Turn it on with KANKI MIX or microtun: while you're connected only your games get internet and other apps can't take the bandwidth, so your ping stays steadier. Google services stay open for game login. Change the list with Choose games. Android 10 and later.", sheet = "game"),
    GuideStep("game_list", "تانل یا مستقیم", "هر سرور یه ردیفه با دو خونه: «تانل» از سرور ایران رد می‌شه و وقتی فیلترینگ زیاده بهتره؛ «مستقیم» معمولاً پینگ کمتری داره. «سریع‌ترین سرور» خودش کم‌پینگ‌ترین رو وصل می‌کنه.",
        "Tunnel or direct", "Each server is one row with two cells: Tunnel goes through the Iran server and is better when filtering is heavy; Direct usually has a lower ping. Fastest server connects the lowest ping by itself.", sheet = "game"),
    GuideStep(null, "اگه پینگ بالا رفت", "گاهی توی ایران UDP محدود می‌شه و پینگ یه کم بالا می‌ره. اون موقع تانل یا یه پروتکل دیگه رو امتحان کن.",
        "If your ping goes up", "Sometimes UDP is restricted in Iran and your ping rises a little. Then try the tunnel or another protocol.", sheet = "game"),
    GuideStep("filter_list", "پنل ضد فیلتر", "یه لمس روی کاشی ضد فیلتر، خودش بهترین راه رو پیدا می‌کنه. دوباره بزنی یا نگهش داری، این پنل باز می‌شه: Xray برای فیلترینگ خیلی سخت، Amnezia از تانل یا مستقیم.",
        "Anti-filter panel", "One tap on the Anti-filter tile finds the best way by itself. Tap it again or hold it to open this panel: Xray for the toughest filtering, Amnezia through the tunnel or direct.", sheet = "filter"),
    GuideStep("sub", "اشتراک تو", "حجم و روزهای باقی‌مونده اینجاست. وقتی کم بشه همین‌جا خبرت می‌کنم.",
        "Your subscription", "Your remaining data and days are here. When they run low, I'll tell you right here."),
    GuideStep("ping_all", "تب سرورها", "همه‌ی کشورها اینجان. «تست پینگ همه» پینگ واقعی همه‌ی کانفیگ‌ها رو با هم می‌گیره.",
        "Servers tab", "Every country is here. Ping all measures the real ping of every config at once.", tab = KTab.SERVERS),
    GuideStep("country", "کانفیگ‌های هر کشور", "روی کشور بزن تا باز بشه: تانل و مستقیم کنار هم، با میله‌های سیگنال و پینگ. یه لمس وصل می‌کنه؛ نگه داری، جزئیات و MTU رو می‌بینی.",
        "Each country's configs", "Tap a country to open it: tunnel and direct side by side, with signal bars and ping. One tap connects; hold for details and MTU.", tab = KTab.SERVERS),
    GuideStep("speed", "تست سرعت", "اول پینگ، جیتر و پکت‌لاس روی UDP (همونی که بازی حس می‌کنه)، بعد دانلود و آپلود. وصل باشی، از داخل VPN سنجیده می‌شه.",
        "Speed test", "First ping, jitter and packet loss over UDP (what your game feels), then download and upload. If you're connected, it's measured through the VPN.", tab = KTab.TEST),
    GuideStep("settings", "تنظیمات", "DNS، اتصال خودکار، کیل‌سوییچ، تونل‌زنی انتخابی (مثلاً برای بانک)، تم، زبان و صداهای اپ.",
        "Settings", "DNS, auto connect, kill switch, split tunneling (for your bank, say), theme, language and app sounds.", tab = KTab.SETTINGS),
    GuideStep("account", "لینک اشتراک", "اطلاعات اشتراک، کپی لینک و تمدید.",
        "Subscription link", "Your subscription details, copying your link and renewing."),
    GuideStep("emergency", "چت اضطراری", "فقط برای وقتی که اینترنت ملی شد: از اینجا مستقیم با پشتیبانی حرف می‌زنی و کانفیگی که رد می‌شه رو می‌گیری.",
        "Emergency chat", "Only for when the internet goes national-only: talk to support right here and get a config that gets through."),
    GuideStep("robot", "من همین‌جام", "هر وقت سؤال داشتی روی من بزن تا این راهنما دوباره باز بشه.",
        "I'm right here", "Tap me whenever you have a question and this guide opens again."),
)

/** وضعیت آموزش و جای بخش‌هایی که باید روشن شوند */
object Guide {
    /** قدم فعلی؛ ‎-1 یعنی آموزش بسته است */
    var step by mutableIntStateOf(-1)
    /** بار اول بعد از ورود: ربات اول می‌پرسد آموزش بدهد یا نه */
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
 * پرده‌ی آموزش: همه‌جا تیره، بخش مورد نظر روشن با قاب طلایی، و حباب ربات کنارش.
 * onAnswer: جواب سؤال اول (Guide.asking): true «نشونم بده»، false «بعداً».
 */
@Composable
fun GuideOverlay(onAnswer: (Boolean) -> Unit = {}, onFinish: () -> Unit) {
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
                step, idx, last, bubbleMod.padding(horizontal = D.PagePad),
                onNext = { next() }, onBack = { Guide.back() }, onSkip = { Guide.stop(); onFinish() },
            )
        }
    }
}

/** سؤال اول ربات بعد از ورود: «برنامه رو نشونت بدم؟» */
@Composable
private fun WelcomeAsk(onAnswer: (Boolean) -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xD9000000)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        KCard(Modifier.padding(horizontal = D.PagePad).widthIn(max = 420.dp).fillMaxWidth(), padding = D.S5, border = K.Gold.copy(alpha = .7f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RobotFace(52.dp)
                Spacer(Modifier.width(D.S3))
                T("سلام، من کانکی‌ام", size = 20.sp, color = K.Gold, weight = FontWeight.Black, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(D.S3))
            GuideBody("برنامه رو قدم‌به‌قدم نشونت بدم؟ اتصال، حالت‌ها، گیمینگ، سرورها، تست سرعت و تنظیمات؛ کمتر از یه دقیقه.")
            Spacer(Modifier.height(D.S5))
            GoldButton("آره، نشونم بده", Modifier.fillMaxWidth()) { onAnswer(true) }
            Spacer(Modifier.height(D.S1))
            T("بعداً", size = 13.sp, color = K.Sub,
                modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(D.RS)).clickable { onAnswer(false) }
                    .padding(horizontal = D.S4, vertical = D.S3))
        }
    }
}

/**
 * حباب هر قدم: بالا نوار پیشرفت (یک تکه برای هر قدم)، بعد ربات کوچک و عنوان با «۵ از ۱۷»؛ متن تمام‌عرض زیرش
 * (نه فشرده کنار ربات) و نکته‌ها هر کدام یک خط جدا با نوار طلایی کنارش؛ پایین «رد کردن»، «قبلی» و «بعدی».
 */
@Composable
private fun GuideBubble(
    step: GuideStep, idx: Int, last: Boolean, modifier: Modifier,
    onNext: () -> Unit, onBack: () -> Unit, onSkip: () -> Unit,
) {
    val en = LangState.en
    KCard(modifier.widthIn(max = 440.dp).fillMaxWidth(), padding = D.S4, border = K.Gold.copy(alpha = .7f)) {
        Row(Modifier.fillMaxWidth().height(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            guideSteps.indices.forEach { i ->
                Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(if (i <= idx) K.Gold else K.Sub.copy(alpha = .22f)))
            }
        }
        Spacer(Modifier.height(D.S3))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RobotFace(36.dp, animate = false)
            Spacer(Modifier.width(D.S2))
            T(if (en) step.titleEn else step.title, size = 16.sp, color = K.Gold, weight = FontWeight.Black, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(D.S2))
            T(trf("%d از %d", idx + 1, guideSteps.size), size = 11.sp, color = K.Sub, weight = FontWeight.Normal, maxLines = 1)
        }
        Spacer(Modifier.height(D.S2))
        GuideBody(if (en) step.textEn else step.text)
        val points = if (en) step.pointsEn else step.points
        if (points.isNotEmpty()) {
            Spacer(Modifier.height(D.S2))
            points.forEach { (name, what) -> GuidePoint(name, what) }
        }
        Spacer(Modifier.height(D.S3))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!last) T("رد کردن", size = 12.sp, color = K.Sub, modifier = Modifier.clip(RoundedCornerShape(D.RS)).clickable(onClick = onSkip).padding(horizontal = D.S2, vertical = D.S2))
            Spacer(Modifier.weight(1f))
            if (idx > 0) { SmallLine("قبلی", onClick = onBack); Spacer(Modifier.width(D.S2)) }
            SmallGold(if (last) "تمام" else "بعدی", onClick = onNext)
        }
    }
}

/** متن بدنه‌ی ربات: فاصله‌ی خط بیشتر برای وزیرمتن، تا جمله‌های بلند فارسی خوانا بمانند */
@Composable
private fun GuideBody(text: String) {
    val s = tr(text)
    Text(
        s,
        style = TextStyle(
            fontFamily = Vazir, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 23.sp, color = K.Text,
            textDirection = if (s.any { it in '؀'..'ۿ' }) TextDirection.Rtl else TextDirection.Content,
        ),
    )
}

/** یک نکته: نوار طلایی باریک کنار خط، اسم پررنگ به رنگ تم و توضیحش پشت سرش */
@Composable
private fun GuidePoint(name: String, what: String) {
    val n = tr(name)
    val w = tr(what)
    Row(Modifier.fillMaxWidth().padding(vertical = D.S1).height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().clip(CircleShape).background(K.Gold.copy(alpha = .7f)))
        Spacer(Modifier.width(D.S2))
        Text(
            buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(color = K.Gold, fontWeight = FontWeight.Bold))
                append(n)
                pop()
                append(if (LangState.en) ": " else "‏: ")
                append(w)
            },
            style = TextStyle(
                fontFamily = Vazir, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 21.sp, color = K.Text,
                textDirection = if (LangState.en) TextDirection.Ltr else TextDirection.Rtl,
            ),
        )
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
