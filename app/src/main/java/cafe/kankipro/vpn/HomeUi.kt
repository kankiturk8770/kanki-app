package cafe.kankipro.vpn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

// صفحه‌ی اتصال (خانه) با طراحی کهکشانی. فقط ظاهر است؛ داده‌ها از AppState در Screens.kt می‌آیند.

enum class KTab(val title: String) { HOME("خانه"), SERVERS("سرورها"), TEST("تست سرعت"), SETTINGS("تنظیمات") }

/** اطلاعات خلاصه‌ی اشتراک برای نوار پایین صفحه‌ی اتصال (فقط عدد) */
data class SubUi(val gbLeft: String, val daysLeft: String, val status: String)

/** همه‌ی چیزهایی که صفحه‌ی اتصال نشان می‌دهد */
data class HomeUi(
    val vstate: VState,
    val hasAccount: Boolean,
    val loading: Boolean,
    val mode: KMode,
    /** نام سرور فعلی (روی نقشه، وقتی وصل است) */
    val serverName: String,
    val serverFlag: String,
    val livePing: Long?,
    val rx: Long,
    val tx: Long,
    val elapsedSec: Long?,
    /** مرحله‌ی فعلی هنگام اتصال (کوچک، فقط وقتی در حال اتصال است) */
    val tryStep: String?,
    val sub: SubUi?,
    val alert: RobotAlert?,
    val countries: List<Country>?,
    val pins: List<MapPin>,
    val userGeo: Geo?,
    val userIso: String?,
    /** یک پنل (گیمینگ/ضد فیلتر/برنامه‌ها) باز است؛ دکمه‌ی اصلی کاری نمی‌کند */
    val sheetOpen: Boolean,
    val smart: SmartPanelUi,
    val dns: DnsCardUi,
)

/** کارهایی که از صفحه‌ی اتصال انجام می‌شود */
class HomeActions(
    val toggle: () -> Unit = {},
    val tapMode: (KMode) -> Unit = {},
    val longMode: (KMode) -> Unit = {},
    val openAccount: () -> Unit = {},
    val alertAction: (String) -> Unit = {},
    val dismissAlert: () -> Unit = {},
    val retry: () -> Unit = {},
    val dns: DnsActions = DnsActions(),
)

// ================================================================== قاب اصلی، سربرگ و نوار پایین

/** قاب همه‌ی تب‌ها: سربرگ بالا، محتوا، نوار تب‌ها پایین */
@Composable
fun MainFrame(tab: KTab, onTab: (KTab) -> Unit, onRobot: () -> Unit, onAccount: () -> Unit, onEmergency: () -> Unit = {}, content: @Composable BoxScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        AppHeader(onRobot, onAccount, onEmergency)
        Box(Modifier.weight(1f), content = content)
        BottomBar(tab, onTab)
    }
}

/** سربرگ: لوگو، و سه دکمه‌ی کوچک (چت اضطراری، ربات، لینک اشتراک) */
@Composable
fun AppHeader(onRobot: () -> Unit, onAccount: () -> Unit, onEmergency: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Logo3D(34.dp)
        Spacer(Modifier.width(8.dp))
        BrandTitle(16.sp)
        Spacer(Modifier.weight(1f))
        Box(Modifier.guideTarget("emergency")) { HeaderIcon(K.Red, onEmergency) { emergencyChatIcon(it) } }
        Spacer(Modifier.width(7.dp))
        Box(Modifier.guideTarget("robot")) { HeaderIcon(Color(0xFF5CC8FF), onRobot) { botIcon(it) } }
        Spacer(Modifier.width(7.dp))
        Box(Modifier.guideTarget("account")) { HeaderIcon(K.Gold, onAccount) { linkIcon(it) } }
    }
}

/** دکمه‌ی کوچک مربعی شیشه‌ای سربرگ */
@Composable
fun HeaderIcon(tint: Color, onClick: () -> Unit, draw: DrawScope.(Color) -> Unit) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        Modifier.size(36.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0x14FFFFFF), Color(0x08FFFFFF))))
            .border(1.dp, Color(0x1FFFFFFF), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(20.dp)) { draw(tint) } }
}

