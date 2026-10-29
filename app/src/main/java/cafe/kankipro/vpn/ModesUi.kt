package cafe.kankipro.vpn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// چهار کاشی حالت، پنل هوشمند، کارت DNS، پنل گیمینگ و انتخاب برنامه‌ها.

/** رنگ اختصاصی هر حالت برای حلقه و نور دکمه‌ی اتصال؛ از تم (هر تم چهار رنگ هماهنگ خودش را دارد) */
fun modeColor(m: KMode): Color = when (m) {
    KMode.SMART -> ThemeState.current.modeSmart
    KMode.GAME -> ThemeState.current.modeGame
    KMode.FILTER -> ThemeState.current.modeAnti
    KMode.DNS -> ThemeState.current.modeDns
}

/** آیکون هر حالت (کشیده‌شده، بدون فایل تصویر) */
@Composable
fun ModeIcon(m: KMode, color: Color, size: Dp = D.Icon) {
    if (m == KMode.DNS) {
        Box(
            Modifier.size(size).clip(CircleShape).background(color.copy(alpha = .08f)).border(1.4.dp, color, CircleShape),
            contentAlignment = Alignment.Center,
        ) { T("DNS", size = (size.value * .26f).sp, color = color, font = Poppins, weight = FontWeight.Bold, ltr = true) }
    } else {
        Canvas(Modifier.size(size)) { modeLogo(m, color) }
    }
}

fun DrawScope.modeLogo(m: KMode, c: Color) {
    val w = size.width
    val sw = w * .085f
    val st = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (m) {
        KMode.SMART -> {
            // ستاره‌ی چهارپر بزرگ و یک ستاره‌ی کوچک (درخشش)
            fun star(cx: Float, cy: Float, r: Float) = Path().apply {
                moveTo(cx, cy - r)
                quadraticBezierTo(cx + r * .16f, cy - r * .16f, cx + r, cy)
                quadraticBezierTo(cx + r * .16f, cy + r * .16f, cx, cy + r)
                quadraticBezierTo(cx - r * .16f, cy + r * .16f, cx - r, cy)
                quadraticBezierTo(cx - r * .16f, cy - r * .16f, cx, cy - r)
                close()
            }
            drawPath(star(w * .42f, w * .42f, w * .36f), c)
            drawPath(star(w * .80f, w * .80f, w * .16f), c)
        }
        KMode.GAME -> {
            // دسته‌ی بازی
            val body = Path().apply {
                moveTo(w * .28f, w * .28f); lineTo(w * .72f, w * .28f)
                cubicTo(w * .92f, w * .28f, w * .98f, w * .62f, w * .9f, w * .76f)
                cubicTo(w * .84f, w * .86f, w * .72f, w * .82f, w * .66f, w * .68f)
                lineTo(w * .34f, w * .68f)
                cubicTo(w * .28f, w * .82f, w * .16f, w * .86f, w * .1f, w * .76f)
                cubicTo(w * .02f, w * .62f, w * .08f, w * .28f, w * .28f, w * .28f); close()
            }
            drawPath(body, c, style = st)
            drawLine(c, Offset(w * .3f, w * .40f), Offset(w * .3f, w * .56f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * .22f, w * .48f), Offset(w * .38f, w * .48f), sw, StrokeCap.Round)
            drawCircle(c, w * .05f, Offset(w * .66f, w * .42f))
            drawCircle(c, w * .05f, Offset(w * .76f, w * .53f))
        }
        KMode.FILTER -> {
            // سپر با یک موشک کوچک داخلش (عبور از فیلتر)
            val sh = Path().apply {
                moveTo(w * .5f, w * .05f); lineTo(w * .88f, w * .19f); lineTo(w * .88f, w * .48f)
                cubicTo(w * .88f, w * .72f, w * .72f, w * .88f, w * .5f, w * .96f)
                cubicTo(w * .28f, w * .88f, w * .12f, w * .72f, w * .12f, w * .48f); lineTo(w * .12f, w * .19f); close()
            }
            drawPath(sh, c, style = st)
            rotate(45f, Offset(w * .5f, w * .5f)) {
                val cx = w * .5f
                val rocket = Path().apply {
                    moveTo(cx, w * .25f)
                    quadraticBezierTo(cx + w * .10f, w * .34f, cx + w * .08f, w * .58f)
                    lineTo(cx - w * .08f, w * .58f)
                    quadraticBezierTo(cx - w * .10f, w * .34f, cx, w * .25f)
                    close()
                }
                drawPath(rocket, c)
                val finL = Path().apply { moveTo(cx - w * .08f, w * .48f); lineTo(cx - w * .15f, w * .62f); lineTo(cx - w * .08f, w * .58f); close() }
                val finR = Path().apply { moveTo(cx + w * .08f, w * .48f); lineTo(cx + w * .15f, w * .62f); lineTo(cx + w * .08f, w * .58f); close() }
                drawPath(finL, c)
                drawPath(finR, c)
                drawLine(c.copy(alpha = .7f), Offset(cx, w * .64f), Offset(cx, w * .72f), sw * .8f, StrokeCap.Round)
                drawCircle(Color.Black.copy(alpha = .55f), w * .035f, Offset(cx, w * .40f))
            }
        }
        KMode.DNS -> {
            drawCircle(c, w * .4f, Offset(w * .5f, w * .5f), style = st)
        }
    }
}

