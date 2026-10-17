package cafe.kankipro.vpn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

// صفحه‌ی اتصال (خانه) با طراحی طلایی. فقط ظاهر است؛ داده‌ها از AppState در Screens.kt می‌آیند.

enum class KTab(val title: String) { HOME("خانه"), SERVERS("سرورها"), TEST("تست سرعت"), SETTINGS("تنظیمات") }

/** اطلاعات خلاصه‌ی اشتراک برای نوار پایین صفحه‌ی اتصال (فقط عدد) */
data class SubUi(val gbLeft: String, val daysLeft: String, val status: String)

/** همه‌ی چیزهایی که صفحه‌ی اتصال نشان می‌دهد */
data class HomeUi(
    val vstate: VState,
    val hasAccount: Boolean,
    val loading: Boolean,
    val mode: KMode,
    /** پینگ زنده و چند پینگ آخر (برای نمودار کوچک) */
    val livePing: Long?,
    val pingHist: List<Long>,
    /** سرعت لحظه‌ای (بایت بر ثانیه) */
    val rx: Long,
    val tx: Long,
    val elapsedSec: Long?,
    val sub: SubUi?,
    val alert: RobotAlert?,
    val countries: List<Country>?,
    val pins: List<MapPin>,
    val userGeo: Geo?,
    val userIso: String?,
    /** کشوری که چیپ بالای نقشه نشان می‌دهد: کشور کاربر، یا وقتی وصل است کشور IP خروجی */
    val locationIso: String?,
    val locationIsExit: Boolean = false,
    /** یک پنل (گیمینگ/برنامه‌ها) باز است؛ دکمه‌ی اصلی کاری نمی‌کند */
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

/** سربرگ: لوگوی سپر K و KANKI VPN در یک طرف، سه دکمه‌ی کوچک مربعی (لینک، ربات، گفتگو) در طرف دیگر */
@Composable
fun AppHeader(onRobot: () -> Unit, onAccount: () -> Unit, onEmergency: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Logo3D(30.dp)
        Spacer(Modifier.width(8.dp))
        BrandTitle(15.sp)
        Spacer(Modifier.weight(1f))
        Box(Modifier.guideTarget("emergency")) { HeaderIcon(K.Gold, onEmergency) { emergencyChatIcon(it) } }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.guideTarget("robot")) { HeaderIcon(K.Gold, onRobot) { drawRobot(body = false) } }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.guideTarget("account")) { HeaderIcon(K.Gold, onAccount) { linkIcon(it) } }
    }
}

/** دکمه‌ی کوچک مربعی شیشه‌ای سربرگ با لبه‌ی طلایی */
@Composable
fun HeaderIcon(tint: Color, onClick: () -> Unit, draw: DrawScope.(Color) -> Unit) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        Modifier.size(38.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(K.Gold.copy(alpha = .07f), Color(0x05FFFFFF))))
            .border(1.dp, K.Gold.copy(alpha = .38f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(20.dp)) { draw(tint) } }
}