/** حباب گفتگو با علامت تعجب قرمز (چت اضطراری) */
fun DrawScope.emergencyChatIcon(c: Color) {
    val w = size.width
    val st = Stroke(w * .09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val bubble = Path().apply {
        moveTo(w * .14f, w * .22f)
        lineTo(w * .86f, w * .22f)
        lineTo(w * .86f, w * .68f)
        lineTo(w * .40f, w * .68f)
        lineTo(w * .20f, w * .86f)
        lineTo(w * .22f, w * .68f)
        lineTo(w * .14f, w * .68f)
        close()
    }
    drawPath(bubble, c.copy(alpha = .22f))
    drawPath(bubble, c, style = st)
    drawLine(c, Offset(w * .5f, w * .32f), Offset(w * .5f, w * .48f), w * .1f, StrokeCap.Round)
    drawCircle(c, w * .055f, Offset(w * .5f, w * .59f))
}

/** ربات تلگرام (سر گرد با آنتن، چشم‌های روشن و گوش) */
fun DrawScope.botIcon(c: Color) {
    val w = size.width
    val st = Stroke(w * .085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawLine(c, Offset(w * .5f, w * .10f), Offset(w * .5f, w * .24f), w * .08f, StrokeCap.Round)
    drawCircle(c, w * .06f, Offset(w * .5f, w * .09f))
    drawRoundRect(c.copy(alpha = .16f), Offset(w * .16f, w * .26f), Size(w * .68f, w * .54f), CornerRadius(w * .2f))
    drawRoundRect(c, Offset(w * .16f, w * .26f), Size(w * .68f, w * .54f), CornerRadius(w * .2f), style = st)
    drawCircle(c, w * .075f, Offset(w * .37f, w * .50f))
    drawCircle(c, w * .075f, Offset(w * .63f, w * .50f))
    drawLine(c, Offset(w * .42f, w * .66f), Offset(w * .58f, w * .66f), w * .06f, StrokeCap.Round)
    drawLine(c, Offset(w * .06f, w * .46f), Offset(w * .06f, w * .62f), w * .08f, StrokeCap.Round)
    drawLine(c, Offset(w * .94f, w * .46f), Offset(w * .94f, w * .62f), w * .08f, StrokeCap.Round)
}

/** دو حلقه‌ی زنجیر (لینک اشتراک) */
fun DrawScope.linkIcon(c: Color) {
    val w = size.width
    val st = Stroke(w * .095f, cap = StrokeCap.Round)
    rotate(-45f) {
        drawRoundRect(c, Offset(w * .06f, w * .36f), Size(w * .48f, w * .28f), CornerRadius(w * .14f), style = st)
        drawRoundRect(c, Offset(w * .46f, w * .36f), Size(w * .48f, w * .28f), CornerRadius(w * .14f), style = st)
    }
}

@Composable
fun BottomBar(current: KTab, onSelect: (KTab) -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(K.Surface.copy(alpha = .98f), K.Panel)))
            .border(1.dp, Brush.horizontalGradient(listOf(K.Gold.copy(alpha = .10f), K.Gold.copy(alpha = .45f), K.Gold.copy(alpha = .10f))), shape)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KTab.entries.forEachIndexed { i, t ->
            val sel = t == current
            val c = if (sel) K.Gold else K.Sub
            val ids = listOf("nav_home", "nav_servers", "nav_test", "nav_settings")
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                    .background(if (sel) K.Gold.copy(alpha = .12f) else Color.Transparent)
                    .clickable { onSelect(t) }
                    .guideTarget(ids[i])
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Canvas(Modifier.size(22.dp)) { navIcon(i, c) }
                Spacer(Modifier.height(3.dp))
                T(t.title, size = 11.sp, color = c, weight = if (sel) FontWeight.Black else FontWeight.Bold)
            }
        }
    }
}

