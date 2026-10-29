package cafe.kankipro.vpn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

// ================================================================== لوگوی اضطراری

/**
 * نشان ارتباط اضطراری: حباب گفتگوی طلایی که داخلش یک نقطه با دو موج (پیام از راه دور) است، و یک نقطه‌ی قرمز
 * کوچک بالای آن (حالت اضطراری). به رنگ تم، نه حلقه‌ی نجات.
 */
@Composable
fun EmergencyIcon(size: Dp = 28.dp) {
    val gold = K.Gold
    val red = K.Red
    Canvas(Modifier.size(size)) {
        val w = this.size.minDimension
        val sw = w * .075f
        val l = w * .08f; val t = w * .14f; val r = w * .86f; val b = w * .72f
        val cr = w * .16f
        val bubble = Path().apply {
            moveTo(l + cr, t); lineTo(r - cr, t); quadraticBezierTo(r, t, r, t + cr)
            lineTo(r, b - cr); quadraticBezierTo(r, b, r - cr, b)
            lineTo(w * .42f, b); lineTo(w * .24f, w * .92f); lineTo(w * .28f, b)
            lineTo(l + cr, b); quadraticBezierTo(l, b, l, b - cr)
            lineTo(l, t + cr); quadraticBezierTo(l, t, l + cr, t); close()
        }
        drawPath(bubble, gold.copy(alpha = .14f))
        drawPath(bubble, gold, style = Stroke(sw, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        // نقطه و دو موج وسط حباب
        val c = Offset(w * .47f, (t + b) / 2 + w * .05f)
        drawCircle(gold, w * .055f, c)
        for (k in 1..2) {
            val rr = w * (.10f + .09f * k)
            drawArc(gold.copy(alpha = 1f - .3f * k), -135f, 90f, false, Offset(c.x - rr, c.y - rr), androidx.compose.ui.geometry.Size(rr * 2, rr * 2), style = Stroke(sw * .85f, cap = StrokeCap.Round))
        }
        // نقطه‌ی قرمز حالت اضطراری
        drawCircle(Color.Black, w * .15f, Offset(r, t))
        drawCircle(red, w * .11f, Offset(r, t))
    }
}

// ================================================================== صفحه‌ی چت اضطراری

@Composable
fun EmergencyScreen(state: AppState, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    BackHandler(onBack = onClose)

    var code by remember { mutableStateOf(state.code ?: Emergency.savedCode(ctx)) }
    var codeText by remember { mutableStateOf("") }
    val msgs = remember { mutableStateListOf<EMsg>() }
    var user by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var stickersOpen by remember { mutableStateOf(false) }
    val loadLock = remember { kotlinx.coroutines.sync.Mutex() }
    /** بعد از اولین خواندن از سرور: پیام تازه‌ی پشتیبانی صدا می‌دهد (مثل پیام‌رسان‌ها)، نه تاریخچه‌ی قدیمی */
    var primed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Sfx.preload(ctx, R.raw.chat_in); Sfx.preload(ctx, R.raw.chat_out) }
    var helpOpen by remember { mutableStateOf(true) }
    val images = remember { mutableStateMapOf<Long, ImageBitmap>() }
    val imageOk = remember { mutableStateMapOf<Long, Boolean>() }
    val list = rememberLazyListState()
    /** پیام‌های صف ارسال (هنوز به سرور نرسیده‌اند؛ 🕓) */
    val pending = remember { mutableStateListOf<EmergencyStore.Out>() }
    fun refreshPending() { pending.clear(); code?.let { pending.addAll(EmergencyStore.outbox(ctx, it)) } }
    /** کد اشتراک در سرور اضطراری پیدا نشد (۴۰۳): دکمه‌ی «عوض کردن کد» نشان داده می‌شود */
    var badCode by remember { mutableStateOf(false) }
    /** عکس‌هایی که چند بار گرفته نشدند (مثلاً از سرور پاک شده‌اند): دیگر هر ۵ ثانیه دوباره خواسته نمی‌شوند */
    val imgFails = remember { mutableStateMapOf<Long, Int>() }
    /** پیام‌های خود کاربر تا این شماره را ادمین خوانده (✓✓ آبی) */
    var readUpto by remember { mutableStateOf(0L) }
    /** آخرین پیام ادمین که «دیده شد» برایش فرستاده شده */
    var readSent by remember { mutableStateOf(0L) }
    // چت باز است: اعلان پیام تازه لازم نیست، و اعلان قبلی برداشته می‌شود
    // فقط وقتی صفحه واقعاً جلوی چشم است (نه وقتی برنامه به پس‌زمینه رفته): وگرنه پیام ادمین بی‌آنکه کاربر
    // ببیند «خوانده شد» می‌خورد و اعلانش هم نمی‌آمد
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    var visible by remember { mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> { visible = true; EmergencyStore.chatOpen = true; EmergencyStore.clearNotification(ctx) }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { visible = false; EmergencyStore.chatOpen = false }
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        if (visible) { EmergencyStore.chatOpen = true; EmergencyStore.clearNotification(ctx) }
        onDispose { lifecycle.removeObserver(obs); EmergencyStore.chatOpen = false }
    }

    fun errText(e: Throwable): String = when {
        e is EmergencyException && e.code == Emergency.NO_ADDRESS -> "آدرس سرور اضطراری هنوز از پنل نیامده. یک بار وقتی اینترنت وصل است برنامه را باز کنید تا اشتراک بروز شود."
        e is EmergencyException && e.code == 403 -> "این اشتراک هنوز در سرور اضطراری ثبت نشده. کد را درست وارد کنید؛ اگر تازه خریده‌اید، باید یک بار وقتی اینترنت وصل بود برنامه را باز کرده باشید."
        e is EmergencyException && e.code == 429 -> "تعداد درخواست زیاد بود. چند دقیقه صبر کنید."
        e is EmergencyException -> e.message ?: "خطا"
        else -> "به سرور اضطراری وصل نشد. اگر VPN روشن است خاموشش کنید و دوباره امتحان کنید."
    }

    suspend fun load() {
        val c = code ?: return
        refreshPending()
        // یک خواندن در هر لحظه (حلقه‌ی ۵ ثانیه‌ای و دکمه‌ی ارسال با هم صدا می‌زنند)
        if (!loadLock.tryLock()) return
        try {
            // اول صف ارسال (پیام‌هایی که بی‌اینترنت نوشته شده‌اند)
            if (pending.isNotEmpty()) { withContext(Dispatchers.IO) { EmergencyStore.flush(ctx, c) }; refreshPending() }
            val after = msgs.lastOrNull()?.id ?: 0L
            val page = withContext(Dispatchers.IO) { Emergency.page(ctx, c, after) }
            val (u, fresh) = page.user to page.msgs
            user = u
            status = null
            badCode = false
            readUpto = maxOf(readUpto, page.readUpto)
            withContext(Dispatchers.IO) { EmergencyStore.save(ctx, c, u, fresh, page.readUpto) }
            if (fresh.isNotEmpty()) {
                val added = fresh.filter { f -> msgs.none { it.id == f.id } }.distinctBy { it.id }
                msgs.addAll(added)
                helpOpen = false
                if (primed && added.any { it.admin } && Sfx.soundOn(ctx)) Sfx.play(ctx, R.raw.chat_in, .8f)
            }
            primed = true
            // پیام‌های ادمین روی صفحه است: «خوانده شد» (ادمین در پنل تیک آبی می‌بیند)
            val lastAdmin = msgs.filter { it.admin }.maxOfOrNull { it.id } ?: 0L
            if (lastAdmin > readSent) {
                runCatching { withContext(Dispatchers.IO) { Emergency.markRead(ctx, c, lastAdmin) } }.onSuccess { readSent = lastAdmin }
                EmergencyStore.setNotified(ctx, lastAdmin)
            }
            // عکس‌ها: همه‌ی پیام‌های عکس‌دار، نه فقط تازه‌ها (از حافظه‌ی گوشی، وگرنه از سرور)
            msgs.filter { it.img && it.id !in images && (imgFails[it.id] ?: 0) < 3 }.forEach { m ->
                runCatching {
                    val who = user.ifBlank { u }
                    // خواندن، ذخیره و باز کردن عکس بیرون از رشته‌ی صفحه، و کوچک‌شده (عکس کامل حافظه را پر می‌کرد)
                    val (bmp, ok) = withContext(Dispatchers.IO) {
                        val bytes = EmergencyStore.savedImage(ctx, m.id) ?: Emergency.image(ctx, c, m.id).also { EmergencyStore.saveImage(ctx, m.id, it) }
                        decodeSmall(bytes) to (if (m.admin) Emergency.verifyImage(ctx, m, who, bytes) else true)
                    }
                    bmp?.let { images[m.id] = it.asImageBitmap() }
                    if (m.admin) imageOk[m.id] = ok
                }.onFailure { imgFails[m.id] = (imgFails[m.id] ?: 0) + 1 }
            }
        } catch (e: Exception) {
            status = errText(e)
            if (e is EmergencyException && e.code == 403) badCode = true
        } finally {
            loadLock.unlock()
        }
    }

    LaunchedEffect(code) {
        msgs.clear()
        // اول پیام‌های ذخیره‌شده روی گوشی (۲ روز اخیر)، تا حتی بدون اینترنت دیده شوند
        code?.let { c ->
            val saved = withContext(Dispatchers.IO) { EmergencyStore.load(ctx, c) }
            msgs.addAll(saved.msgs.filter { s -> msgs.none { it.id == s.id } })
            readUpto = saved.readUpto
            if (saved.user.isNotBlank()) user = saved.user
            if (saved.msgs.isNotEmpty()) helpOpen = false
            refreshPending()
            // عکس‌های ذخیره‌شده، بدون اینترنت
            saved.msgs.filter { it.img }.forEach { m ->
                val r = withContext(Dispatchers.IO) {
                    EmergencyStore.savedImage(ctx, m.id)?.let { b -> decodeSmall(b) to (if (m.admin) Emergency.verifyImage(ctx, m, saved.user, b) else true) }
                }
                r?.first?.let { images[m.id] = it.asImageBitmap() }
                if (m.admin && r != null) imageOk[m.id] = r.second
            }
        }
        // هر ۵ ثانیه، فقط وقتی صفحه جلوی چشم است (در پس‌زمینه کار پس‌زمینه‌ی ۱۵ دقیقه‌ای و اعلان)
        while (code != null) {
            if (visible) load()
            delay(5000)
        }
    }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val c = code
        if (uri != null && c != null) {
            sending = true
            scope.launch {
                try {
                    val jpeg = withContext(Dispatchers.Default) { shrink(ctx, uri) }
                    if (jpeg == null) status = "این عکس خوانده نشد."
                    else {
                        // اول در صف، بعد فرستادن: بی‌اینترنت هم گم نمی‌شود
                        withContext(Dispatchers.IO) { EmergencyStore.queueImage(ctx, c, jpeg) }
                        refreshPending()
                        load()
                    }
                } catch (e: Exception) {
                    status = errText(e)
                }
                sending = false
            }
        }
    }

    // مثل تلگرام: با باز شدن کیبورد، کادر نوشتن بالای کیبورد می‌ماند و متن دیده می‌شود.
    // حاشیه‌ی کناری همه‌جا D.PagePad؛ سربرگ، راهنما، پیام‌ها و کادر نوشتن روی یک خط عمودی‌اند.
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = D.PagePad)) {
        // سربرگ: برگشت، نشان اضطراری، عنوان و یک خط؛ «راهنما» دکمه‌ی کوچک کناری
        Row(Modifier.fillMaxWidth().padding(top = D.S2, bottom = D.S3), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(D.IconBox + D.S1, onClick = onClose) { Canvas(Modifier.size(18.dp)) { backArrow(K.Gold) } }
            Spacer(Modifier.width(D.S3))
            EmergencyIcon(28.dp)
            Spacer(Modifier.width(D.S2))
            Column(Modifier.weight(1f)) {
                T("ارتباط اضطراری", size = 17.sp, weight = FontWeight.Black, maxLines = 1)
                T(if (user.isNotEmpty()) trf("حساب: %s", user) else "گفتگو با پشتیبانی کانکی", size = 11.sp, color = K.Sub, weight = FontWeight.Normal, maxLines = 1)
            }
            val hs = RoundedCornerShape(50)
            T(
                if (helpOpen) "بستن" else "راهنما", size = 12.sp, color = K.Gold, maxLines = 1,
                modifier = Modifier.clip(hs).border(1.dp, K.Gold.copy(alpha = .4f), hs).clickable { helpOpen = !helpOpen }
                    .padding(horizontal = D.S3, vertical = D.S1 + 2.dp),
            )
        }

        // راهنما: کارت آرام با نوار قرمز باریک؛ سه قدم پشت سر هم (واقعاً ترتیب دارند)
        if (helpOpen) {
            val hs = RoundedCornerShape(D.RM)
            Row(
                Modifier.fillMaxWidth().clip(hs).background(K.Surface).border(1.dp, K.Gold.copy(alpha = .14f), hs)
                    .height(IntrinsicSize.Min),
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(K.Red.copy(alpha = .85f)))
                Column(Modifier.weight(1f).padding(D.CardPad)) {
                    T("فقط وقتی اینترنت بین‌الملل کامل قطع شد", size = 14.sp, color = K.Text, weight = FontWeight.Black)
                    Spacer(Modifier.height(D.S1))
                    T("وقتی اینترنت بین‌الملل قطع است، اینجا با پشتیبانی کانکی حرف می‌زنی و کانفیگی که وصل می‌شه رو می‌گیری.", size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
                    Spacer(Modifier.height(D.S2))
                    listOf(
                        "مشکل رو بنویس؛ اسکرین‌شات هم می‌شه فرستاد.",
                        "جواب همین‌جا میاد؛ کانفیگ رو با «کپی کانفیگ» بردار.",
                        "فقط به پیامی اعتماد کن که «✓ از طرف ادمین» داره.",
                    ).forEachIndexed { n, line ->
                        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                            T(faDigits("${n + 1}"), size = 12.sp, color = K.Gold, weight = FontWeight.Black, modifier = Modifier.width(D.S4))
                            T(line, size = 12.sp, color = K.Text, weight = FontWeight.Normal)
                        }
                    }
                }
            }
            Spacer(Modifier.height(D.S2))
        }

        if (code == null) {
            // کاربر هنوز وارد نشده: کد اشتراکش را می‌خواهیم
            KCard(Modifier.fillMaxWidth(), padding = D.CardPad) {
                T("کد اشتراک خود را وارد کنید", size = 14.sp, weight = FontWeight.Black)
                T("فقط مشتری‌های کانکی می‌توانند پیام بدهند.", size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
                Spacer(Modifier.height(D.S3))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = codeText, onValueChange = { codeText = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontFamily = Poppins, fontSize = 14.sp, color = K.Text),
                        placeholder = { Text("- - - - - - - - - -", color = K.Sub.copy(alpha = .5f)) },
                        shape = RoundedCornerShape(D.RS),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = K.Gold, unfocusedBorderColor = K.Line, cursorColor = K.Gold),
                    )
                }
                Spacer(Modifier.height(D.S3))
                GoldButton("ادامه", Modifier.fillMaxWidth()) {
                    val c = SubscriptionApi.normalize(codeText)?.second
                    if (c == null) status = "کد اشتراک درست نیست."
                    else { Emergency.saveCode(ctx, c); code = c }
                }
            }
        }

        // خطا: یک خط کوچک با نقطه‌ی قرمز، نه یک بند متن قرمز
        status?.let {
            Spacer(Modifier.height(D.S1))
            val es = RoundedCornerShape(D.RS)
            Row(
                Modifier.fillMaxWidth().clip(es).background(K.Red.copy(alpha = .08f)).border(1.dp, K.Red.copy(alpha = .25f), es)
                    .padding(horizontal = D.S3, vertical = D.S2),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.padding(top = 6.dp).size(6.dp).clip(CircleShape).background(K.Red))
                Spacer(Modifier.width(D.S2))
                T(it, size = 12.sp, color = K.Text, weight = FontWeight.Normal)
            }
            // کد اشتباه بود: بشود دوباره نوشت (فقط وقتی کد از خود حساب نیامده)
            if (badCode && state.code == null) {
                Spacer(Modifier.height(D.S2))
                LineButton("عوض کردن کد", Modifier.fillMaxWidth()) {
                    Emergency.saveCode(ctx, ""); code = null; badCode = false; status = null; msgs.clear(); pending.clear()
                }
            }
        }

        // پیام‌ها
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(D.S2), contentPadding = PaddingValues(vertical = D.S3)) {
            if (code != null && msgs.isEmpty() && pending.isEmpty() && status == null) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = D.S6 * 2), horizontalAlignment = Alignment.CenterHorizontally) {
                        EmergencyIcon(40.dp)
                        Spacer(Modifier.height(D.S3))
                        T("هنوز پیامی نیست", size = 14.sp, color = K.Text, weight = FontWeight.Black)
                        T("مشکل رو بنویس تا پشتیبانی جواب بده.", size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
                    }
                }
            }
            items(msgs, key = { it.id }) { m ->
                // عکس ادمین تا دانلود نشده «در حال بررسی» است، نه «امضا تأیید نشد»
                Bubble(m, images[m.id], if (m.img) imageOk[m.id] else m.verified, read = !m.admin && m.id <= readUpto) { clip.setText(AnnotatedString(it)); toast(ctx, "کانفیگ کپی شد") }
            }
            items(pending, key = { "out" + it.lid }) { o ->
                Bubble(EMsg(-o.lid, false, if (o.kind == "image") "image" else "text", if (o.kind == "image") "🖼 عکس" else o.text, o.ts, "", false, false), null, true, waiting = true) {}
            }
        }

        // نوشتن
        if (code != null) {
            val bar = RoundedCornerShape(D.RL)
            // استیکرهای کانکی: یک ردیف کوچک بالای کادر نوشتن
            if (stickersOpen) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(bottom = D.S2).clip(bar).background(K.Surface).border(1.dp, K.Gold.copy(alpha = .14f), bar),
                    horizontalArrangement = Arrangement.spacedBy(D.S2), contentPadding = PaddingValues(D.S2),
                ) {
                    items(Stickers.all, key = { it.key }) { s ->
                        val bmp = remember(s.key) { Stickers.bitmap(ctx, s, 160).asImageBitmap() }
                        Image(bmp, s.text, Modifier.size(56.dp).clip(RoundedCornerShape(D.RS)).clickable {
                            stickersOpen = false
                            val c = code
                            if (c != null) scope.launch {
                                withContext(Dispatchers.IO) { EmergencyStore.queueImage(ctx, c, Stickers.jpeg(ctx, s)) }
                                refreshPending()
                                load()
                            }
                        })
                    }
                }
            }
            // یک نوار: استیکر و عکس، متن، دکمه‌ی ارسال طلایی
            Row(
                Modifier.fillMaxWidth().padding(bottom = D.S3).clip(bar).background(K.Surface)
                    .border(1.dp, K.Gold.copy(alpha = .22f), bar).padding(horizontal = D.S1, vertical = D.S1),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { stickersOpen = !stickersOpen }, contentAlignment = Alignment.Center) {
                    T("😊", size = 18.sp, weight = FontWeight.Normal)
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable { if (!sending) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center,
                ) { Canvas(Modifier.size(20.dp)) { pictureIcon(K.Gold) } }
                Box(Modifier.weight(1f).heightIn(min = 40.dp).padding(horizontal = D.S2), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) T("پیام به پشتیبانی…", size = 14.sp, color = K.Sub.copy(alpha = .6f), weight = FontWeight.Normal)
                    androidx.compose.foundation.text.BasicTextField(
                        value = text, onValueChange = { if (it.length <= 2000) text = it }, maxLines = 4,
                        textStyle = TextStyle(fontFamily = Vazir, fontSize = 14.sp, lineHeight = 21.sp, color = K.Text,
                            textDirection = androidx.compose.ui.text.style.TextDirection.Content),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(K.Gold),
                        modifier = Modifier.fillMaxWidth().padding(vertical = D.S2),
                    )
                }
                val canSend = text.isNotBlank() && !sending
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(if (canSend) K.Gold else K.Gold.copy(alpha = .18f)).clickable {
                        val c = code
                        val t = text.trim()
                        if (t.isNotEmpty() && c != null && !sending) {
                            // مثل تلگرام: پیام فوراً در صف و روی صفحه (🕓)، و هر وقت سرور در دسترس بود فرستاده می‌شود
                            EmergencyStore.queueText(ctx, c, t)
                            if (Sfx.soundOn(ctx)) Sfx.play(ctx, R.raw.chat_out, .6f)
                            text = ""
                            refreshPending()
                            scope.launch { load() }
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) { Canvas(Modifier.size(18.dp)) { sendArrow(if (canSend) K.OnGold else K.Sub) } }
            }
        }
    }
}