/** حباب گفتگو با علامت تعجب (چت اضطراری) */
fun DrawScope.emergencyChatIcon(c: Color) {
    val w = size.width
    val st = Stroke(w * .085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val bubble = Path().apply {
        moveTo(w * .14f, w * .20f)
        lineTo(w * .86f, w * .20f)
        lineTo(w * .86f, w * .68f)
        lineTo(w * .40f, w * .68f)
        lineTo(w * .20f, w * .86f)
        lineTo(w * .22f, w * .68f)
        lineTo(w * .14f, w * .68f)
        close()
    }
    drawPath(bubble, c, style = st)
    drawLine(c, Offset(w * .5f, w * .32f), Offset(w * .5f, w * .47f), w * .09f, StrokeCap.Round)
    drawCircle(c, w * .05f, Offset(w * .5f, w * .58f))
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
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(K.Surface.copy(alpha = .96f), K.Panel)))
            .border(1.dp, K.Gold.copy(alpha = .22f), shape)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        KTab.entries.forEachIndexed { i, t ->
            val sel = t == current
            val c = if (sel) K.Gold else K.Sub
            val ids = listOf("nav_home", "nav_servers", "nav_test", "nav_settings")
            val ts = RoundedCornerShape(16.dp)
            Column(
                Modifier.weight(1f).clip(ts)
                    .background(if (sel) K.Gold.copy(alpha = .08f) else Color.Transparent)
                    .border(if (sel) 1.2.dp else 0.dp, if (sel) K.Gold.copy(alpha = .8f) else Color.Transparent, ts)
                    .clickable { onSelect(t) }
                    .guideTarget(ids[i])
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Canvas(Modifier.size(22.dp)) { navIcon(i, c) }
                Spacer(Modifier.height(4.dp))
                T(t.title, size = 11.sp, color = c, weight = if (sel) FontWeight.Black else FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

// ================================================================== صفحه‌ی اتصال

@Composable
fun HomeContent(ui: HomeUi, act: HomeActions, modifier: Modifier = Modifier) {
    val mc = modeColor(ui.mode)
    val on = ui.vstate == VState.ON
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
            LocationChip(ui.locationIso, ui.locationIsExit)
        }
        // نقشه‌ی بزرگ بدون قاب؛ دکمه‌ی گرد اتصال روی لبه‌ی محوشده‌ی پایین نقشه
        Box(Modifier.fillMaxWidth().height(334.dp)) {
            WorldMap(
                ui.countries, ui.pins, ui.userGeo, ui.userIso, on,
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(276.dp).guideTarget("map"),
                accent = if (ui.vstate == VState.OFF) K.Gold else mc,
            )
            Box(Modifier.align(Alignment.BottomCenter).guideTarget("connect")) {
                OrbButton(ui, mc, enabled = ui.hasAccount && !ui.sheetOpen, onClick = act.toggle)
            }
        }
        Spacer(Modifier.height(10.dp))

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!ui.hasAccount) {
                if (ui.loading) Spinner(28.dp) else GoldButton("تلاش دوباره", Modifier.fillMaxWidth(), onClick = act.retry)
            } else {
                ModeRow(ui.mode, enabled = ui.vstate != VState.CONNECTING || ui.mode == KMode.GAME, modifier = Modifier.guideTarget("protocols"), onTap = act.tapMode, onLong = act.longMode)
                Spacer(Modifier.height(8.dp))
                ModeHint(ui.mode)
                AnimatedVisibility(ui.smart.open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    SmartPanel(ui.smart, Modifier.padding(top = 8.dp))
                }
                if (ui.mode == KMode.DNS) DnsCard(ui.dns, ui.vstate, act.dns, Modifier.padding(top = 8.dp))
            }

            AnimatedVisibility(visible = ui.alert != null) {
                ui.alert?.let { al ->
                    RobotBanner(al, Modifier.padding(top = 8.dp), onAction = act.alertAction, onClose = act.dismissAlert)
                }
            }
            if (ui.hasAccount) {
                Spacer(Modifier.height(8.dp))
                StatsRow(ui)
            }
            ui.sub?.let {
                Spacer(Modifier.height(8.dp))
                UsageStrip(it, Modifier.guideTarget("sub"), act.openAccount)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** «موقعیت شما : ایران» بالای نقشه؛ وقتی وصل است «IP شما : آلمان» (کشور IP خروجی) */
@Composable
private fun LocationChip(iso: String?, on: Boolean) {
    val c = if (on) K.Green else K.Gold
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape).background(c.copy(alpha = .07f)).border(1.dp, c.copy(alpha = .5f), shape)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(c, 6.dp)
        Spacer(Modifier.width(7.dp))
        T(tr(if (on) "IP شما" else "موقعیت شما") + " : " + (countryName(iso) ?: iso ?: "—"), size = 11.sp, color = K.Text, weight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.width(7.dp))
        Dot(c.copy(alpha = .5f), 4.dp)
    }
}

/**
 * دکمه‌ی گرد اتصال: دیسک شیشه‌ای تیره با حلقه‌ی نئونی. هنگام اتصال و وقتی وصل است یک قوس نورانی
 * دورش می‌چرخد (هنگام اتصال تندتر). رنگ حلقه و نور وقتی وصل است رنگ همان حالت است:
 * هوشمند طلایی، گیمینگ سبز نئونی، ضد فیلتر آبی، DNS بنفش.
 */
@Composable
fun OrbButton(ui: HomeUi, color: Color, enabled: Boolean, diameter: Dp = 156.dp, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "orb")
    val spinFast by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "sf")
    val spinSlow by t.animateFloat(0f, 360f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "ss")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "pulse")
    val breathe by t.animateFloat(.6f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "br")
    val on = ui.vstate == VState.ON
    val connecting = ui.vstate == VState.CONNECTING
    val ring by animateColorAsState(if (on || connecting) color else K.Gold, tween(500), label = "ring")
    val pad = 34.dp

    Box(
        Modifier.size(diameter + pad).alpha(if (enabled) 1f else .55f).clickable(
            enabled = enabled,
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val outer = this.size.minDimension / 2
            val r = outer * (diameter.value / (diameter.value + pad.value))
            val sw = 2.6.dp.toPx()
            // هاله
            val glowA = (if (on) .40f else if (connecting) .30f else .16f) * breathe
            drawCircle(Brush.radialGradient(listOf(ring.copy(alpha = glowA), Color.Transparent), c, outer), outer, c)
            // دیسک شیشه‌ای تیره
            drawCircle(Brush.radialGradient(listOf(K.Surface.copy(alpha = 1f), K.Ink), c, r), r, c)
            drawCircle(Brush.verticalGradient(listOf(Color.White.copy(alpha = .08f), Color.Transparent), startY = c.y - r, endY = c.y), r, c)
            drawCircle(ring.copy(alpha = .16f), r * .84f, c, style = Stroke(1.dp.toPx()))
            // حلقه‌ی نئونی
            neonRing(ring, c, r, sw, if (on) 1f else if (connecting) .7f else .5f)
            // قوس چرخان
            if (connecting || on) {
                val head = if (connecting) spinFast else spinSlow
                neonComet(ring, head - 90f, if (connecting) 130f else 85f, c, r, sw * 1.1f)
            }
            if (on) drawCircle(ring.copy(alpha = (1f - pulse) * .45f), r * (1.03f + pulse * .15f), c, style = Stroke(1.5.dp.toPx()))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val dnsOn = ui.mode == KMode.DNS
            val (title, sub) = when (ui.vstate) {
                VState.ON -> (if (dnsOn) "روشن است" else "وصل است") to
                    (if (dnsOn) (ui.dns.activeRoute ?: "DNS") else tr("پیوسته") + " · " + elapsed(ui.elapsedSec))
                VState.CONNECTING -> "در حال اتصال" to "لمس: لغو"
                VState.OFF -> "وصل شو" to ui.mode.title
            }
            T(title, size = if (on) 21.sp else 19.sp, color = if (on) Color.White else K.GoldLight, weight = FontWeight.Black, shadow = glow(ring, 14f))
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                T(sub, size = 11.sp, color = K.Text.copy(alpha = .8f), weight = FontWeight.Bold)
            }
        }
    }
}

