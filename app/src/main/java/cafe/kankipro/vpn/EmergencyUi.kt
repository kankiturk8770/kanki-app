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

/** نشان اضطراری: حلقه‌ی نجات قرمز و سفید */
@Composable
fun EmergencyIcon(size: Dp = 28.dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val ring = r * 0.36f
        val mid = r - ring / 2f
        drawCircle(K.Red, mid, center, style = Stroke(ring))
        // چهار نوار سفید روی حلقه
        for (i in 0 until 4) {
            drawArc(
                Color.White, startAngle = 45f + i * 90f - 16f, sweepAngle = 32f, useCenter = false,
                topLeft = Offset(center.x - mid, center.y - mid), size = androidx.compose.ui.geometry.Size(mid * 2, mid * 2),
                style = Stroke(ring),
            )
        }
        drawCircle(K.Red.copy(alpha = .9f), r, center, style = Stroke(r * 0.07f))
    }
}

/** دکمه‌ی کوچک گرد اضطراری (سربرگ برنامه) */
@Composable
fun EmergencyButton(size: Dp = 40.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(K.Red.copy(alpha = .10f))
            .border(1.dp, K.Red.copy(alpha = .45f), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { EmergencyIcon(size * 0.62f) }
}

/** کادر کوچک گوشه‌ی صفحه‌ی ورود */
@Composable
fun EmergencyBox(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.widthIn(max = 190.dp).clip(shape).background(K.Red.copy(alpha = .08f))
            .border(1.dp, K.Red.copy(alpha = .40f), shape).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmergencyIcon(26.dp)
        Spacer(Modifier.width(6.dp))
        Column {
            T("ارتباط اضطراری", size = 11.sp, color = K.Red, weight = FontWeight.Black)
            T("فقط وقتی اینترنت ملی شده", size = 9.sp, color = K.Sub, weight = FontWeight.Normal)
        }
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
                msgs.addAll(fresh.filter { f -> msgs.none { it.id == f.id } }.distinctBy { it.id })
                helpOpen = false
            }
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

    // مثل تلگرام: با باز شدن کیبورد، کادر نوشتن بالای کیبورد می‌ماند و متن دیده می‌شود
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 14.dp)) {
        // سربرگ
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(40.dp, onClick = onClose) { Canvas(Modifier.size(18.dp)) { backArrow(K.Gold) } }
            Spacer(Modifier.width(10.dp))
            EmergencyIcon(30.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                T("ارتباط اضطراری", size = 18.sp, weight = FontWeight.Black)
                T(if (user.isNotEmpty()) "حساب: $user" else "گفتگو با ادمین کانکی", size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
            }
            T(if (helpOpen) "بستن راهنما" else "راهنما", size = 12.sp, color = K.Gold, modifier = Modifier.clickable { helpOpen = !helpOpen }.padding(6.dp))
        }

        // راهنما
        if (helpOpen) {
            KCard(Modifier.fillMaxWidth(), padding = 12.dp, border = K.Red.copy(alpha = .35f)) {
                T("این بخش برای وقتی است که اینترنت ملی شده", size = 14.sp, color = K.Red, weight = FontWeight.Black)
                Spacer(Modifier.height(6.dp))
                T("وقتی اینترنت بین‌الملل قطع است، اینجا مستقیم با ادمین کانکی حرف می‌زنید و کانفیگ‌هایی که از این اختلال و فیلترینگ رد می‌شوند را تحویل می‌گیرید.", size = 12.sp, color = K.Text, weight = FontWeight.Normal)
                Spacer(Modifier.height(4.dp))
                T("• مشکل را بنویسید؛ می‌توانید اسکرین‌شات هم بفرستید.\n• جواب ادمین همین‌جا می‌آید. کانفیگ را با دکمه‌ی «کپی کانفیگ» بردارید.\n• پیام‌های ادمین امضا دارند: فقط پیامی که «✓ از طرف ادمین» دارد را قبول کنید.\n• در حالت عادی (اینترنت وصل) به این بخش نیازی ندارید.", size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
            }
            Spacer(Modifier.height(8.dp))
        }

        if (code == null) {
            // کاربر هنوز وارد نشده: کد اشتراکش را می‌خواهیم
            KCard(Modifier.fillMaxWidth(), padding = 14.dp) {
                T("کد اشتراک خود را وارد کنید", size = 14.sp, weight = FontWeight.Black)
                T("فقط مشتری‌های کانکی می‌توانند پیام بدهند.", size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
                Spacer(Modifier.height(10.dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = codeText, onValueChange = { codeText = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontFamily = Poppins, fontSize = 14.sp, color = K.Text),
                        placeholder = { Text("kanki-4-…", color = K.Sub.copy(alpha = .5f)) },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = K.Gold, unfocusedBorderColor = K.Line, cursorColor = K.Gold),
                    )
                }
                Spacer(Modifier.height(10.dp))
                GoldButton("ادامه", Modifier.fillMaxWidth()) {
                    val c = SubscriptionApi.normalize(codeText)?.second
                    if (c == null) status = "کد اشتراک درست نیست."
                    else { Emergency.saveCode(ctx, c); code = c }
                }
            }
        }

        status?.let {
            Spacer(Modifier.height(6.dp))
            T(it, size = 12.sp, color = K.Red, weight = FontWeight.Normal)
            // کد اشتباه بود: بشود دوباره نوشت (فقط وقتی کد از خود حساب نیامده)
            if (badCode && state.code == null) {
                Spacer(Modifier.height(6.dp))
                LineButton("عوض کردن کد", Modifier.fillMaxWidth()) {
                    Emergency.saveCode(ctx, ""); code = null; badCode = false; status = null; msgs.clear(); pending.clear()
                }
            }
        }

        // پیام‌ها
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (code != null && msgs.isEmpty() && status == null) {
                item { T("هنوز پیامی نیست. مشکل را بنویسید تا ادمین جواب بدهد.", size = 12.sp, color = K.Sub, weight = FontWeight.Normal, modifier = Modifier.padding(8.dp)) }
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
            // استیکرهای کانکی
            if (stickersOpen) {
                LazyRow(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Stickers.all, key = { it.key }) { s ->
                        val bmp = remember(s.key) { Stickers.bitmap(ctx, s, 160).asImageBitmap() }
                        Image(bmp, s.text, Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)).clickable {
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
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(44.dp, onClick = { stickersOpen = !stickersOpen }) { T("😊", size = 18.sp) }
                Spacer(Modifier.width(6.dp))
                RoundIcon(44.dp, onClick = { if (!sending) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Canvas(Modifier.size(20.dp)) { pictureIcon(K.Gold) }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { if (it.length <= 2000) text = it }, modifier = Modifier.weight(1f), maxLines = 4,
                    textStyle = TextStyle(fontFamily = Vazir, fontSize = 14.sp, color = K.Text),
                    placeholder = { Text("پیام به ادمین…", color = K.Sub.copy(alpha = .6f), fontFamily = Vazir) },
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = K.Gold, unfocusedBorderColor = K.Line, cursorColor = K.Gold),
                )
                Spacer(Modifier.width(8.dp))
                RoundIcon(44.dp, filled = true, onClick = {
                    val c = code
                    val t = text.trim()
                    if (t.isNotEmpty() && c != null && !sending) {
                        // مثل تلگرام: پیام فوراً در صف و روی صفحه (🕓)، و هر وقت سرور در دسترس بود فرستاده می‌شود
                        EmergencyStore.queueText(ctx, c, t)
                        text = ""
                        refreshPending()
                        scope.launch { load() }
                    }
                }) { Canvas(Modifier.size(20.dp)) { sendArrow(if (sending) K.Sub else K.Gold) } }
            }
        }
    }
}

@Composable
private fun Bubble(m: EMsg, img: ImageBitmap?, okOrNull: Boolean?, read: Boolean = false, waiting: Boolean = false, onCopy: (String) -> Unit) {
    val ok = okOrNull ?: true
    val checking = m.admin && okOrNull == null
    val mine = !m.admin
    val shape = RoundedCornerShape(16.dp)
    val bg = if (mine) K.Gold.copy(alpha = .14f) else if (ok) K.Green.copy(alpha = .10f) else K.Red.copy(alpha = .10f)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterStart else Alignment.CenterEnd) {
        Column(Modifier.widthIn(max = 300.dp).clip(shape).background(bg).padding(10.dp)) {
            if (m.admin) {
                T(when { checking -> "از طرف ادمین · در حال دریافت…"; ok -> "✓ از طرف ادمین"; else -> "⚠ امضا تأیید نشد؛ به این پیام اعتماد نکنید" }, size = 11.sp, color = if (checking) K.Sub else if (ok) K.Green else K.Red, weight = FontWeight.Black)
                Spacer(Modifier.height(4.dp))
            }
            when {
                m.img -> {
                    if (img != null) Image(img, null, Modifier.widthIn(max = 240.dp).heightIn(max = 320.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
                    else T("در حال دریافت عکس…", size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
                }
                m.kind == "config" -> {
                    T("کانفیگ", size = 12.sp, color = K.Gold, weight = FontWeight.Black)
                    Spacer(Modifier.height(4.dp))
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(m.body, maxLines = 3, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = K.Text))
                    }
                    if (ok) {
                        Spacer(Modifier.height(6.dp))
                        LineButton("کپی کانفیگ", Modifier.fillMaxWidth()) { onCopy(m.body) }
                    }
                }
                else -> T(m.body, size = 14.sp, color = K.Text, weight = FontWeight.Normal)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(PanelTime.jalali(m.ts * 1000), size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
                // پیام خود کاربر: ✓ رسید به سرور، ✓✓ آبی ادمین خواند
                if (mine) {
                    Spacer(Modifier.width(6.dp))
                    // 🕓 در صف (هنوز به سرور نرسیده)، ✓ رسید، ✓✓ آبی ادمین خواند
                    T(if (waiting) "🕓" else if (read) "✓✓" else "✓", size = 11.sp, color = if (read && !waiting) Color(0xFF4FC3F7) else K.Sub, weight = FontWeight.Black)
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