/**
 * یک پیام. مال کاربر: طلایی کم‌رنگ، طرف شروع؛ پشتیبانی: کارت تیره با لبه‌ی سبز (امضا درست) یا قرمز، طرف دیگر.
 * گوشه‌ی سمت فرستنده تیزتر است تا معلوم باشد پیام از کدام طرف آمده. استیکر (عکس مربعی کوچک) کوچک نشان داده می‌شود.
 */
@Composable
private fun Bubble(m: EMsg, img: ImageBitmap?, okOrNull: Boolean?, read: Boolean = false, waiting: Boolean = false, onCopy: (String) -> Unit) {
    val ok = okOrNull ?: true
    val checking = m.admin && okOrNull == null
    val mine = !m.admin
    val r = D.RM
    val shape = if (mine) RoundedCornerShape(topStart = r, topEnd = r, bottomStart = 4.dp, bottomEnd = r)
        else RoundedCornerShape(topStart = r, topEnd = r, bottomStart = r, bottomEnd = 4.dp)
    val edge = when { mine -> K.Gold.copy(alpha = .25f); checking -> K.Sub.copy(alpha = .25f); ok -> K.Green.copy(alpha = .45f); else -> K.Red.copy(alpha = .6f) }
    val bg = if (mine) K.Gold.copy(alpha = .12f) else K.Surface
    val sticker = m.img && img != null && img.width == img.height && img.width <= 512
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterStart else Alignment.CenterEnd) {
        Column(
            Modifier.fillMaxWidth(.82f).wrapContentWidth(if (mine) Alignment.Start else Alignment.End)
                .clip(shape).background(bg).border(1.dp, edge, shape).padding(horizontal = D.S3, vertical = D.S2),
        ) {
            if (m.admin) {
                T(when { checking -> "پشتیبانی · در حال دریافت…"; ok -> "✓ از طرف ادمین"; else -> "⚠ امضا تأیید نشد؛ به این پیام اعتماد نکنید" },
                    size = 11.sp, color = if (checking) K.Sub else if (ok) K.Green else K.Red, weight = FontWeight.Black)
                Spacer(Modifier.height(D.S1))
            }
            when {
                m.img -> {
                    if (img != null) Image(
                        img, null,
                        if (sticker) Modifier.size(112.dp).clip(RoundedCornerShape(D.RS))
                        else Modifier.widthIn(max = 220.dp).heightIn(max = 280.dp).clip(RoundedCornerShape(D.RT)),
                        contentScale = ContentScale.Fit,
                    )
                    else T("در حال دریافت عکس…", size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
                }
                m.kind == "config" -> {
                    T("کانفیگ", size = 12.sp, color = K.Gold, weight = FontWeight.Black)
                    Spacer(Modifier.height(D.S1))
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(m.body, maxLines = 3, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = K.Text))
                    }
                    if (ok) {
                        Spacer(Modifier.height(D.S2))
                        LineButton("کپی کانفیگ", Modifier.fillMaxWidth()) { onCopy(m.body) }
                    }
                }
                else -> Text(
                    m.body,
                    style = TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp, color = K.Text,
                        textDirection = androidx.compose.ui.text.style.TextDirection.Content),
                )
            }
            Spacer(Modifier.height(D.S1))
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(PanelTime.jalali(m.ts * 1000), size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
                // پیام خود کاربر: 🕓 در صف (هنوز به سرور نرسیده)، ✓ رسید، ✓✓ ادمین خواند
                if (mine) {
                    Spacer(Modifier.width(D.S1))
                    T(if (waiting) "🕓" else if (read) "✓✓" else "✓", size = 11.sp, color = if (read && !waiting) K.Accent else K.Sub, weight = FontWeight.Black)
                }
            }
        }
    }
}