// ================================================================== صفحه‌ی اتصال

@Composable
fun HomeContent(ui: HomeUi, act: HomeActions, modifier: Modifier = Modifier) {
    val mc = modeColor(ui.mode)
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // نقشه و دکمه‌ی گرد اتصال که روی پایین نقشه نشسته
            Box(Modifier.fillMaxWidth().height(262.dp)) {
                MapCard(ui, mc, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(196.dp).guideTarget("map"))
                Box(Modifier.align(Alignment.BottomCenter).guideTarget("connect")) {
                    OrbButton(ui, mc, enabled = ui.hasAccount && !ui.sheetOpen, onClick = act.toggle)
                }
            }
            // فقط هنگام اتصال یک خط کوچک (بدون نام سرور و پروتکل زیر دکمه)
            AnimatedVisibility(ui.vstate == VState.CONNECTING && ui.tryStep != null && !ui.smart.open) {
                T(ui.tryStep ?: "", size = 11.sp, color = K.Sub, weight = FontWeight.Normal, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(10.dp))

            if (!ui.hasAccount) {
                if (ui.loading) Spinner(28.dp) else GoldButton("تلاش دوباره", Modifier.fillMaxWidth(), onClick = act.retry)
            } else {
                ModeRow(ui.mode, enabled = ui.vstate != VState.CONNECTING || ui.mode == KMode.GAME, modifier = Modifier.guideTarget("protocols"), onTap = act.tapMode, onLong = act.longMode)
                ModeHint(ui.mode)
                AnimatedVisibility(ui.smart.open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    SmartPanel(ui.smart, Modifier.padding(top = 10.dp))
                }
                if (ui.mode == KMode.DNS) DnsCard(ui.dns, ui.vstate, act.dns, Modifier.padding(top = 10.dp))
            }

            AnimatedVisibility(visible = ui.alert != null) {
                ui.alert?.let { al ->
                    RobotBanner(al, Modifier.padding(top = 10.dp), onAction = act.alertAction, onClose = act.dismissAlert)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        ui.sub?.let { UsageStrip(it, Modifier.padding(bottom = 4.dp).guideTarget("sub"), act.openAccount) }
    }
}

/** نقشه با وضعیت امنیت؛ وقتی وصل است سرعت لحظه‌ای کوچک بالای نقشه */
@Composable
fun MapCard(ui: HomeUi, accent: Color, modifier: Modifier = Modifier) {
    val on = ui.vstate == VState.ON
    val connecting = ui.vstate == VState.CONNECTING
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier.clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0x0DFFFFFF), Color(0x05FFFFFF))))
            .border(1.dp, Color(0x14FFFFFF), shape),
    ) {
        WorldMap(ui.countries, ui.pins, ui.userGeo, ui.userIso, on, Modifier.fillMaxSize(), accent = accent)
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(K.Ink.copy(alpha = .55f), Color.Transparent)))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (txt, c) = when {
                on && ui.mode == KMode.DNS -> "رفع تحریم فعال" to K.Green
                on -> "محافظت‌شده" to K.Green
                connecting -> "در حال اتصال…" to accent
                else -> "محافظت‌نشده" to K.Red
            }
            StatusPill(txt, c, pulse = on || connecting)
            Spacer(Modifier.weight(1f))
            if (on) {
                MiniIcon("down", K.Green, 10.dp)
                Spacer(Modifier.width(3.dp))
                T("${formatBytes(ui.rx)}/s", size = 10.sp, color = K.Text, font = Poppins, weight = FontWeight.SemiBold, ltr = true)
                Spacer(Modifier.width(8.dp))
                MiniIcon("up", K.Accent, 10.dp)
                Spacer(Modifier.width(3.dp))
                T("${formatBytes(ui.tx)}/s", size = 10.sp, color = K.Text, font = Poppins, weight = FontWeight.SemiBold, ltr = true)
                ui.livePing?.let {
                    Spacer(Modifier.width(8.dp))
                    PingChip(it)
                }
            } else {
                flagEmoji(ui.userIso)?.let { T(it, size = 13.sp, weight = FontWeight.Normal); Spacer(Modifier.width(5.dp)) }
                T("موقعیت شما", size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, c: Color, pulse: Boolean) {
    val t = rememberInfiniteTransition(label = "sp")
    val p by t.animateFloat(.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape).background(c.copy(alpha = .14f)).border(1.dp, c.copy(alpha = .6f), shape)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c.copy(alpha = if (pulse) p else 1f)))
        Spacer(Modifier.width(5.dp))
        T(text, size = 10.sp, color = c, weight = FontWeight.Black)
    }
}

