package cafe.kankipro.vpn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// چهار کاشی حالت، پنل هوشمند، کارت DNS، پنل گیمینگ و انتخاب برنامه‌ها.

/** رنگ اختصاصی هر حالت برای حلقه و نور دکمه‌ی اتصال (ملایم، نه جیغ) */
fun modeColor(m: KMode): Color = when (m) {
    KMode.SMART -> K.Gold
    KMode.GAME -> Color(0xFF46D99A)
    KMode.FILTER -> Color(0xFF58A6F0)
    KMode.DNS -> Color(0xFF9E82EE)
}

/** آیکون هر حالت (کشیده‌شده، بدون فایل تصویر) */
@Composable
fun ModeIcon(m: KMode, color: Color, size: Dp = 22.dp) {
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

/** چهار کاشی حالت (هوشمند، گیمینگ، ضد فیلتر، DNS)؛ گیمینگ با نگه‌داشتن هم پنلش را باز می‌کند */
@Composable
fun ModeRow(selected: KMode, enabled: Boolean, modifier: Modifier = Modifier, onTap: (KMode) -> Unit, onLong: (KMode) -> Unit) {
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLong)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KMode.entries.forEach { m ->
            val sel = m == selected
            val bg by animateColorAsState(if (sel) K.Gold.copy(alpha = .10f) else Color(0x08FFFFFF), tween(220), label = "mode")
            val shape = RoundedCornerShape(16.dp)
            Box(Modifier.weight(1f).height(76.dp)) {
                if (sel) {
                    // نور طلایی ملایم پشت کاشی انتخاب‌شده
                    Canvas(Modifier.matchParentSize()) {
                        drawRoundRect(K.Gold.copy(alpha = .10f), cornerRadius = CornerRadius(18.dp.toPx()), style = Stroke(6.dp.toPx()))
                    }
                }
                Column(
                    Modifier.matchParentSize().clip(shape)
                        .background(Brush.verticalGradient(listOf(bg, K.Panel.copy(alpha = .55f))))
                        .border(if (sel) 1.5.dp else 1.dp, if (sel) K.Gold.copy(alpha = .9f) else K.Gold.copy(alpha = .22f), shape)
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
                    ModeIcon(m, if (sel) K.GoldLight else K.Gold, 26.dp)
                    Spacer(Modifier.height(6.dp))
                    T(m.title, size = 12.5.sp, color = if (sel) K.Text else K.Text.copy(alpha = .85f), weight = if (sel) FontWeight.Black else FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

/** یک خط باریک زیر کاشی‌ها: حالت انتخاب‌شده چه می‌کند */
@Composable
fun ModeHint(m: KMode, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(Color(0x08FFFFFF)).border(1.dp, K.Gold.copy(alpha = .16f), shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniIcon("spark", K.Gold, 12.dp)
        Spacer(Modifier.width(7.dp))
        T(tr(m.title) + " : " + tr(m.sub), size = 11.5.sp, color = K.Sub, weight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
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

/** پنل کوچک «هوشمند»: پیشرفت سنجش، بهترین‌ها و سروری که به آن وصل می‌شود؛ بعد از اتصال خودش بسته می‌شود */
@Composable
fun SmartPanel(u: SmartPanelUi, modifier: Modifier = Modifier) {
    val mc = modeColor(KMode.SMART)
    KCard(modifier.fillMaxWidth(), padding = 12.dp, radius = 18.dp, border = mc.copy(alpha = .45f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spinner(15.dp, mc)
            Spacer(Modifier.width(8.dp))
            T(u.status, size = 12.sp, color = K.Text, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (u.total > 0) T("${u.done}/${u.total}", size = 11.sp, color = K.Sub, font = Poppins, weight = FontWeight.SemiBold, ltr = true)
        }
        if (u.total > 0) {
            Spacer(Modifier.height(8.dp))
            ProgressLine(u.done.toFloat() / u.total.coerceAtLeast(1), mc, height = 4.dp)
        }
        u.rows.forEachIndexed { i, r ->
            val cur = r.key == u.current
            Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                if (cur) Spinner(12.dp, K.Green) else if (i == 0) MiniIcon("crown", mc, 12.dp) else Spacer(Modifier.width(12.dp))
                Spacer(Modifier.width(6.dp))
                T(r.flag, size = 13.sp, weight = FontWeight.Normal)
                Spacer(Modifier.width(6.dp))
                AutoText(r.title, size = 11.sp, color = if (cur) K.Green else K.Text, modifier = Modifier.weight(1f))
                PingChip(r.ping)
            }
        }
    }
}

// ================================================================== کارت حالت DNS

data class DnsCardUi(
    val hasDns: Boolean = false,
    /** اسم چند برنامه‌ی انتخابی برای نمایش */
    val appNames: List<String> = emptyList(),
    val appCount: Int = 0,
    /** (شناسه، عنوان، نقش) سرورهای DNS؛ شناسه‌ی خالی یعنی خودکار */
    val servers: List<Triple<String, String, String>> = emptyList(),
    val pick: String = "",
    val active: String? = null,
    /** مسیر سرور فعال: «از ریلی ایران» یا «مستقیم» */
    val activeRoute: String? = null,
    /** این گوشی می‌تواند بقیه‌ی برنامه‌ها را ببندد (اندروید ۱۰ به بالا) */
    val canBlock: Boolean = true,
    /** بقیه‌ی برنامه‌ها بسته‌اند (پینگ برنامه‌های انتخابی بالاتر می‌رود) */
    val block: Boolean = false,
)

class DnsActions(
    val pickApps: () -> Unit = {},
    val pickDns: (String) -> Unit = {},
    val toggleBlock: () -> Unit = {},
)

@Composable
fun DnsCard(u: DnsCardUi, vstate: VState, act: DnsActions, modifier: Modifier = Modifier) {
    val mc = modeColor(KMode.DNS)
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(listOf(mc.copy(alpha = .10f), mc.copy(alpha = .04f))))
            .border(1.dp, mc.copy(alpha = .38f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (!u.hasDns) {
            T("اشتراک شما DNS ندارد", size = 12.sp, color = K.Warn, weight = FontWeight.Bold)
            return@Column
        }
        Row(verticalAlignment = Alignment.Top) {
            T("⚠", size = 13.sp, color = K.Warn, weight = FontWeight.Normal)
            Spacer(Modifier.width(6.dp))
            T(
                if (u.canBlock && u.block) "تا وقتی DNS روشن است، فقط بازی‌ها و برنامه‌هایی که انتخاب کرده‌اید به اینترنت وصل‌اند و از DNS کانکی استفاده می‌کنند. بقیه‌ی برنامه‌ها تا خاموش کردن DNS اینترنت ندارند."
                else "تا وقتی DNS روشن است، بازی‌ها و برنامه‌هایی که انتخاب کرده‌اید از DNS کانکی استفاده می‌کنند؛ ترافیکشان مستقیم می‌رود.",
                size = 11.5.sp, color = K.Text, weight = FontWeight.Normal, modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        // دکمه‌ی انتخاب برنامه‌ها
        val bs = RoundedCornerShape(14.dp)
        Row(
            Modifier.fillMaxWidth().clip(bs)
                .background(Brush.horizontalGradient(listOf(mc.copy(alpha = .28f), mc.copy(alpha = .12f))))
                .border(1.dp, mc.copy(alpha = .7f), bs)
                .clickable(enabled = vstate != VState.CONNECTING, onClick = act.pickApps)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(20.dp)) { appsGridIcon(mc) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                T("انتخاب برنامه‌ها", size = 13.sp, color = K.Text, weight = FontWeight.Black)
                T(
                    if (u.appCount == 0) "هنوز برنامه‌ای انتخاب نشده (همه‌ی برنامه‌ها از DNS استفاده می‌کنند)"
                    else u.appNames.joinToString(" · ") + if (u.appCount > u.appNames.size) " …" else "",
                    size = 10.sp, color = K.Sub, weight = FontWeight.Normal,
                )
            }
            if (u.appCount > 0) {
                Box(Modifier.clip(CircleShape).background(mc).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    T(u.appCount.toString(), size = 11.sp, color = Color.White, font = Poppins, weight = FontWeight.Bold, ltr = true)
                }
                Spacer(Modifier.width(6.dp))
            }
            Chevron(mc)
        }
        if (u.canBlock) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = vstate == VState.OFF, onClick = act.toggleBlock).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckMark(u.block, 20.dp, mc)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    T("بقیه‌ی برنامه‌ها اینترنت نداشته باشند", size = 12.sp, color = K.Text, weight = FontWeight.Bold)
                    T("پینگ بازی بالاتر می‌رود؛ فقط اگر لازم است روشن کنید", size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
                }
            }
        }
        if (u.servers.size > 2) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                u.servers.forEach { (id, title, role) ->
                    val sel = u.pick == id
                    val cs = RoundedCornerShape(10.dp)
                    Row(
                        Modifier.clip(cs).background(if (sel) mc.copy(alpha = .2f) else Color(0x0AFFFFFF))
                            .border(1.dp, if (sel) mc else Color(0x16FFFFFF), cs)
                            .clickable(enabled = vstate == VState.OFF) { act.pickDns(id) }
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        T(when (role) { "relay" -> "🇮🇷 "; "exit" -> "🌍 "; else -> "✨ " }, size = 10.sp, weight = FontWeight.Normal)
                        AutoText(title, size = 10.sp, color = if (sel) mc else K.Text)
                    }
                }
            }
        }
        u.active?.let {
            Spacer(Modifier.height(6.dp))
            T(trf("وصل به: %s", it) + (u.activeRoute?.let { r -> " · " + tr(r) } ?: ""), size = 10.sp, color = K.Green, weight = FontWeight.Bold)
        }
    }
}

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

/** یک گزینه‌ی پروتکل در پنل */
data class SheetOption(val key: String, val title: String, val tag: String? = null, val ping: Long? = null, val enabled: Boolean = true)

/** یک کشور در پنل */
data class SheetCountry(val id: String, val name: String, val flag: String, val ping: Long?)

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
            val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column(
                Modifier.fillMaxWidth().heightIn(max = 660.dp).clip(shape)
                    .background(Brush.verticalGradient(listOf(K.Surface.copy(alpha = 1f), K.Panel)))
                    .border(1.dp, Brush.verticalGradient(listOf(K.Gold.copy(alpha = .55f), K.Gold.copy(alpha = .08f))), shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(44.dp).height(4.dp).clip(CircleShape).background(K.Gold.copy(alpha = .3f)))
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(mc.copy(alpha = .10f)).border(1.dp, K.Gold.copy(alpha = .5f), RoundedCornerShape(13.dp)),
                        contentAlignment = Alignment.Center,
                    ) { ModeIcon(mode, mc, 26.dp) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        T(title, size = 19.sp, color = K.Text, weight = FontWeight.Black)
                        T(sub, size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
                    }
                    trailing()
                }
                Spacer(Modifier.height(6.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp, start = 2.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        T(text, size = 12.sp, color = K.Sub, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** گزینه‌ی رادیویی: دایره، اسم، پینگ و برچسب (مثل «تانل»، «مستقیم») */
@Composable
private fun OptionRow(o: SheetOption, selected: Boolean, color: Color, dim: Boolean, badge: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 7.dp).clip(shape)
            .background(if (selected) K.Gold.copy(alpha = .08f) else Color(0x08FFFFFF))
            .border(if (selected) 1.3.dp else 1.dp, if (selected) K.Gold.copy(alpha = .75f) else K.Gold.copy(alpha = .16f), shape)
            .alpha(if (!o.enabled) .4f else if (dim) .8f else 1f)
            .clickable(enabled = o.enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Radio(selected, color)
        Spacer(Modifier.width(10.dp))
        AutoText(o.title, size = 13.5.sp, color = K.Text, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.weight(1f))
        o.ping?.let { p ->
            T(if (p < 0) "✕" else "$p ms", size = 10.5.sp, color = pingColor(p), font = Poppins, weight = FontWeight.SemiBold, ltr = true)
            Spacer(Modifier.width(8.dp))
        }
        badge?.let {
            Tag(it, K.GoldLight, strong = true)
            Spacer(Modifier.width(5.dp))
        }
        o.tag?.let { Tag(it, K.Gold) }
    }
}

/** برچسب کوچک گرد */
@Composable
private fun Tag(text: String, c: Color, strong: Boolean = false) {
    val sh = RoundedCornerShape(8.dp)
    T(
        text, size = 9.5.sp, color = c, weight = if (strong) FontWeight.Black else FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(sh).background(c.copy(alpha = if (strong) .18f else .10f)).border(1.dp, c.copy(alpha = .35f), sh)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun Radio(on: Boolean, c: Color) {
    Box(
        Modifier.size(18.dp).clip(CircleShape).border(1.6.dp, if (on) c else K.Sub.copy(alpha = .6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (on) Box(Modifier.size(9.dp).clip(CircleShape).background(c)) }
}

/** شبکه‌ی دوستونی کشورها */
@Composable
private fun CountryGrid(list: List<SheetCountry>, selected: String?, color: Color, enabled: Boolean = true, onPick: (String) -> Unit) {
    if (list.isEmpty()) {
        T("کشوری برای این پروتکل در اشتراک شما نیست.", size = 11.sp, color = K.Warn, weight = FontWeight.Normal)
        return
    }
    list.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 7.dp).alpha(if (enabled) 1f else .55f), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            row.forEach { c ->
                val sel = c.id == selected
                val shape = RoundedCornerShape(14.dp)
                Row(
                    Modifier.weight(1f).clip(shape)
                        .background(if (sel) K.Gold.copy(alpha = .08f) else Color(0x08FFFFFF))
                        .border(if (sel) 1.3.dp else 1.dp, if (sel) K.Gold.copy(alpha = .75f) else K.Gold.copy(alpha = .16f), shape)
                        .clickable(enabled = enabled) { onPick(c.id) }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Radio(sel, color)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            T(c.flag, size = 13.sp, weight = FontWeight.Normal)
                            Spacer(Modifier.width(4.dp))
                            AutoText(c.name, size = 12.5.sp, color = K.Text)
                        }
                        T(
                            c.ping?.let { if (it > 0) trf("پینگ %d", it) else "بدون پاسخ" } ?: "…",
                            size = 10.sp, color = c.ping?.let { pingColor(it) } ?: K.Sub, weight = FontWeight.Normal,
                        )
                    }
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** دکمه‌ی بزرگ طلایی «اتصال» پایین پنل */
@Composable
private fun SheetConnect(enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp).height(54.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(K.Shine, K.Gold, K.GoldDeep)))
            .border(1.dp, K.GoldLight.copy(alpha = .7f), shape)
            .alpha(if (enabled) 1f else .45f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T("اتصال", size = 17.sp, color = K.OnGold, weight = FontWeight.Black) }
}

/** چک‌باکس کوچک (مثل «انتخاب هوشمند سرور») */
@Composable
private fun AutoCheck(on: Boolean, color: Color, label: String, onToggle: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggle).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(5.dp)
        Box(
            Modifier.size(16.dp).clip(shape).background(if (on) color else Color.Transparent).border(1.5.dp, if (on) color else K.Sub.copy(alpha = .7f), shape),
            contentAlignment = Alignment.Center,
        ) {
            if (on) Canvas(Modifier.size(10.dp)) {
                val w = size.width
                drawLine(K.OnGold, Offset(w * .1f, w * .52f), Offset(w * .4f, w * .82f), w * .2f, StrokeCap.Round)
                drawLine(K.OnGold, Offset(w * .4f, w * .82f), Offset(w * .92f, w * .18f), w * .2f, StrokeCap.Round)
            }
        }
        Spacer(Modifier.width(5.dp))
        T(label, size = 11.sp, color = if (on) K.GoldLight else K.Sub, weight = FontWeight.Bold)
    }
}

@Composable
private fun SwitchRow(title: String, sub: String?, on: Boolean, color: Color, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(title, size = 12.5.sp, color = K.Text, weight = FontWeight.Bold)
            sub?.let { T(it, size = 10.sp, color = K.Sub, weight = FontWeight.Normal) }
        }
        Switch(
            checked = on, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = K.OnGold, checkedTrackColor = color, uncheckedTrackColor = K.Panel),
        )
    }
}

/** آیکون چرخ‌دنده (تنظیمات پیشرفته) */
fun DrawScope.gearIcon(c: Color) {
    val w = size.width
    val sw = w * .1f
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        drawLine(
            c, Offset(w / 2 + w * .28f * kotlin.math.cos(a).toFloat(), w / 2 + w * .28f * kotlin.math.sin(a).toFloat()),
            Offset(w / 2 + w * .44f * kotlin.math.cos(a).toFloat(), w / 2 + w * .44f * kotlin.math.sin(a).toFloat()), sw * 1.4f, StrokeCap.Round,
        )
    }
    drawCircle(c, w * .30f, Offset(w / 2, w / 2), style = Stroke(sw))
    drawCircle(c, w * .10f, Offset(w / 2, w / 2), style = Stroke(sw))
}

/**
 * پنل گیمینگ: «انتخاب هوشمند سرور»، فهرست پروتکل‌ها با برچسب، انتخاب کشور، تنظیمات پیشرفته
 * (با پیشنهاد ربات) و دکمه‌ی طلایی «اتصال». options(node): پروتکل‌های همان کشور با پینگ.
 * با «انتخاب هوشمند سرور»، کم‌پینگ‌ترین کشور و پروتکل خودکار برداشته می‌شود (bestNode).
 */
@Composable
fun GameSheet(
    visible: Boolean,
    countries: List<SheetCountry>,
    options: (String?) -> List<SheetOption>,
    bestNode: String?,
    initAuto: Boolean,
    initProto: String,
    initNode: String?,
    gamesOnly: Boolean,
    autoGames: Boolean,
    appCount: Int,
    canBlock: Boolean,
    onGamesOnly: (Boolean) -> Unit,
    onAutoGames: (Boolean) -> Unit,
    onPickApps: () -> Unit,
    onNodeShown: (String) -> Unit,
    onConnect: (Boolean, String, String?) -> Unit,
    onClose: () -> Unit,
) {
    val mc = modeColor(KMode.GAME)
    var auto by remember(visible) { mutableStateOf(initAuto) }
    var proto by remember(visible) { mutableStateOf(initProto) }
    var picked by remember(visible) { mutableStateOf(initNode?.takeIf { n -> countries.any { it.id == n } } ?: countries.firstOrNull()?.id) }
    var advanced by remember(visible) { mutableStateOf(false) }
    // با «انتخاب هوشمند» کشوری که نشان داده می‌شود همان کم‌پینگ‌ترین است
    val node = if (auto) (bestNode?.takeIf { b -> countries.any { it.id == b } } ?: picked) else picked
    LaunchedEffect(visible, node) { if (visible) node?.let { onNodeShown(it) } }
    ModeSheet(
        visible, KMode.GAME, "گیمینگ", "سریع‌ترین سرورها برای بازی", onClose,
        trailing = { AutoCheck(auto, K.Gold, "انتخاب هوشمند سرور") { auto = !auto } },
    ) {
        val opts = options(node)
        val best = opts.filter { it.enabled }.minByOrNull { it.ping?.takeIf { p -> p > 0 } ?: 99_999L }?.key
        SheetLabel("پروتکل")
        opts.forEach { o ->
            val sel = if (auto) o.key == best else o.key == proto
            OptionRow(o, sel, K.Gold, dim = auto, badge = if (o.key == best && opts.size > 1) "پیشنهادی" else null) { proto = o.key; auto = false }
        }
        SheetLabel("کشور")
        CountryGrid(countries, node, K.Gold, enabled = true) { picked = it; auto = false }

        // پیشنهاد ربات: تنظیم پیشرفته‌ی «فقط بازی‌ها» (پیش‌فرض خاموش است)
        if (!gamesOnly && !advanced) {
            Spacer(Modifier.height(6.dp))
            RobotSays(
                "اینجا رو ببین ⭐ توی «تنظیمات پیشرفته» گزینه‌ی «فقط بازی‌ها اینترنت داشته باشند» رو روشن کن؛ اینترنت فقط به بازی می‌رسه و پینگت بهتر میشه.",
                robotSize = 56.dp, robotAtStart = false, onClick = { advanced = true },
            )
        }
        // دکمه‌ی تنظیمات پیشرفته
        val ash = RoundedCornerShape(16.dp)
        val cyan = Color(0xFF6FD3F0)
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp).clip(ash)
                .background(cyan.copy(alpha = .05f))
                .border(1.2.dp, Brush.horizontalGradient(listOf(cyan.copy(alpha = .75f), K.Gold.copy(alpha = .45f))), ash)
                .clickable { advanced = !advanced }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(18.dp)) { gearIcon(K.GoldLight) }
            Spacer(Modifier.width(10.dp))
            T("تنظیمات پیشرفته", size = 14.sp, color = K.Text, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            T(if (advanced) "▲" else "▼", size = 10.sp, color = K.Sub, font = Poppins, ltr = true)
        }
        AnimatedVisibility(advanced, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = 6.dp)) {
                SwitchRow(
                    "فقط بازی‌ها اینترنت داشته باشند",
                    if (appCount > 0) trf("%d برنامه مجاز است؛ بقیه تا قطع اتصال اینترنت ندارند", appCount) else "اول بازی یا برنامه‌ای انتخاب کنید",
                    gamesOnly, mc, enabled = appCount > 0 || gamesOnly, onChange = onGamesOnly,
                )
                SwitchRow("تشخیص خودکار بازی‌ها", "بازی‌های نصب‌شده خودشان به فهرست اضافه می‌شوند", autoGames, mc, onChange = onAutoGames)
                SmallLine("انتخاب برنامه‌ها", Modifier.fillMaxWidth(), color = K.Gold, onClick = onPickApps)
                if (gamesOnly) {
                    Spacer(Modifier.height(6.dp))
                    T(
                        if (canBlock) "با Hysteria2 بقیه‌ی برنامه‌ها کاملاً بسته می‌شوند. با AmneziaWG و WireGuard برای بستن کامل، «مسدود کردن اتصال‌های بدون VPN» را در تنظیمات VPN گوشی روشن کنید."
                        else "روی این نسخه‌ی اندروید بقیه‌ی برنامه‌ها مستقیم وصل می‌مانند؛ برای بستنشان «مسدود کردن اتصال‌های بدون VPN» را در تنظیمات VPN گوشی روشن کنید.",
                        size = 10.sp, color = K.Sub, weight = FontWeight.Normal,
                    )
                }
            }
        }
        val chosen = if (auto) best else proto.takeIf { p -> opts.any { it.key == p && it.enabled } }
        SheetConnect(enabled = node != null && (auto || chosen != null)) {
            onConnect(auto, chosen ?: proto, node)
        }
    }
}

