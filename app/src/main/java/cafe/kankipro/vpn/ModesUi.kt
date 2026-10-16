package cafe.kankipro.vpn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// انتخاب حالت اتصال در صفحه‌ی خانه: پنج کاشی با لوگوی اختصاصی، و تنظیمات همان حالت زیرش.

/** رنگ اختصاصی هر حالت */
fun modeColor(m: KMode): Color = when (m) {
    KMode.AUTO -> K.Gold
    KMode.GAME -> Color(0xFF7C8CFF)
    KMode.WEB -> Color(0xFFFF7A59)
    KMode.STRICT -> Color(0xFF3DDC97)
    KMode.DNS -> Color(0xFF4FC3F7)
}

/** لوگوی هر حالت (کشیده‌شده، بدون فایل تصویر) */
@Composable
fun ModeLogo(m: KMode, color: Color, size: Dp = 26.dp) {
    Canvas(Modifier.size(size)) { modeLogo(m, color) }
}

fun DrawScope.modeLogo(m: KMode, c: Color) {
    val w = size.width
    val sw = w * .085f
    val st = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (m) {
        KMode.AUTO -> {
            // دو پیکان چرخشی دور یک صاعقه: «خودش انتخاب می‌کند»
            drawArc(c, -200f, 150f, false, Offset(w * .1f, w * .1f), Size(w * .8f, w * .8f), style = st)
            drawArc(c, -20f, 150f, false, Offset(w * .1f, w * .1f), Size(w * .8f, w * .8f), style = st)
            val a1 = Path().apply { moveTo(w * .84f, w * .22f); lineTo(w * .88f, w * .42f); lineTo(w * .68f, w * .38f) }
            drawPath(a1, c, style = st)
            val a2 = Path().apply { moveTo(w * .16f, w * .78f); lineTo(w * .12f, w * .58f); lineTo(w * .32f, w * .62f) }
            drawPath(a2, c, style = st)
            val bolt = Path().apply {
                moveTo(w * .54f, w * .26f); lineTo(w * .38f, w * .53f); lineTo(w * .5f, w * .53f)
                lineTo(w * .45f, w * .74f); lineTo(w * .63f, w * .45f); lineTo(w * .51f, w * .45f); close()
            }
            drawPath(bolt, c)
        }
        KMode.GAME -> {
            // دسته‌ی بازی
            val body = Path().apply {
                moveTo(w * .28f, w * .3f); lineTo(w * .72f, w * .3f)
                cubicTo(w * .92f, w * .3f, w * .98f, w * .62f, w * .9f, w * .76f)
                cubicTo(w * .84f, w * .86f, w * .72f, w * .82f, w * .66f, w * .68f)
                lineTo(w * .34f, w * .68f)
                cubicTo(w * .28f, w * .82f, w * .16f, w * .86f, w * .1f, w * .76f)
                cubicTo(w * .02f, w * .62f, w * .08f, w * .3f, w * .28f, w * .3f); close()
            }
            drawPath(body, c, style = st)
            drawLine(c, Offset(w * .3f, w * .42f), Offset(w * .3f, w * .58f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * .22f, w * .5f), Offset(w * .38f, w * .5f), sw, StrokeCap.Round)
            drawCircle(c, w * .045f, Offset(w * .66f, w * .44f))
            drawCircle(c, w * .045f, Offset(w * .75f, w * .54f))
        }
        KMode.WEB -> {
            // صفحه با دکمه‌ی پخش
            drawRoundRect(c, Offset(w * .1f, w * .18f), Size(w * .8f, w * .56f), CornerRadius(w * .1f), style = st)
            drawLine(c, Offset(w * .34f, w * .86f), Offset(w * .66f, w * .86f), sw, StrokeCap.Round)
            val play = Path().apply { moveTo(w * .43f, w * .34f); lineTo(w * .43f, w * .58f); lineTo(w * .63f, w * .46f); close() }
            drawPath(play, c)
        }
        KMode.STRICT -> {
            // سپر با قفل
            val sh = Path().apply {
                moveTo(w * .5f, w * .08f); lineTo(w * .86f, w * .22f); lineTo(w * .86f, w * .48f)
                cubicTo(w * .86f, w * .72f, w * .7f, w * .86f, w * .5f, w * .94f)
                cubicTo(w * .3f, w * .86f, w * .14f, w * .72f, w * .14f, w * .48f); lineTo(w * .14f, w * .22f); close()
            }
            drawPath(sh, c, style = st)
            drawRoundRect(c, Offset(w * .36f, w * .46f), Size(w * .28f, w * .22f), CornerRadius(w * .04f))
            drawArc(c, 180f, 180f, false, Offset(w * .4f, w * .32f), Size(w * .2f, w * .26f), style = Stroke(sw * .8f, cap = StrokeCap.Round))
        }
        KMode.DNS -> {
            // کره با کلید باز (رفع تحریم)
            drawCircle(c, w * .36f, Offset(w * .46f, w * .46f), style = st)
            drawLine(c, Offset(w * .1f, w * .46f), Offset(w * .82f, w * .46f), sw * .8f)
            drawOval(c, Offset(w * .3f, w * .1f), Size(w * .32f, w * .72f), style = Stroke(sw * .8f))
            drawCircle(c, w * .14f, Offset(w * .78f, w * .78f))
            val ck = Path().apply { moveTo(w * .72f, w * .78f); lineTo(w * .77f, w * .83f); lineTo(w * .85f, w * .73f) }
            drawPath(ck, K.Ink, style = Stroke(sw * .7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** پنج کاشی حالت */
@Composable
fun ModeBar(selected: KMode, enabled: Boolean, modifier: Modifier = Modifier, onPick: (KMode) -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        KMode.entries.forEach { m ->
            val sel = m == selected
            val mc = modeColor(m)
            val bg by animateColorAsState(if (sel) mc.copy(alpha = .18f) else K.Panel.copy(alpha = .7f), tween(220), label = "mode")
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier.weight(1f).clip(shape).background(bg)
                    .border(if (sel) 1.5.dp else 1.dp, if (sel) mc else K.Gold.copy(alpha = .12f), shape)
                    .clickable(enabled = enabled && !sel) { onPick(m) }
                    .padding(vertical = 9.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape)
                        .background(if (sel) Brush.radialGradient(listOf(mc.copy(alpha = .35f), mc.copy(alpha = .08f))) else Brush.radialGradient(listOf(Color.Transparent, Color.Transparent))),
                    contentAlignment = Alignment.Center,
                ) { ModeLogo(m, if (sel) mc else K.Sub, 24.dp) }
                Spacer(Modifier.height(4.dp))
                T(m.title, size = 10.sp, color = if (sel) mc else K.Text, weight = if (sel) FontWeight.Black else FontWeight.Bold)
            }
        }
    }
}

/** اطلاعات بخش زیر انتخاب حالت */
data class ModeUi(
    val mode: KMode,
    val netName: String,
    /** حالت خودکار: مرحله‌ای که الان امتحان می‌شود */
    val tryStep: String?,
    val gamesOnly: Boolean,
    val gameCount: Int,
    /** حالت DNS: (شناسه، عنوان، نقش) سرورها؛ شناسه‌ی خالی یعنی خودکار */
    val dnsServers: List<Triple<String, String, String>>,
    val dnsPick: String,
    val dnsActive: String?,
    val dot: String?,
    val hasDns: Boolean,
    val hasXray: Boolean,
)

class ModeActions(
    val setGamesOnly: (Boolean) -> Unit = {},
    val pickDns: (String) -> Unit = {},
    val copyDot: () -> Unit = {},
    val openPrivateDns: () -> Unit = {},
)

/** توضیح و تنظیمات حالت انتخاب‌شده */
@Composable
fun ModeExtras(u: ModeUi, act: ModeActions, enabled: Boolean) {
    val mc = modeColor(u.mode)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(mc))
            Spacer(Modifier.width(6.dp))
            T(u.mode.sub, size = 11.sp, color = K.Sub, weight = FontWeight.Normal, modifier = Modifier.weight(1f))
            if (u.netName.isNotBlank() && u.mode != KMode.DNS) T("📶 " + u.netName, size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
        }
        when (u.mode) {
            KMode.AUTO -> {
                Spacer(Modifier.height(8.dp))
                // زنجیره‌ی خودکار
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf("تانل", "مستقیم", "فیلتر شدید").forEachIndexed { i, s ->
                        if (i > 0) { T(" ← ", size = 11.sp, color = K.Sub, weight = FontWeight.Normal) }
                        val on = u.tryStep == s
                        val shape = RoundedCornerShape(50)
                        T(
                            s, size = 10.sp, color = if (on) K.OnGold else K.Gold, weight = FontWeight.Black,
                            modifier = Modifier.clip(shape).background(if (on) K.Gold else K.Gold.copy(alpha = .1f)).padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                T("هر راهی که روی این اینترنت وصل شد، دفعه‌ی بعد اول همان امتحان می‌شود.", size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
            }
            KMode.GAME -> {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T("فقط بازی‌ها از VPN", size = 12.sp, color = K.Text, weight = FontWeight.Bold)
                        T(if (u.gameCount > 0) trf("%d بازی روی گوشی پیدا شد؛ بقیه‌ی برنامه‌ها مستقیم", u.gameCount) else "بازی‌ای روی گوشی پیدا نشد", size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
                    }
                    Switch(
                        checked = u.gamesOnly, onCheckedChange = act.setGamesOnly, enabled = enabled && u.gameCount > 0,
                        colors = SwitchDefaults.colors(checkedThumbColor = K.OnGold, checkedTrackColor = mc, uncheckedTrackColor = K.Panel),
                    )
                }
            }
            KMode.STRICT -> if (!u.hasXray) {
                Spacer(Modifier.height(4.dp))
                T("اشتراک شما Xray ندارد؛ Hysteria2 امتحان می‌شود.", size = 10.sp, color = K.Warn, weight = FontWeight.Normal)
            }
            KMode.DNS -> {
                if (!u.hasDns) {
                    Spacer(Modifier.height(6.dp))
                    T("رفع تحریم در اشتراک شما فعال نیست.", size = 11.sp, color = K.Warn, weight = FontWeight.Bold)
                    return@Column
                }
                Spacer(Modifier.height(8.dp))
                T("این حالت VPN نیست: فقط سایت‌ها و بازی‌های تحریمی از سرور کانکی رد می‌شوند و بقیه با سرعت کامل مستقیم.", size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
                Spacer(Modifier.height(8.dp))
                // انتخاب سرور DNS
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    u.dnsServers.forEach { (id, title, role) ->
                        val sel = u.dnsPick == id
                        val shape = RoundedCornerShape(12.dp)
                        Row(
                            Modifier.clip(shape).background(if (sel) mc.copy(alpha = .18f) else K.Panel.copy(alpha = .7f))
                                .border(1.dp, if (sel) mc else K.Gold.copy(alpha = .12f), shape)
                                .clickable(enabled = enabled) { act.pickDns(id) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            T(when (role) { "relay" -> "🇮🇷 "; "exit" -> "🌍 "; else -> "✨ " }, size = 11.sp, weight = FontWeight.Normal)
                            AutoText(title, size = 11.sp, color = if (sel) mc else K.Text)
                        }
                    }
                }
                u.dnsActive?.let {
                    Spacer(Modifier.height(4.dp))
                    T(trf("وصل به: %s", it), size = 10.sp, color = K.Green, weight = FontWeight.Bold)
                }
                // Private DNS: بدون برنامه، همیشه روشن
                if (u.dot != null) {
                    Spacer(Modifier.height(10.dp))
                    val shape = RoundedCornerShape(14.dp)
                    Column(
                        Modifier.fillMaxWidth().clip(shape).background(K.Panel.copy(alpha = .7f)).border(1.dp, mc.copy(alpha = .35f), shape).padding(10.dp),
                    ) {
                        T("بدون روشن کردن برنامه: Private DNS", size = 12.sp, color = K.Text, weight = FontWeight.Bold)
                        T("در تنظیمات گوشی › Private DNS این آدرس را بگذارید:", size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
                        Spacer(Modifier.height(4.dp))
                        T(u.dot, size = 11.sp, color = mc, font = Poppins, weight = FontWeight.SemiBold, ltr = true)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallGold("کپی آدرس", Modifier.weight(1f), onClick = act.copyDot)
                            SmallLine("باز کردن تنظیمات", Modifier.weight(1f), color = mc, onClick = act.openPrivateDns)
                        }
                    }
                }
            }
            else -> {}
        }
    }
}