// ================================================================== شکل‌ها

private fun androidx.compose.ui.graphics.drawscope.DrawScope.backArrow(c: Color) {
    val w = size.width
    val h = size.height
    // در راست‌به‌چپ «برگشت» به سمت راست است
    drawLine(c, Offset(w * .2f, h * .5f), Offset(w * .85f, h * .5f), w * .12f, StrokeCap.Round)
    drawLine(c, Offset(w * .55f, h * .2f), Offset(w * .85f, h * .5f), w * .12f, StrokeCap.Round)
    drawLine(c, Offset(w * .55f, h * .8f), Offset(w * .85f, h * .5f), w * .12f, StrokeCap.Round)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.sendArrow(c: Color) {
    val w = size.width
    val h = size.height
    val p = Path().apply {
        moveTo(w * .85f, h * .12f)
        lineTo(w * .12f, h * .45f)
        lineTo(w * .42f, h * .58f)
        lineTo(w * .55f, h * .88f)
        close()
    }
    drawPath(p, c, style = Stroke(w * .09f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.pictureIcon(c: Color) {
    val w = size.width
    val h = size.height
    drawRoundRect(c, topLeft = Offset(w * .08f, h * .18f), size = androidx.compose.ui.geometry.Size(w * .84f, h * .64f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .12f), style = Stroke(w * .09f))
    drawCircle(c, w * .08f, Offset(w * .34f, h * .40f))
    val p = Path().apply {
        moveTo(w * .14f, h * .76f)
        lineTo(w * .42f, h * .50f)
        lineTo(w * .60f, h * .66f)
        lineTo(w * .72f, h * .56f)
        lineTo(w * .88f, h * .76f)
    }
    drawPath(p, c, style = Stroke(w * .08f, cap = StrokeCap.Round))
}

/** عکس را کوچک می‌کند (بیشینه ۱۲۸۰ پیکسل، JPEG زیر ۱.۴ مگابایت) */
private fun shrink(ctx: Context, uri: Uri): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 2560 || bounds.outHeight / sample > 2560) sample *= 2
    val bmp = ctx.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
    val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
    var q = 82
    while (true) {
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, q, out)
        val b = out.toByteArray()
        if (b.size < 1_400_000) return b
        if (q <= 32) return null
        q -= 15
    }
}

/** عکس چت، کوچک‌شده تا حداکثر حدود ۹۰۰ پیکسل (برای نمایش کافی است و حافظه را پر نمی‌کند) */
private fun decodeSmall(b: ByteArray): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(b, 0, b.size, o)
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 900) s *= 2
    return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
}