/**
 * دکمه‌ی گرد اتصال. رنگش رنگ حالت فعال است؛ در حالت گیمینگ حلقه‌ی نئونی چرخان و تپنده دارد.
 * متن داخل دکمه: «وصل شو» / «در حال اتصال…» / «وصل است» + زمان اتصال.
 */
@Composable
fun OrbButton(ui: HomeUi, color: Color, enabled: Boolean, diameter: Dp = 124.dp, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "spin")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "pulse")
    val breathe by t.animateFloat(.6f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "br")
    val on = ui.vstate == VState.ON
    val connecting = ui.vstate == VState.CONNECTING
    val gaming = ui.mode == KMode.GAME
    val neon = Color(0xFF39FF9A)

    Box(
        Modifier.size(diameter + 28.dp).alpha(if (enabled) 1f else .55f).clickable(
            enabled = enabled,
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val outer = this.size.minDimension / 2
            val r = outer * (diameter.value / (diameter.value + 28f))
            // هاله
            val glowA = (if (on) .45f else if (gaming) .38f else .22f) * breathe
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = glowA), Color.Transparent), c, outer), outer, c)
            if (gaming) {
                // حلقه‌ی نئونی چرخان (دو رنگ) + موج تپنده
                rotate(spin, c) {
                    val br = Brush.sweepGradient(listOf(neon, color, Color(0xFF00E5FF), Color.Transparent, neon), c)
                    for ((m, a) in listOf(3.2f to .18f, 2f to .35f, 1f to 1f)) {
                        drawCircle(br, r * 1.08f, c, alpha = a, style = Stroke(3.dp.toPx() * m))
                    }
                }
                drawCircle(neon.copy(alpha = (1f - pulse) * .55f), r * (1.04f + pulse * .2f), c, style = Stroke(2.dp.toPx()))
            } else {
                drawCircle(color.copy(alpha = .14f), r * 1.08f, c, style = Stroke(7.dp.toPx()))
                if (connecting) {
                    rotate(spin, c) {
                        drawArc(
                            Brush.sweepGradient(0f to Color.Transparent, .3f to color, .31f to Color.Transparent, 1f to Color.Transparent, center = c),
                            0f, 110f, false, Offset(c.x - r * 1.08f, c.y - r * 1.08f), Size(r * 2.16f, r * 2.16f),
                            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                } else if (on) {
                    drawCircle(color.copy(alpha = (1f - pulse) * .5f), r * (1.02f + pulse * .18f), c, style = Stroke(1.5.dp.toPx()))
                }
            }
            // دیسک شیشه‌ای رنگی
            val top = color.copy(alpha = if (on) .95f else .60f)
            val deep = Color(
                red = color.red * .35f, green = color.green * .35f, blue = color.blue * .35f, alpha = 1f,
            )
            drawCircle(Brush.radialGradient(listOf(top, deep), Offset(c.x, c.y - r * .35f), r * 1.25f), r, c)
            drawCircle(Brush.verticalGradient(listOf(Color.White.copy(alpha = .16f), Color.Transparent), startY = c.y - r, endY = c.y), r, c)
            drawCircle(Color.White.copy(alpha = .18f), r, c, style = Stroke(1.5.dp.toPx()))
            // نقطه‌های دور دکمه (مثل صفحه‌ی ساعت)
            for (i in 0 until 36) {
                val ang = Math.toRadians(i * 10.0 - 90)
                val r1 = r * .86f
                val p = Offset(c.x + r1 * cos(ang).toFloat(), c.y + r1 * sin(ang).toFloat())
                drawCircle(Color.White.copy(alpha = if (i % 3 == 0) .35f else .14f), 1.1.dp.toPx(), p)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val (title, sub) = when (ui.vstate) {
                VState.ON -> (if (ui.mode == KMode.DNS) "روشن" else "وصل است") to (if (ui.mode == KMode.DNS) "DNS" else elapsed(ui.elapsedSec))
                VState.CONNECTING -> "در حال اتصال" to "لمس: لغو"
                VState.OFF -> "وصل شو" to ui.mode.title
            }
            T(title, size = 16.sp, color = Color.White, weight = FontWeight.Black, shadow = glow(color, 16f))
            if (sub.isNotBlank()) {
                val latin = isLatin(sub)
                T(sub, size = 10.5.sp, color = Color.White.copy(alpha = .82f), font = if (latin) Poppins else Vazir, weight = FontWeight.SemiBold, ltr = latin)
            }
        }
    }
}