// ================================================================== انتخاب برنامه‌ها

/**
 * انتخاب بازی‌ها و برنامه‌هایی که در «فقط بازی‌ها» و حالت DNS اینترنت دارند.
 * بازی‌های تشخیص‌داده‌شده بالای فهرست‌اند و خودکار انتخاب می‌شوند؛ کاربر می‌تواند هر برنامه را اضافه یا حذف کند.
 */
@Composable
fun AppPicker(
    detected: List<String>,
    autoGames: Boolean,
    isOn: (String) -> Boolean,
    onToggle: (String) -> Unit,
    onAutoGames: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppItem>?>(null) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { AppsRepo.load(ctx) } }
    val mc = modeColor(KMode.DNS)
    Box(
        Modifier.fillMaxSize().background(K.Ink.copy(alpha = .97f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("انتخاب برنامه‌ها", size = 20.sp, color = K.Text, weight = FontWeight.Black)
                    T("فقط برنامه‌های تیک‌خورده اینترنت دارند", size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
                }
                SmallGold("تمام", onClick = onClose)
            }
            Spacer(Modifier.height(6.dp))
            SwitchRow("تشخیص خودکار بازی‌ها", "بازی‌های نصب‌شده خودشان انتخاب می‌شوند", autoGames, mc, onChange = onAutoGames)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { T("جستجو…", size = 13.sp, color = K.Sub) },
                    textStyle = TextStyle(fontFamily = Vazir, fontSize = 14.sp, color = K.Text),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = mc, unfocusedBorderColor = K.Line, cursorColor = mc),
                )
            }
            Spacer(Modifier.height(6.dp))
            val list = apps
            if (list == null) {
                Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { Spinner(26.dp, mc) }
            } else {
                val shown = list.filter { q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true) }
                    .sortedWith(compareBy({ if (it.pkg in detected) 0 else 1 }, { if (isOn(it.pkg)) 0 else 1 }))
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
                    items(shown.size) { idx ->
                        val app = shown[idx]
                        val on = isOn(app.pkg)
                        Row(
                            Modifier.fillMaxWidth().clickable { onToggle(app.pkg) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            app.icon?.let { Image(it, null, Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))) }
                                ?: Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(K.Surface))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    T(app.label, size = 14.sp)
                                    if (app.pkg in detected) {
                                        Spacer(Modifier.width(6.dp))
                                        T("🎮 بازی", size = 9.5.sp, color = modeColor(KMode.GAME), weight = FontWeight.Bold)
                                    }
                                }
                                T(app.pkg, size = 10.sp, color = K.Sub, font = Poppins, ltr = true)
                            }
                            CheckMark(on, 22.dp, mc)
                        }
                        HDivider()
                    }
                }
            }
        }
    }
}