private fun elapsed(s: Long?): String {
    val v = s ?: 0
    return String.format(java.util.Locale.US, "%02d:%02d:%02d", v / 3600, v / 60 % 60, v % 60)
}

/** کارت پینگ (با نمودار زنده) و کارت دانلود/آپلود */
@Composable
private fun StatsRow(ui: HomeUi) {
    val on = ui.vstate == VState.ON
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("پینگ", size = 12.sp, color = K.Sub, weight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                val p = if (on) ui.livePing else null
                T(p?.let { "$it ms" } ?: "– ms", size = 16.sp, color = if (p != null) pingColor(p) else K.Sub, font = Poppins, weight = FontWeight.Bold, ltr = true)
            }
            Spacer(Modifier.height(6.dp))
            Sparkline(if (on) ui.pingHist else emptyList(), Modifier.fillMaxWidth().height(30.dp))
        }
        StatCard(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth()) {
                SpeedCell("down", "دانلود", if (on) ui.rx else null, Modifier.weight(1f))
                SpeedCell("up", "آپلود", if (on) ui.tx else null, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.height(88.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(K.Surface, K.Surface2)))
            .border(1.dp, K.Gold.copy(alpha = .22f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
private fun SpeedCell(icon: String, label: String, bytesPerSec: Long?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniIcon(icon, K.Gold, 10.dp)
            Spacer(Modifier.width(4.dp))
            T(label, size = 11.sp, color = K.Sub, weight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        val v = bytesPerSec?.let { String.format(java.util.Locale.US, "%.1f", it * 8 / 1_000_000.0) } ?: "–"
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.Bottom) {
                T(v, size = 16.sp, color = K.Text, font = Poppins, weight = FontWeight.Bold, ltr = true)
                Spacer(Modifier.width(3.dp))
                T("Mbps", size = 9.5.sp, color = K.Sub, font = Poppins, weight = FontWeight.SemiBold, ltr = true, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

/** نمودار خطی کوچک پینگ‌های اخیر (طلایی با کمی درخشش) */
@Composable
private fun Sparkline(values: List<Long>, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (values.size < 2) {
            drawLine(K.Gold.copy(alpha = .25f), Offset(0f, h * .6f), Offset(w, h * .6f), 1.5.dp.toPx(), StrokeCap.Round)
            return@Canvas
        }
        val lo = values.min().toFloat()
        val hi = values.max().toFloat()
        val span = (hi - lo).coerceAtLeast(8f)
        val step = w / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = h * .9f - (v - lo) / span * h * .8f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, K.Gold.copy(alpha = .18f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, K.Gold, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** نوار باریک وضعیت اشتراک: گیگ باقی‌مانده · روز باقی‌مانده · وضعیت (فقط عدد) */
@Composable
fun UsageStrip(s: SubUi, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val active = isActive(s.status)
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().height(46.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(K.Surface, K.Surface2))).border(1.dp, K.Gold.copy(alpha = .22f), shape)
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UsageCell(s.gbLeft, "گیگ", Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(18.dp).background(K.Gold.copy(alpha = .18f)))
        UsageCell(s.daysLeft, "روز", Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(18.dp).background(K.Gold.copy(alpha = .18f)))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            val c = if (active) K.Green else K.Red
            Dot(c, 7.dp)
            Spacer(Modifier.width(6.dp))
            T(faStatus(s.status), size = 13.sp, color = c, weight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun UsageCell(value: String, unit: String, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val v = faDigits(value)
        val latin = isLatin(v)
        T(v, size = 15.sp, color = K.Text, font = if (latin) Poppins else Vazir, weight = FontWeight.Black, ltr = latin)
        Spacer(Modifier.width(5.dp))
        T(unit, size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
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