/**
 * چهار کاشی حالت (هوشمند، گیمینگ، ضد فیلتر، DNS)؛ گیمینگ با نگه‌داشتن هم پنلش را باز می‌کند.
 * هر کاشی لبه و نور خودش را به رنگ همان حالت دارد: انتخاب‌شده لبه‌ی پررنگ با درخشش، بقیه لبه‌ی کم‌رنگ.
 */
@Composable
fun ModeRow(selected: KMode, enabled: Boolean, modifier: Modifier = Modifier, showDns: Boolean = true, onTap: (KMode) -> Unit, onLong: (KMode) -> Unit) {
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLong)
    val compact = LocalCompact.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(D.S2)) {
        // DNS only for a subscription that has Kanki DNS (the selected mode always stays visible)
        KMode.entries.filter { it != KMode.DNS || showDns || it == selected }.forEach { m ->
            val sel = m == selected
            val mc = modeColor(m)
            // روشن شدن نرم کاشی موقع انتخاب
            val lit by animateFloatAsState(if (sel) 1f else 0f, tween(260), label = "mode")
            val shape = RoundedCornerShape(D.RS)
            Column(
                Modifier.weight(1f).height(if (compact) D.TileS else D.Tile).modeGlass(shape, mc, lit)
                    .alpha(if (enabled) 1f else .5f)
                    .pointerInput(m, enabled) {
                        if (enabled) detectTapGestures(
                            onTap = { tap(m) },
                            onLongPress = { long(m) },
                        )
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ModeIcon(m, if (sel) lerp(mc, Color.White, .18f) else mc.copy(alpha = .8f), if (compact) 18.dp else D.Icon)
                Spacer(Modifier.height(D.S1))
                T(m.title, size = 11.sp, color = if (sel) K.Text else K.Text.copy(alpha = .78f), weight = if (sel) FontWeight.Black else FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/**
 * شیشه‌ی کاشی حالت به رنگ خود حالت (lit: ۰ = انتخاب‌نشده، ۱ = انتخاب‌شده):
 * انتخاب‌نشده لبه‌ی ۱dp با ٪۳۰ رنگ و بدون درخشش؛ انتخاب‌شده لبه‌ی ۱.۵dp پررنگ، درخشش نرم بیرون و داخل کمی روشن‌تر.
 */
fun Modifier.modeGlass(shape: RoundedCornerShape, c: Color, lit: Float): Modifier = this
    .drawBehind {
        if (lit > .01f) {
            val o = shape.createOutline(size, layoutDirection, this)
            for ((w, a) in listOf(11f to .06f, 6.5f to .12f, 3.2f to .26f)) drawOutline(o, c.copy(alpha = a * lit), style = Stroke(w.dp.toPx()))
        }
    }
    .clip(shape)
    .background(K.Surface)
    .background(
        Brush.linearGradient(
            listOf(c.copy(alpha = .05f + .17f * lit), Color.White.copy(alpha = .015f), Color.Black.copy(alpha = .32f - .08f * lit), c.copy(alpha = .03f + .08f * lit)),
        ),
    )
    .drawWithContent {
        // براقی شیشه در نیمه‌ی بالا
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .07f + .07f * lit), Color.Transparent), endY = size.height * .5f))
        drawContent()
        // خط نور کوتاه وسط لبه‌ی پایین، به رنگ حالت (فقط برای انتخاب‌شده پررنگ)
        val y = size.height - 1.dp.toPx()
        val line = Brush.horizontalGradient(listOf(Color.Transparent, lerp(c, Color.White, .25f), Color.Transparent), startX = size.width * .2f, endX = size.width * .8f)
        drawLine(line, Offset(size.width * .2f, y), Offset(size.width * .8f, y), 3.dp.toPx(), alpha = .3f * lit)
        drawLine(line, Offset(size.width * .2f, y), Offset(size.width * .8f, y), 1.1.dp.toPx(), alpha = .25f + .75f * lit)
    }
    .border(
        (1f + .5f * lit).dp,
        Brush.linearGradient(
            listOf(
                lerp(c, Color.White, .3f * lit).copy(alpha = .30f + .70f * lit),
                c.copy(alpha = .22f + .68f * lit),
                c.copy(alpha = .30f + .70f * lit),
            ),
        ),
        shape,
    )

/** یک خط باریک زیر کاشی‌ها: حالت انتخاب‌شده چه می‌کند */
@Composable
fun ModeHint(m: KMode, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(D.RS)
    Row(
        modifier.fillMaxWidth().goldGlass(shape)
            .padding(horizontal = D.S3, vertical = if (LocalCompact.current) D.S1 else D.S2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniIcon("spark", modeColor(m), 12.dp)
        Spacer(Modifier.width(D.S2))
        T(tr(m.title) + " : " + tr(m.sub), size = 11.sp, color = K.Sub, weight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
    }
}

// ================================================================== پنل هوشمند

data class SmartRowUi(val key: String, val flag: String, val title: String, val ping: Long)

data class SmartPanelUi(
    val open: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val status: String = "",
    val rows: List<SmartRowUi> = emptyList(),
    val current: String? = null,
)

/** ارتفاع هر ردیف پنل هوشمند (بدون فاصله‌ی بالایش) */
val SmartRowH = D.S6

/** پنل کوچک «هوشمند»: پیشرفت سنجش، بهترین‌ها و سروری که به آن وصل می‌شود؛ بعد از اتصال خودش بسته می‌شود */
@Composable
fun SmartPanel(u: SmartPanelUi, modifier: Modifier = Modifier) {
    val mc = modeColor(KMode.SMART)
    KCard(modifier.fillMaxWidth(), padding = D.CardPad, radius = D.RM, border = mc.copy(alpha = .45f)) {
        Row(Modifier.height(D.S5), verticalAlignment = Alignment.CenterVertically) {
            Spinner(14.dp, mc)
            Spacer(Modifier.width(D.S2))
            T(u.status, size = 12.sp, color = K.Text, weight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
            if (u.total > 0) T("${u.done}/${u.total}", size = 11.sp, color = K.Sub, font = Poppins, weight = FontWeight.SemiBold, ltr = true)
        }
        if (u.total > 0) {
            Spacer(Modifier.height(D.S2))
            ProgressLine(u.done.toFloat() / u.total.coerceAtLeast(1), mc, height = 4.dp)
        }
        u.rows.forEachIndexed { i, r ->
            val cur = r.key == u.current
            Row(Modifier.fillMaxWidth().padding(top = D.S1).height(SmartRowH), verticalAlignment = Alignment.CenterVertically) {
                if (cur) Spinner(12.dp, K.Green) else if (i == 0) MiniIcon("crown", mc, 12.dp) else Spacer(Modifier.width(12.dp))
                Spacer(Modifier.width(D.S2))
                T(r.flag, size = 13.sp, weight = FontWeight.Normal)
                Spacer(Modifier.width(D.S2))
                AutoText(r.title, size = 11.sp, color = if (cur) K.Green else K.Text, modifier = Modifier.weight(1f))
                PingChip(r.ping)
            }
        }
    }
}

// ================================================================== کارت حالت DNS

/** یک سرور DNS در فهرست: خودکار، مستقیم (سرور خارج) یا تانل (ریلی ایران ← سرور خارج) */
data class DnsRowUi(val id: String, val title: String, val role: String, val flag: String, val exitFlag: String = "", val exitTitle: String = "")

data class DnsCardUi(
    val hasDns: Boolean = false,
    /** سرورهایی که در پنل اضافه شده‌اند؛ شناسه‌ی خالی یعنی خودکار */
    val servers: List<DnsRowUi> = emptyList(),
    val pick: String = "",
    val active: String? = null,
    /** مسیر سرور فعال: «از ریلی ایران» یا «مستقیم» */
    val activeRoute: String? = null,
    /** مصرف این اتصال (دریافت + ارسال، بایت)؛ null وقتی وصل نیست */
    val sessionBytes: Long? = null,
)

class DnsActions(
    val pickDns: (String) -> Unit = {},
)

/**
 * کارت حالت DNS: دکمه‌ی «سرورهای DNS» که فهرست سرورهای پنل را باز می‌کند.
 * سرور مستقیم: پرچم و اسم سرور خارج. سرور تانل: 🇮🇷 ← پرچم خارج با برچسب «تانل»، تا معلوم باشد از ایران رد می‌شود.
 */
@Composable
fun DnsCard(u: DnsCardUi, vstate: VState, act: DnsActions, modifier: Modifier = Modifier) {
    val mc = modeColor(KMode.DNS)
    val shape = RoundedCornerShape(D.RM)
    val compact = LocalCompact.current
    var open by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().goldGlass(shape, tint = mc)
            .padding(horizontal = D.S3, vertical = if (compact) D.S1 else D.S2),
    ) {
        if (!u.hasDns) {
            T("اشتراک شما DNS ندارد", size = 12.sp, color = K.Warn, weight = FontWeight.Bold)
            return@Column
        }
        val cur = u.servers.firstOrNull { it.id == u.pick } ?: u.servers.firstOrNull()
        val bs = RoundedCornerShape(D.RS)
        Row(
            Modifier.fillMaxWidth().height(if (compact) D.HomeRowS else D.HomeRow).clip(bs)
                .background(Brush.horizontalGradient(listOf(mc.copy(alpha = .26f), mc.copy(alpha = .10f))))
                .border(1.dp, mc.copy(alpha = .7f), bs)
                .clickable { open = !open }
                .padding(horizontal = D.S3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("DNS", size = 11.sp, color = mc, font = Poppins, weight = FontWeight.Bold, ltr = true)
            Spacer(Modifier.width(D.S3))
            Column(Modifier.weight(1f)) {
                T("سرورهای DNS", size = 12.sp, color = K.Text, weight = FontWeight.Black)
                cur?.let { T(dnsRowLabel(it), size = 10.sp, color = K.Sub, weight = FontWeight.Normal, maxLines = 1) }
            }
            T(if (open) "▴" else "▾", size = 14.sp, color = mc)
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(top = D.S2), verticalArrangement = Arrangement.spacedBy(D.S1)) {
                u.servers.forEach { r ->
                    val sel = u.pick == r.id
                    val rs = RoundedCornerShape(D.RS)
                    Row(
                        Modifier.fillMaxWidth().clip(rs)
                            .background(if (sel) mc.copy(alpha = .16f) else Color(0x0CFFFFFF))
                            .border(1.dp, if (sel) mc else Color(0x18FFFFFF), rs)
                            .clickable(enabled = vstate != VState.CONNECTING) { act.pickDns(r.id); open = false }
                            .height(D.RowS).padding(horizontal = D.S3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            AutoText(dnsRowTitle(r), size = 12.5.sp, color = if (sel) mc else K.Text)
                            T(dnsRowTag(r), size = 10.sp, color = K.Sub, weight = FontWeight.Bold)
                        }
                        if (sel) CheckMark(true, D.Icon, mc)
                    }
                }
                if (vstate == VState.ON) T("برای عوض کردن سرور، یک بار قطع و وصل کن", size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
            }
        }
        u.active?.let {
            Spacer(Modifier.height(D.S1))
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(trf("وصل به: %s", it) + (u.activeRoute?.let { r -> " · " + tr(r) } ?: ""), size = 10.sp, color = K.Green, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                u.sessionBytes?.let { b -> T(trf("مصرف: %s", formatBytes(b)), size = 10.sp, color = mc, weight = FontWeight.Bold) }
            }
        }
    }
}

/** اسم سرور (برای تانل: اسم سرور خارجی که تانل به آن می‌رسد؛ بدون پرچم و اسم ایران) */
private fun dnsRowTitle(r: DnsRowUi): String = when (r.role) {
    "relay" -> r.exitTitle.ifBlank { r.title }
    "auto" -> tr("خودکار")
    else -> r.title
}

private fun dnsRowTag(r: DnsRowUi): String = tr(when (r.role) { "relay" -> "تانل"; "auto" -> "سریع‌ترین"; else -> "مستقیم" })

private fun dnsRowLabel(r: DnsRowUi): String = dnsRowTitle(r) + " · " + dnsRowTag(r)

/** چهار مربع (آیکون برنامه‌ها) */
fun DrawScope.appsGridIcon(c: Color) {
    val w = size.width
    val s = w * .38f
    val r = CornerRadius(w * .1f)
    drawRoundRect(c, Offset(w * .06f, w * .06f), Size(s, s), r)
    drawRoundRect(c.copy(alpha = .6f), Offset(w * .56f, w * .06f), Size(s, s), r)
    drawRoundRect(c.copy(alpha = .6f), Offset(w * .06f, w * .56f), Size(s, s), r)
    drawRoundRect(c, Offset(w * .56f, w * .56f), Size(s, s), r)
}

// ================================================================== پنل‌های پایین (گیمینگ و ضد فیلتر)

/** قاب پنل پایین با پرده‌ی تیره روی نقشه؛ با لمس بیرون بسته می‌شود */
@Composable
fun ModeSheet(
    visible: Boolean, mode: KMode, title: String, sub: String, onClose: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val mc = modeColor(mode)
    AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Box(
            Modifier.fillMaxSize().background(Color(0xA6000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible,
            enter = slideInVertically(tween(260)) { it } + fadeIn(),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(),
        ) {
            val shape = RoundedCornerShape(topStart = D.RX, topEnd = D.RX)
            Column(
                Modifier.fillMaxWidth().heightIn(max = 660.dp).clip(shape)
                    .background(Brush.verticalGradient(listOf(K.Surface.copy(alpha = 1f), K.Panel)))
                    .border(1.dp, Brush.verticalGradient(listOf(K.Gold.copy(alpha = .55f), K.Gold.copy(alpha = .08f))), shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .padding(horizontal = D.PagePad, vertical = D.S2),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(D.S6 * 2).height(4.dp).clip(CircleShape).background(K.Gold.copy(alpha = .3f)))
                Spacer(Modifier.height(D.S3))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(D.IconBox + D.S1).clip(RoundedCornerShape(D.RS)).background(mc.copy(alpha = .10f)).border(1.dp, K.Gold.copy(alpha = .5f), RoundedCornerShape(D.RS)),
                        contentAlignment = Alignment.Center,
                    ) { ModeIcon(mode, mc, D.S6) }
                    Spacer(Modifier.width(D.S3))
                    Column(Modifier.weight(1f)) {
                        T(title, size = D.TxtPage, color = K.Text, weight = FontWeight.Black)
                        T(sub, size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal)
                    }
                    trailing()
                }
                Spacer(Modifier.height(D.S2))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = D.S3, bottom = D.S2, start = D.S1, end = D.S1), verticalAlignment = Alignment.CenterVertically) {
        T(text, size = 12.sp, color = K.Sub, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** برچسب کوچک گرد */
@Composable
private fun Tag(text: String, c: Color, strong: Boolean = false) {
    val sh = RoundedCornerShape(D.RT)
    T(
        text, size = 9.5.sp, color = c, weight = if (strong) FontWeight.Black else FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(sh).background(c.copy(alpha = if (strong) .18f else .10f)).border(1.dp, c.copy(alpha = .35f), sh)
            .padding(horizontal = D.S2, vertical = 2.dp),
    )
}

@Composable
private fun Radio(on: Boolean, c: Color) {
    Box(
        Modifier.size(D.Icon).clip(CircleShape).border(1.6.dp, if (on) c else K.Sub.copy(alpha = .6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(c)) }
}

@Composable
private fun SwitchRow(title: String, sub: String?, on: Boolean, color: Color, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = D.S1), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(title, size = 13.sp, color = K.Text, weight = FontWeight.Bold)
            sub?.let { T(it, size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal) }
        }
        Switch(
            checked = on, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = K.OnGold, checkedTrackColor = K.ToggleOn, uncheckedTrackColor = K.Panel),
        )
    }
}

/** یک سرور در پنل گیمینگ / ضد فیلتر: پرچم، اسم، برچسب (تانل، مستقیم، …)، پینگ و راهش (tunnel / direct) */
data class SheetServer(val node: String, val proto: String, val flag: String, val name: String, val tag: String?, val ping: Long?, val side: String = "direct")

/** یک سرور با دو راهش کنار هم: کانفیگ تانل (راست) و کانفیگ مستقیم (چپ)؛ هر کدام ممکن است نباشد */
data class SheetPair(val flag: String, val name: String, val tunnel: SheetServer?, val direct: SheetServer?) {
    val best: Long? get() = listOfNotNull(tunnel?.ping, direct?.ping).filter { it > 0 }.minOrNull()
}

/**
 * کانفیگ‌های یک پروتکل، هر سرور یک ردیف: تانل و مستقیم همان سرور روبه‌روی هم (حتی اگر تانل سرور دیگری پینگ بهتری
 * داشته باشد)؛ ردیف‌ها به ترتیب بهترین پینگ هر سرور.
 */
fun pairsOf(list: List<SheetServer>): List<SheetPair> =
    list.groupBy { it.node.removeSuffix(TUNNEL_TWIN) }.values.flatMap { g ->
        val t = g.filter { it.side == "tunnel" }
        val d = g.filter { it.side != "tunnel" }
        val head = g.first()
        (0 until maxOf(t.size, d.size)).map { i -> SheetPair(head.flag, head.name, t.getOrNull(i), d.getOrNull(i)) }
    }.sortedBy { it.best ?: 99_999L }

/** یک خانواده‌ی پروتکل در پنل گیمینگ (KANKI MIX، Amnezia، WireGuard) با سرورهای خودش */
data class GameFamilyUi(val key: String, val title: String, val sub: String, val servers: List<SheetServer>) {
    val best: Long? get() = servers.mapNotNull { it.ping }.filter { it > 0 }.minOrNull()
}

/**
 * کارت یک خانواده‌ی پروتکل: دو کارت هم‌اندازه در هر ردیف (روی هر عرض گوشی کامل دیده می‌شوند، بدون اسکرول کناری)؛
 * آیکون، اسم و زیرش بهترین پینگ یا تعداد سرورها.
 */
@Composable
private fun FamilyCard(f: GameFamilyUi, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = familyColor(f.key)
    val shape = RoundedCornerShape(D.RS)
    val has = f.servers.isNotEmpty()
    Row(
        modifier.height(D.RowH).clip(shape)
            .background(Brush.horizontalGradient(listOf(c.copy(alpha = if (selected) .22f else .07f), K.Panel.copy(alpha = .6f))))
            .border(if (selected) 1.6.dp else 1.dp, if (selected) c else c.copy(alpha = .28f), shape)
            .alpha(if (has) 1f else .4f)
            .clickable(enabled = has, onClick = onClick)
            .padding(horizontal = D.S3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(D.Icon)) { familyIcon(f.key, c) }
        Spacer(Modifier.width(D.S2))
        Column(Modifier.weight(1f)) {
            AutoText(f.title, size = 12.5.sp, color = if (selected) K.Text else K.Text.copy(alpha = .85f))
            T(if (!has) tr("در اشتراک نیست") else f.best?.let { "$it ms" } ?: trf("%d سرور", f.servers.size),
                size = 10.sp, color = f.best?.let { pingColor(it) } ?: K.Sub, font = if (f.best != null) Poppins else Vazir, weight = FontWeight.SemiBold, ltr = f.best != null,
                maxLines = 1)
        }
        if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(c))
    }
}

/** سرتیتر دو ستون: «تانل» راست، «مستقیم» چپ */
@Composable
private fun PairHeader(c: Color) {
    Row(Modifier.fillMaxWidth().padding(bottom = D.S1)) {
        for (k in listOf("تانل", "مستقیم")) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                T(k, size = 11.sp, color = if (k == "تانل") c else K.Sub, weight = FontWeight.Black)
            }
        }
    }
}

/** یک سرور: پرچم و اسم، زیرش کانفیگ تانل (راست) و مستقیم (چپ)؛ لمس هر خانه = اتصال به همان */
@Composable
private fun PairRow(p: SheetPair, current: String?, auto: Boolean, color: Color, enabled: Boolean, onClick: (SheetServer) -> Unit) {
    val shape = RoundedCornerShape(D.RS)
    val lit = !auto && listOfNotNull(p.tunnel, p.direct).any { current == it.node + "|" + it.proto }
    Column(
        Modifier.fillMaxWidth().padding(bottom = D.S2).clip(shape)
            .background(if (lit) color.copy(alpha = .07f) else Color(0x06FFFFFF))
            .border(1.dp, if (lit) color.copy(alpha = .6f) else K.Gold.copy(alpha = .14f), shape)
            .padding(D.S2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = D.S1, end = D.S1, bottom = D.S1)) {
            T(p.flag, size = 14.sp, weight = FontWeight.Normal)
            Spacer(Modifier.width(D.S2))
            AutoText(p.name, size = 12.5.sp, color = K.Text, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(D.S2)) {
            PairCell(p.tunnel, "تانل", !auto && p.tunnel != null && current == p.tunnel.node + "|" + p.tunnel.proto, color, enabled, Modifier.weight(1f), onClick)
            PairCell(p.direct, "مستقیم", !auto && p.direct != null && current == p.direct.node + "|" + p.direct.proto, color, enabled, Modifier.weight(1f), onClick)
        }
    }
}

/** یک خانه: دکمه‌ی انتخاب، اسم راه (تانل، مستقیم یا اسمی که ادمین گذاشته) و پینگ؛ خالی وقتی این سرور آن راه را ندارد */
@Composable
private fun PairCell(sv: SheetServer?, label: String, selected: Boolean, color: Color, enabled: Boolean, modifier: Modifier, onClick: (SheetServer) -> Unit) {
    val shape = RoundedCornerShape(D.RT)
    Row(
        modifier.height(D.RowS).clip(shape)
            .background(if (selected) color.copy(alpha = .16f) else if (sv != null) Color(0x0AFFFFFF) else Color.Transparent)
            .border(if (selected) 1.3.dp else 1.dp, if (selected) color else if (sv != null) K.Gold.copy(alpha = .16f) else K.Gold.copy(alpha = .06f), shape)
            .alpha(if (sv == null) .45f else if (enabled) 1f else .55f)
            .clickable(enabled = enabled && sv != null) { sv?.let(onClick) }
            .padding(horizontal = D.S2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sv == null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { T("—", size = 12.sp, color = K.Sub, ltr = true) }
            return@Row
        }
        Radio(selected, color)
        Spacer(Modifier.width(D.S2))
        AutoText(sv.tag?.takeIf { label == "تانل" && it != tr("تانل") } ?: label, size = 11.5.sp, color = K.Text, modifier = Modifier.weight(1f))
        sv.ping?.let { p -> T(if (p < 0) "✕" else "$p ms", size = 10.5.sp, color = pingColor(p), font = Poppins, weight = FontWeight.SemiBold, ltr = true, maxLines = 1) }
            ?: T("…", size = 10.5.sp, color = K.Sub, font = Poppins, ltr = true)
    }
}

/** یک ردیف سرور: لمس یعنی همین حالا به همین وصل شو */
@Composable
private fun ServerRow(sv: SheetServer, selected: Boolean, color: Color, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(D.RS)
    Row(
        Modifier.fillMaxWidth().padding(bottom = D.S2).height(D.RowH).clip(shape)
            .background(if (selected) color.copy(alpha = .12f) else Color(0x08FFFFFF))
            .border(if (selected) 1.3.dp else 1.dp, if (selected) color.copy(alpha = .85f) else K.Gold.copy(alpha = .16f), shape)
            .alpha(if (enabled) 1f else .55f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = D.S3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Radio(selected, color)
        Spacer(Modifier.width(D.S3))
        T(sv.flag, size = 15.sp, weight = FontWeight.Normal)
        Spacer(Modifier.width(D.S2))
        AutoText(sv.name, size = 13.sp, color = K.Text, modifier = Modifier.weight(1f))
        sv.tag?.let { Tag(it, color); Spacer(Modifier.width(D.S2)) }
        sv.ping?.let { p -> T(if (p < 0) "✕" else "$p ms", size = 11.sp, color = pingColor(p), font = Poppins, weight = FontWeight.SemiBold, ltr = true) }
            ?: T("…", size = 11.sp, color = K.Sub, font = Poppins, ltr = true)
    }
}

/** ردیف «سریع‌ترین سرور»: خودش کم‌پینگ‌ترین سرور همین پروتکل را انتخاب و وصل می‌کند */
@Composable
private fun FastestRow(selected: Boolean, color: Color, best: Long?, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(D.RS)
    Row(
        Modifier.fillMaxWidth().padding(bottom = D.S2).height(D.RowH).clip(shape)
            .background(Brush.horizontalGradient(listOf(color.copy(alpha = if (selected) .26f else .14f), color.copy(alpha = .04f))))
            .border(if (selected) 1.5.dp else 1.dp, color.copy(alpha = if (selected) .9f else .45f), shape)
            .alpha(if (enabled) 1f else .5f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = D.S3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T("⚡", size = 16.sp, weight = FontWeight.Normal)
        Spacer(Modifier.width(D.S3))
        Column(Modifier.weight(1f)) {
            T("سریع‌ترین سرور", size = D.TxtTitle, color = K.Text, weight = FontWeight.Black, maxLines = 1)
            T("خودش کم‌پینگ‌ترین را انتخاب و وصل می‌کند", size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal, maxLines = 1)
        }
        best?.let { T("$it ms", size = 11.sp, color = pingColor(it), font = Poppins, weight = FontWeight.SemiBold, ltr = true); Spacer(Modifier.width(D.S2)) }
        Chevron(color)
    }
}

/**
 * پنل گیمینگ: بالا چهار پروتکل هم‌اندازه (KANKI MIX اول، Hysteria2، Amnezia، WireGuard)؛ زیرش سرورهای همان
 * پروتکل در دو ستون، تانل راست و مستقیم چپ، هر سرور یک ردیف. لمس یک خانه = اتصال به همان.
 * «سریع‌ترین سرور» کم‌پینگ‌ترین کانفیگ همان پروتکل را برمی‌دارد.
 * برای KANKI MIX کلید «ارسال دوتایی» هست. همه‌ی برنامه‌ها (بازی، سرویس‌های گوگل، مرورگر) از VPN رد می‌شوند.
 */
@Composable
fun GameSheet(
    visible: Boolean,
    families: List<GameFamilyUi>,
    initFamily: String,
    current: String?,
    currentAuto: Boolean,
    busy: Boolean,
    kmixDup: Boolean,
    onKmixDup: (Boolean) -> Unit,
    gameOnly: Boolean,
    gameCount: Int,
    onGameOnly: (Boolean) -> Unit,
    onPickGames: () -> Unit,
    onFamilyShown: (String) -> Unit,
    onConnect: (node: String, proto: String) -> Unit,
    onFastest: (family: String) -> Unit,
    onClose: () -> Unit,
) {
    var fam by remember(visible) {
        mutableStateOf(initFamily.takeIf { k -> families.any { it.key == k && it.servers.isNotEmpty() } }
            ?: families.firstOrNull { it.servers.isNotEmpty() }?.key ?: initFamily)
    }
    LaunchedEffect(visible, fam) { if (visible) onFamilyShown(fam) }
    val f = families.firstOrNull { it.key == fam }
    val c = familyColor(fam)
    ModeSheet(visible, KMode.GAME, "گیمینگ", "پروتکل را انتخاب کن، بعد سرور را بزن", onClose) {
        SheetLabel("پروتکل")
        // دو کارت در هر ردیف: هیچ پروتکلی نصفه یا بیرون از صفحه نیست
        Column(Modifier.fillMaxWidth().guideTarget("game_fams"), verticalArrangement = Arrangement.spacedBy(D.S2)) {
            families.chunked(2).forEach { two ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(D.S2)) {
                    two.forEach { x -> FamilyCard(x, x.key == fam, Modifier.weight(1f)) { fam = x.key } }
                    if (two.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        T(f?.sub ?: "", size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal, modifier = Modifier.padding(top = D.S2, start = D.S1))
        if (fam == "kankimix" && f?.servers?.isNotEmpty() == true) {
            SwitchRow("ارسال دوتایی بسته‌ها", "هر بسته‌ی بازی دو بار می‌رود: پکت‌لاس اثر ندارد (کمی حجم بیشتر)", kmixDup, c, onChange = onKmixDup)
        }
        // فایروال «فقط بازی»: فقط پروتکل‌های مخصوص بازی (KANKI MIX و microtun)، اندروید ۱۰ به بالا
        if ((fam == "kankimix" || fam == "mtun") && f?.servers?.isNotEmpty() == true) {
            val ok = AppGate.supported
            SwitchRow(
                "فقط بازی اینترنت داشته باشد",
                if (!ok) "روی اندروید ۱۰ به بالا" else trf("%d بازی · سرویس‌های گوگل و DNS هم باز می‌مانند؛ بقیه‌ی برنامه‌ها تا قطع VPN اینترنت ندارند", gameCount),
                gameOnly && ok, c, enabled = ok, onChange = onGameOnly,
            )
            if (gameOnly && ok) SmallLine("انتخاب بازی‌ها", onClick = onPickGames)
        }
        SheetLabel(trf("سرورهای %s", f?.title ?: ""))
        if (f == null || f.servers.isEmpty()) {
            T("این پروتکل در اشتراک شما نیست.", size = 11.sp, color = K.Warn, weight = FontWeight.Normal)
        } else {
            Column(Modifier.fillMaxWidth().guideTarget("game_list")) {
                FastestRow(currentAuto && Modes.familyOf(current?.substringAfter("|") ?: "") == fam, c, f.best, !busy) { onFastest(fam) }
                PairHeader(c)
                pairsOf(f.servers).forEach { p ->
                    PairRow(p, current, currentAuto, c, !busy) { sv -> onConnect(sv.node, sv.proto) }
                }
            }
        }

        Spacer(Modifier.height(D.S2))
    }
}

/**
 * پنل ضد فیلتر (نگه‌داشتن کاشی «ضد فیلتر»): فقط سرورهای Xray و Amnezia، نه همه‌ی سرورها.
 * دو تیک بالا (Xray و Amnezia) هم فهرست را عوض می‌کنند و هم اینکه «خودکار» بین کدام‌ها بگردد.
 */
@Composable
fun FilterSheet(
    visible: Boolean,
    xray: List<SheetServer>,
    amnezia: List<SheetServer>,
    useX: Boolean,
    useAwg: Boolean,
    auto: Boolean,
    current: String?,
    busy: Boolean,
    onTicks: (Boolean, Boolean) -> Unit,
    onShown: () -> Unit,
    onAuto: () -> Unit,
    onConnect: (node: String, proto: String) -> Unit,
    onClose: () -> Unit,
) {
    val mc = modeColor(KMode.FILTER)
    LaunchedEffect(visible) { if (visible) onShown() }
    ModeSheet(visible, KMode.FILTER, "ضد فیلتر", "Xray و Amnezia برای فیلتر شدید", onClose) {
        SheetLabel("پروتکل‌ها")
        Row(Modifier.fillMaxWidth().guideTarget("filter_list"), horizontalArrangement = Arrangement.spacedBy(D.S2)) {
            TickChip("Xray", trf("%d سرور", xray.size), useX, protoColor("xray"), Modifier.weight(1f)) { onTicks(!useX, useAwg) }
            TickChip("Amnezia", trf("%d سرور", amnezia.size), useAwg, protoColor("amneziawg"), Modifier.weight(1f)) { onTicks(useX, !useAwg) }
        }
        val list = (if (useX) xray else emptyList()) + (if (useAwg) amnezia else emptyList())
        Spacer(Modifier.height(D.S3))
        FastestRow(auto, mc, list.mapNotNull { it.ping }.filter { it > 0 }.minOrNull(), !busy && list.isNotEmpty(), onAuto)
        if (useX) {
            SheetLabel("Xray")
            if (xray.isEmpty()) T("اشتراک شما Xray ندارد.", size = 11.sp, color = K.Warn, weight = FontWeight.Normal)
            xray.forEach { sv -> ServerRow(sv, !auto && current == sv.node + "|" + sv.proto, protoColor("xray"), !busy) { onConnect(sv.node, sv.proto) } }
        }
        if (useAwg) {
            SheetLabel("Amnezia")
            if (amnezia.isEmpty()) T("اشتراک شما Amnezia ندارد.", size = 11.sp, color = K.Warn, weight = FontWeight.Normal)
            if (amnezia.isNotEmpty()) PairHeader(protoColor("amneziawg"))
            pairsOf(amnezia).forEach { p -> PairRow(p, current, auto, protoColor("amneziawg"), !busy) { sv -> onConnect(sv.node, sv.proto) } }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** چیپ تیک‌دار بزرگ (Xray / Amnezia) */
@Composable
private fun TickChip(title: String, sub: String, on: Boolean, c: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(D.RS)
    Row(
        modifier.height(D.RowH).clip(shape)
            .background(if (on) c.copy(alpha = .16f) else Color(0x08FFFFFF))
            .border(if (on) 1.5.dp else 1.dp, if (on) c else K.Gold.copy(alpha = .18f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = D.S3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckMark(on, D.Icon, c)
        Spacer(Modifier.width(D.S2))
        Column {
            T(title, size = 13.5.sp, color = K.Text, weight = FontWeight.Black, font = Poppins, ltr = true)
            T(sub, size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
        }
    }
}

/** رنگ هر پروتکل (حلقه و هاله‌ی دکمه‌ی اتصال، کارت‌ها)؛ همه از تُن‌های همان تم، تا کنار هم یکدست بمانند */
fun protoColor(p: String?): Color = when (p) {
    "kankimix" -> ThemeState.current.mix
    "mtun" -> ThemeState.current.connected
    "amneziawg", "amneziawg-direct" -> ThemeState.current.modeAnti
    "wireguard" -> ThemeState.current.modeDns
    "hysteria2" -> ThemeState.current.connected
    "xray" -> K.Gold
    else -> K.Gold
}

fun familyColor(f: String): Color = when (f) {
    "kankimix" -> protoColor("kankimix")
    "mtun" -> protoColor("mtun")
    "hysteria2" -> protoColor("hysteria2")
    "amnezia" -> protoColor("amneziawg")
    else -> protoColor("wireguard")
}

/** آیکون خانواده‌ی پروتکل: KANKI MIX دسته‌ی بازی با جرقه، Hysteria2 صاعقه، Amnezia سپر، WireGuard موج */
fun DrawScope.familyIcon(f: String, c: Color) {
    val w = size.width
    val st = Stroke(w * .09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (f) {
        "kankimix", "mtun" -> {
            modeLogo(KMode.GAME, c)
            drawCircle(c, w * .07f, Offset(w * .88f, w * .14f))
        }
        "hysteria2" -> {
            // صاعقه: سرعت روی UDP
            val bolt = Path().apply {
                moveTo(w * .58f, w * .06f); lineTo(w * .2f, w * .56f); lineTo(w * .48f, w * .56f)
                lineTo(w * .4f, w * .94f); lineTo(w * .8f, w * .42f); lineTo(w * .52f, w * .42f); close()
            }
            drawPath(bolt, c, style = st)
        }
        "amnezia" -> {
            val sh = Path().apply {
                moveTo(w * .5f, w * .06f); lineTo(w * .88f, w * .2f); lineTo(w * .88f, w * .48f)
                cubicTo(w * .88f, w * .72f, w * .72f, w * .88f, w * .5f, w * .96f)
                cubicTo(w * .28f, w * .88f, w * .12f, w * .72f, w * .12f, w * .48f); lineTo(w * .12f, w * .2f); close()
            }
            drawPath(sh, c, style = st)
            drawLine(c, Offset(w * .36f, w * .52f), Offset(w * .47f, w * .63f), w * .09f, StrokeCap.Round)
            drawLine(c, Offset(w * .47f, w * .63f), Offset(w * .66f, w * .40f), w * .09f, StrokeCap.Round)
        }
        else -> {
            val wave = Path().apply {
                moveTo(w * .08f, w * .55f)
                cubicTo(w * .25f, w * .25f, w * .4f, w * .25f, w * .5f, w * .5f)
                cubicTo(w * .6f, w * .75f, w * .75f, w * .75f, w * .92f, w * .45f)
            }
            drawPath(wave, c, style = st)
            drawCircle(c, w * .08f, Offset(w * .92f, w * .45f))
        }
    }
}
