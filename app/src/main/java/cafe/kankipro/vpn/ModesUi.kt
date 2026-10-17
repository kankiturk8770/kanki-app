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
    // آبی-فیروزه‌ای: با بنفش Amnezia قاطی نشود
    KMode.DNS -> Color(0xFF3ED6E0)
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
    /** مصرف این اتصال (دریافت + ارسال، بایت)؛ null وقتی وصل نیست */
    val sessionBytes: Long? = null,
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
                if (u.appCount > 0) T(
                    u.appNames.joinToString(" · ") + if (u.appCount > u.appNames.size) " …" else "",
                    size = 10.sp, color = K.Sub, weight = FontWeight.Normal, maxLines = 1,
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
                T("بقیه‌ی برنامه‌ها اینترنت نداشته باشند", size = 12.sp, color = K.Text, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(trf("وصل به: %s", it) + (u.activeRoute?.let { r -> " · " + tr(r) } ?: ""), size = 10.sp, color = K.Green, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                u.sessionBytes?.let { b -> T(trf("مصرف: %s", formatBytes(b)), size = 10.sp, color = mc, weight = FontWeight.Bold) }
            }
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

/** یک سرور در پنل گیمینگ / ضد فیلتر: پرچم، اسم، برچسب (تانل، مستقیم، …) و پینگ */
data class SheetServer(val node: String, val proto: String, val flag: String, val name: String, val tag: String?, val ping: Long?)

/** یک خانواده‌ی پروتکل در پنل گیمینگ (KANKI MIX، Amnezia، WireGuard) با سرورهای خودش */
data class GameFamilyUi(val key: String, val title: String, val sub: String, val servers: List<SheetServer>) {
    val best: Long? get() = servers.mapNotNull { it.ping }.filter { it > 0 }.minOrNull()
}

/** کارت یک خانواده‌ی پروتکل (سه کارت کنار هم بالای پنل گیمینگ) */
@Composable
private fun FamilyCard(f: GameFamilyUi, selected: Boolean, badge: String?, modifier: Modifier, onClick: () -> Unit) {
    val c = familyColor(f.key)
    val shape = RoundedCornerShape(16.dp)
    val has = f.servers.isNotEmpty()
    Column(
        modifier.clip(shape)
            .background(Brush.verticalGradient(listOf(c.copy(alpha = if (selected) .22f else .07f), K.Panel.copy(alpha = .6f))))
            .border(if (selected) 1.6.dp else 1.dp, if (selected) c else c.copy(alpha = .28f), shape)
            .alpha(if (has) 1f else .4f)
            .clickable(enabled = has, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(24.dp)) { familyIcon(f.key, c) }
        Spacer(Modifier.height(5.dp))
        AutoText(f.title, size = 12.5.sp, color = if (selected) K.Text else K.Text.copy(alpha = .85f))
        T(if (!has) tr("در اشتراک نیست") else f.best?.let { "$it ms" } ?: trf("%d سرور", f.servers.size),
            size = 9.5.sp, color = f.best?.let { pingColor(it) } ?: K.Sub, font = if (f.best != null) Poppins else Vazir, weight = FontWeight.SemiBold, ltr = f.best != null)
        badge?.let {
            Spacer(Modifier.height(4.dp))
            Tag(it, c, strong = true)
        }
    }
}

/** یک ردیف سرور: لمس یعنی همین حالا به همین وصل شو */
@Composable
private fun ServerRow(sv: SheetServer, selected: Boolean, color: Color, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 7.dp).clip(shape)
            .background(if (selected) color.copy(alpha = .12f) else Color(0x08FFFFFF))
            .border(if (selected) 1.3.dp else 1.dp, if (selected) color.copy(alpha = .85f) else K.Gold.copy(alpha = .16f), shape)
            .alpha(if (enabled) 1f else .55f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Radio(selected, color)
        Spacer(Modifier.width(10.dp))
        T(sv.flag, size = 15.sp, weight = FontWeight.Normal)
        Spacer(Modifier.width(8.dp))
        AutoText(sv.name, size = 13.sp, color = K.Text, modifier = Modifier.weight(1f))
        sv.tag?.let { Tag(it, color); Spacer(Modifier.width(6.dp)) }
        sv.ping?.let { p -> T(if (p < 0) "✕" else "$p ms", size = 11.sp, color = pingColor(p), font = Poppins, weight = FontWeight.SemiBold, ltr = true) }
            ?: T("…", size = 11.sp, color = K.Sub, font = Poppins, ltr = true)
    }
}

/** ردیف «سریع‌ترین سرور»: خودش کم‌پینگ‌ترین سرور همین پروتکل را انتخاب و وصل می‌کند */
@Composable
private fun FastestRow(selected: Boolean, color: Color, best: Long?, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(shape)
            .background(Brush.horizontalGradient(listOf(color.copy(alpha = if (selected) .26f else .14f), color.copy(alpha = .04f))))
            .border(if (selected) 1.5.dp else 1.dp, color.copy(alpha = if (selected) .9f else .45f), shape)
            .alpha(if (enabled) 1f else .5f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T("⚡", size = 18.sp, weight = FontWeight.Normal)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            T("سریع‌ترین سرور", size = 14.sp, color = K.Text, weight = FontWeight.Black)
            T("خودش کم‌پینگ‌ترین را انتخاب و وصل می‌کند", size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
        }
        best?.let { T("$it ms", size = 11.sp, color = pingColor(it), font = Poppins, weight = FontWeight.SemiBold, ltr = true); Spacer(Modifier.width(6.dp)) }
        Chevron(color)
    }
}

/**
 * پنل گیمینگ: بالا سه پروتکل (KANKI MIX اول، Amnezia، WireGuard)؛ زیرش فقط سرورهای همان پروتکل.
 * لمس یک سرور = اتصال مستقیم به همان. «سریع‌ترین سرور» کم‌پینگ‌ترین سرور همان پروتکل را برمی‌دارد.
 * برای KANKI MIX کلید «ارسال دوتایی» هست؛ «تنظیمات پیشرفته» همان «فقط بازی‌ها» است.
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
    gamesOnly: Boolean,
    autoGames: Boolean,
    appCount: Int,
    canBlock: Boolean,
    onKmixDup: (Boolean) -> Unit,
    onGamesOnly: (Boolean) -> Unit,
    onAutoGames: (Boolean) -> Unit,
    onPickApps: () -> Unit,
    onFamilyShown: (String) -> Unit,
    onConnect: (node: String, proto: String) -> Unit,
    onFastest: (family: String) -> Unit,
    onClose: () -> Unit,
) {
    var fam by remember(visible) {
        mutableStateOf(initFamily.takeIf { k -> families.any { it.key == k && it.servers.isNotEmpty() } }
            ?: families.firstOrNull { it.servers.isNotEmpty() }?.key ?: initFamily)
    }
    var advanced by remember(visible) { mutableStateOf(false) }
    LaunchedEffect(visible, fam) { if (visible) onFamilyShown(fam) }
    val f = families.firstOrNull { it.key == fam }
    val c = familyColor(fam)
    ModeSheet(visible, KMode.GAME, "گیمینگ", "پروتکل را انتخاب کن، بعد سرور را بزن", onClose) {
        SheetLabel("پروتکل")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            families.forEach { x ->
                FamilyCard(x, x.key == fam, if (x.key == "kankimix") tr("ویژه گیم") else null, Modifier.weight(1f)) { fam = x.key }
            }
        }
        T(f?.sub ?: "", size = 10.5.sp, color = K.Sub, weight = FontWeight.Normal, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
        if (fam == "kankimix" && f?.servers?.isNotEmpty() == true) {
            SwitchRow("ارسال دوتایی بسته‌ها", "هر بسته‌ی بازی دو بار می‌رود: پکت‌لاس اثر ندارد (کمی حجم بیشتر)", kmixDup, c, onChange = onKmixDup)
        }
        SheetLabel(trf("سرورهای %s", f?.title ?: ""))
        if (f == null || f.servers.isEmpty()) {
            T("این پروتکل در اشتراک شما نیست.", size = 11.sp, color = K.Warn, weight = FontWeight.Normal)
        } else {
            FastestRow(currentAuto && Modes.familyOf(current?.substringAfter("|") ?: "") == fam, c, f.best, !busy) { onFastest(fam) }
            f.servers.forEach { sv ->
                ServerRow(sv, !currentAuto && current == sv.node + "|" + sv.proto, c, !busy) { onConnect(sv.node, sv.proto) }
            }
        }

        // تنظیمات پیشرفته: «فقط بازی‌ها اینترنت داشته باشند»
        val ash = RoundedCornerShape(16.dp)
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp).clip(ash)
                .background(c.copy(alpha = .05f))
                .border(1.2.dp, Brush.horizontalGradient(listOf(c.copy(alpha = .7f), K.Gold.copy(alpha = .4f))), ash)
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
            Column(Modifier.padding(top = 6.dp, bottom = 6.dp)) {
                SwitchRow(
                    "فقط بازی‌ها اینترنت داشته باشند",
                    if (appCount > 0) trf("%d برنامه مجاز است؛ بقیه تا قطع اتصال اینترنت ندارند", appCount) else "اول بازی یا برنامه‌ای انتخاب کنید",
                    gamesOnly, c, enabled = appCount > 0 || gamesOnly, onChange = onGamesOnly,
                )
                SwitchRow("تشخیص خودکار بازی‌ها", "بازی‌های نصب‌شده خودشان به فهرست اضافه می‌شوند", autoGames, c, onChange = onAutoGames)
                SmallLine("انتخاب برنامه‌ها", Modifier.fillMaxWidth(), color = K.Gold, onClick = onPickApps)
                if (gamesOnly && !canBlock) {
                    Spacer(Modifier.height(6.dp))
                    T("روی این نسخه‌ی اندروید برای بستن بقیه‌ی برنامه‌ها «مسدود کردن اتصال‌های بدون VPN» را در تنظیمات VPN گوشی روشن کنید.",
                        size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TickChip("Xray", trf("%d سرور", xray.size), useX, protoColor("xray"), Modifier.weight(1f)) { onTicks(!useX, useAwg) }
            TickChip("Amnezia", trf("%d سرور", amnezia.size), useAwg, protoColor("amneziawg"), Modifier.weight(1f)) { onTicks(useX, !useAwg) }
        }
        val list = (if (useX) xray else emptyList()) + (if (useAwg) amnezia else emptyList())
        Spacer(Modifier.height(10.dp))
        FastestRow(auto, mc, list.mapNotNull { it.ping }.filter { it > 0 }.minOrNull(), !busy && list.isNotEmpty(), onAuto)
        if (useX) {
            SheetLabel("Xray")
            if (xray.isEmpty()) T("اشتراک شما Xray ندارد.", size = 11.sp, color = K.Warn, weight = FontWeight.Normal)
            xray.forEach { sv -> ServerRow(sv, !auto && current == sv.node + "|" + sv.proto, protoColor("xray"), !busy) { onConnect(sv.node, sv.proto) } }
        }
        if (useAwg) {
            SheetLabel("Amnezia")
            if (amnezia.isEmpty()) T("اشتراک شما Amnezia ندارد.", size = 11.sp, color = K.Warn, weight = FontWeight.Normal)
            amnezia.forEach { sv -> ServerRow(sv, !auto && current == sv.node + "|" + sv.proto, protoColor("amneziawg"), !busy) { onConnect(sv.node, sv.proto) } }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** چیپ تیک‌دار بزرگ (Xray / Amnezia) */
@Composable
private fun TickChip(title: String, sub: String, on: Boolean, c: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.clip(shape)
            .background(if (on) c.copy(alpha = .16f) else Color(0x08FFFFFF))
            .border(if (on) 1.5.dp else 1.dp, if (on) c else K.Gold.copy(alpha = .18f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckMark(on, 20.dp, c)
        Spacer(Modifier.width(8.dp))
        Column {
            T(title, size = 13.5.sp, color = K.Text, weight = FontWeight.Black, font = Poppins, ltr = true)
            T(sub, size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
        }
    }
}

/** رنگ هر پروتکل (حلقه و هاله‌ی کهکشانی دکمه‌ی اتصال، کارت‌ها) */
fun protoColor(p: String?): Color = when (p) {
    "kankimix" -> Color(0xFFFF8A3D)
    "amneziawg", "amneziawg-direct" -> Color(0xFF9B7BFF)
    "wireguard" -> Color(0xFF34D399)
    "hysteria2" -> Color(0xFFF06292)
    "xray" -> Color(0xFF4FB3FF)
    else -> K.Gold
}

fun familyColor(f: String): Color = when (f) {
    "kankimix" -> protoColor("kankimix")
    "amnezia" -> protoColor("amneziawg")
    else -> protoColor("wireguard")
}

/** آیکون خانواده‌ی پروتکل: KANKI MIX دسته‌ی بازی با جرقه، Amnezia سپر، WireGuard اژدهای ساده (موج) */
fun DrawScope.familyIcon(f: String, c: Color) {
    val w = size.width
    val st = Stroke(w * .09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (f) {
        "kankimix" -> {
            modeLogo(KMode.GAME, c)
            drawCircle(c, w * .07f, Offset(w * .88f, w * .14f))
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