private fun elapsed(s: Long?): String {
    val v = s ?: 0
    return String.format(java.util.Locale.US, "%02d:%02d:%02d", v / 3600, v / 60 % 60, v % 60)
}

/** نوار کوچک مصرف: گیگ باقی‌مانده · روز باقی‌مانده · وضعیت (فقط عدد، بدون نوار پیشرفت) */
@Composable
fun UsageStrip(s: SubUi, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val active = isActive(s.status)
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier.fillMaxWidth().height(40.dp).clip(shape)
            .background(Color(0x0DFFFFFF)).border(1.dp, Color(0x16FFFFFF), shape)
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UsageCell(s.gbLeft, "گیگ", Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(16.dp).background(Color(0x1AFFFFFF)))
        UsageCell(s.daysLeft, "روز", Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(16.dp).background(Color(0x1AFFFFFF)))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            val c = if (active) K.Green else K.Red
            Box(Modifier.size(6.dp).clip(CircleShape).background(c))
            Spacer(Modifier.width(5.dp))
            T(faStatus(s.status), size = 12.sp, color = c, weight = FontWeight.Bold)
        }
    }
}

@Composable
private fun UsageCell(value: String, unit: String, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val latin = isLatin(value)
        T(value, size = 13.sp, color = K.Text, font = if (latin) Poppins else Vazir, weight = FontWeight.Bold, ltr = latin)
        Spacer(Modifier.width(4.dp))
        T(unit, size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
    }
}

/** تیک انتخاب (دایره‌ی سبز) */
@Composable
fun CheckMark(on: Boolean, size: Dp = 22.dp, color: Color = K.Green) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (on) color else Color.Transparent)
            .border(1.5.dp, if (on) color else K.Sub.copy(alpha = .4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Canvas(Modifier.size(size * .55f)) {
            val w = this.size.width
            drawLine(K.Ink, Offset(w * .12f, w * .52f), Offset(w * .40f, w * .80f), w * .16f, StrokeCap.Round)
            drawLine(K.Ink, Offset(w * .40f, w * .80f), Offset(w * .90f, w * .22f), w * .16f, StrokeCap.Round)
        }
    }
}

/** یک خط توضیح زیر انتخاب پروتکل: تانل است یا مستقیم، و کِی به درد می‌خورد */
fun protoHint(p: String) = when (p) {
    "amneziawg" -> "تانل · از سرور ایران رد می‌شود و فیلتر را دور می‌زند"
    "amneziawg-direct" -> "مستقیم · بدون تانل؛ وقتی تانل‌ها وصل نمی‌شوند همین را بزنید"
    "wireguard" -> "تانل · سریع و مناسب گیم"
    "hysteria2" -> "تانل · پرسرعت روی اینترنت ضعیف"
    "xray" -> "Xray · برای فیلتر شدید، سخت‌ترین برای مسدود شدن"
    else -> protoDesc(p)
}
