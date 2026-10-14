package cafe.kankipro.vpn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// صفحه‌ی اتصال (خانه) با طراحی جدید. فقط ظاهر است؛ داده‌ها از AppState در Screens.kt می‌آیند.

enum class KTab(val title: String) { HOME("خانه"), SERVERS("سرورها"), TEST("تست سرعت"), SETTINGS("تنظیمات") }

/** اطلاعات خلاصه‌ی اشتراک برای نوار پایین صفحه‌ی اتصال */
data class SubUi(val dataLeft: String, val timeLeft: String, val status: String, val leftFraction: Float?)

/** یک سرور در فهرست انتخاب سریع */
data class NodeUi(val id: String, val name: String, val flag: String, val protocols: Int, val ping: Long?, val selected: Boolean)

/** یک ردیف نتیجه‌ی انتخاب هوشمند */
data class SmartUi(val flag: String, val title: String, val ping: Long, val jitter: Long)

/** همه‌ی چیزهایی که صفحه‌ی اتصال نشان می‌دهد */
data class HomeUi(
    val vstate: VState,
    val hasAccount: Boolean,
    val loading: Boolean,
    val serverName: String,
    val serverFlag: String,
    val serverSub: String,
    val serverPing: Long?,
    val livePing: Long?,
    val rx: Long,
    val tx: Long,
    val history: List<Float>,
    val elapsedSec: Long?,
    val protos: List<SegItem>,
    val selectedProto: String,
    val sub: SubUi?,
    val smartBusy: Boolean,
    val smartOpen: Boolean,
    val smartRows: List<SmartUi>,
    val alert: RobotAlert?,
    val countries: List<Country>?,
    val pins: List<MapPin>,
    val userGeo: Geo?,
    val userIso: String?,
)

/** کارهایی که از صفحه‌ی اتصال انجام می‌شود */
class HomeActions(
    val toggle: () -> Unit = {},
    val openServers: () -> Unit = {},
    val smart: () -> Unit = {},
    val closeSmart: () -> Unit = {},
    val pickProto: (String) -> Unit = {},
    val openAccount: () -> Unit = {},
    val alertAction: (String) -> Unit = {},
    val dismissAlert: () -> Unit = {},
    val retry: () -> Unit = {},
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

@Composable
fun AppHeader(onRobot: () -> Unit, onAccount: () -> Unit, onEmergency: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Logo3D(36.dp)
        Spacer(Modifier.width(8.dp))
        BrandTitle(19.sp)
        Spacer(Modifier.weight(1f))
        // ارتباط اضطراری (فقط وقتی اینترنت ملی شده)
        Box(Modifier.guideTarget("emergency")) { EmergencyButton(36.dp, onClick = onEmergency) }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onRobot).guideTarget("robot"),
            contentAlignment = Alignment.Center,
        ) { RobotFace(40.dp) }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.guideTarget("account")) {
            RoundIcon(42.dp, onClick = onAccount) { Canvas(Modifier.size(20.dp)) { accountIcon(K.Gold) } }
        }
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
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MapCard(ui, Modifier.guideTarget("map"))
        Spacer(Modifier.height(6.dp))
        ConnectArea(ui, act)

        AnimatedVisibility(visible = ui.alert != null) {
            ui.alert?.let { al ->
                RobotBanner(al, Modifier.padding(top = 10.dp), onAction = act.alertAction, onClose = act.dismissAlert)
            }
        }
        Spacer(Modifier.height(12.dp))

        if (!ui.hasAccount) {
            if (ui.loading) Spinner(28.dp) else GoldButton("تلاش دوباره", Modifier.fillMaxWidth(), onClick = act.retry)
        } else {
            ControlCard(ui, act)
            Spacer(Modifier.height(10.dp))
            ui.sub?.let { SubStrip(it, Modifier.guideTarget("sub"), act.openAccount) }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** نقشه با وضعیت امنیت و آمار زنده */
@Composable
fun MapCard(ui: HomeUi, modifier: Modifier = Modifier) {
    val on = ui.vstate == VState.ON
    val connecting = ui.vstate == VState.CONNECTING
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier.fillMaxWidth().height(206.dp).clip(shape)
            .background(K.Panel)
            .border(1.dp, (if (on) K.Green else K.Gold).copy(alpha = .28f), shape),
    ) {
        WorldMap(ui.countries, ui.pins, ui.userGeo, ui.userIso, on, Modifier.fillMaxSize())
        // بالا: وضعیت امنیت
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(K.Panel.copy(alpha = .85f), Color.Transparent)))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (txt, c) = when {
                on -> "محافظت‌شده" to K.Green
                connecting -> "در حال اتصال…" to K.Gold
                else -> "محافظت‌نشده" to K.Red
            }
            StatusPill(txt, c, pulse = on || connecting)
            Spacer(Modifier.weight(1f))
            if (on) {
                T(ui.serverFlag, size = 14.sp, weight = FontWeight.Normal)
                Spacer(Modifier.width(5.dp))
                AutoText(ui.serverName, size = 12.sp, color = K.Text)
            } else {
                flagEmoji(ui.userIso)?.let { T(it, size = 14.sp, weight = FontWeight.Normal); Spacer(Modifier.width(5.dp)) }
                T("موقعیت شما", size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
            }
        }
        // پایین: آمار زنده یا پیام وضعیت
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, K.Panel.copy(alpha = .92f), K.Panel)))
                .padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 10.dp),
        ) {
            if (on) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveStat("down", "دانلود", "${formatBytes(ui.rx)}/s", K.Green, Modifier.weight(1f))
                    VDivider(26.dp)
                    LiveStat("up", "آپلود", "${formatBytes(ui.tx)}/s", K.Accent, Modifier.weight(1f))
                    VDivider(26.dp)
                    LiveStat("ping", "پینگ", ui.livePing?.let { "$it ms" } ?: "…", ui.livePing?.let { pingColor(it) } ?: K.Sub, Modifier.weight(1f))
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    if (connecting) { Spinner(13.dp, K.Gold); Spacer(Modifier.width(8.dp)) }
                    else { MiniIcon("unlock", K.Red, 14.dp); Spacer(Modifier.width(6.dp)) }
                    T(
                        if (connecting) "در حال برقراری اتصال امن…" else "اینترنت شما الان محافظت نمی‌شود",
                        size = 12.sp, color = if (connecting) K.Gold else K.Sub, weight = FontWeight.Bold,
                    )
                }
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
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c.copy(alpha = if (pulse) p else 1f)))
        Spacer(Modifier.width(6.dp))
        T(text, size = 11.sp, color = c, weight = FontWeight.Black)
    }
}

@Composable
private fun LiveStat(icon: String, label: String, value: String, c: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniIcon(icon, c, 11.dp)
            Spacer(Modifier.width(4.dp))
            T(label, size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
        }
        T(value, size = 13.sp, color = K.Text, font = Poppins, weight = FontWeight.Bold, ltr = true)
    }
}

/** دکمه‌ی اتصال با عنوان وضعیت و زمان اتصال */
@Composable
private fun ConnectArea(ui: HomeUi, act: HomeActions) {
    val (label, color) = when (ui.vstate) {
        VState.ON -> "متصل" to K.Green
        VState.CONNECTING -> "در حال اتصال…" to K.Gold
        VState.OFF -> "آماده‌ی اتصال" to K.Text
    }
    Box(Modifier.guideTarget("connect")) {
        ConnectButton(ui.vstate, 150.dp) { if (ui.hasAccount) act.toggle() }
    }
    T(label, size = 20.sp, color = color, weight = FontWeight.Black, shadow = glow(color, 20f))
    Spacer(Modifier.height(2.dp))
    when (ui.vstate) {
        VState.ON -> {
            val s = ui.elapsedSec ?: 0
            T(
                String.format(java.util.Locale.US, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60),
                size = 17.sp, color = K.Text, font = Poppins, weight = FontWeight.SemiBold, ltr = true,
            )
        }
        VState.CONNECTING -> T("${ui.serverFlag}  ${ui.serverName} · ${ui.serverSub}", size = 13.sp, color = K.Sub, weight = FontWeight.Normal)
        VState.OFF -> T("برای اتصال، دکمه را لمس کنید", size = 13.sp, color = K.Sub, weight = FontWeight.Normal)
    }
}

/** کارت انتخاب سرور و پروتکل */
@Composable
private fun ControlCard(ui: HomeUi, act: HomeActions) {
    val busy = ui.vstate == VState.CONNECTING
    KCard(Modifier.fillMaxWidth(), padding = 12.dp) {
        // ردیف سرور: پرچم، اسم، پروتکل، پینگ، انتخاب هوشمند
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .clickable(enabled = !busy, onClick = act.openServers)
                .guideTarget("server")
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlagBadge(ui.serverFlag, 46.dp, ring = if (ui.vstate == VState.ON) K.Green.copy(alpha = .8f) else K.Gold.copy(alpha = .55f))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                T("کشور / سرور", size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
                AutoText(ui.serverName, size = 15.sp, color = K.Text, weight = FontWeight.Bold)
                T(ui.serverSub, size = 11.sp, color = K.Gold, weight = FontWeight.Bold)
            }
            PingChip(ui.serverPing)
            Spacer(Modifier.width(8.dp))
            RoundIcon(40.dp, tint = K.Gold, filled = true, onClick = { if (!ui.smartBusy && !busy) act.smart() }) {
                if (ui.smartBusy) Spinner(18.dp) else MiniIcon("spark", K.Gold, 18.dp)
            }
            Spacer(Modifier.width(6.dp))
            // دکمه‌ی واضح برای عوض کردن کشور
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Chevron(K.Gold)
                T("تغییر", size = 9.sp, color = K.Gold, weight = FontWeight.Black)
            }
        }
        // نتیجه‌ی انتخاب هوشمند
        AnimatedVisibility(visible = ui.smartOpen && ui.smartRows.isNotEmpty()) {
            Column(
                Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(K.Panel.copy(alpha = .8f)).clickable(onClick = act.closeSmart)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                ui.smartRows.forEachIndexed { i, r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (i == 0) MiniIcon("crown", K.Green, 13.dp) else Spacer(Modifier.width(13.dp))
                        Spacer(Modifier.width(6.dp))
                        T(r.flag, size = 14.sp, weight = FontWeight.Normal)
                        Spacer(Modifier.width(6.dp))
                        T(r.title, size = 11.sp, font = Poppins, weight = FontWeight.Bold, color = if (i == 0) K.Green else K.Text, ltr = true)
                        Spacer(Modifier.weight(1f))
                        T("±${r.jitter}ms", size = 10.sp, font = Poppins, color = K.Sub, ltr = true)
                        Spacer(Modifier.width(6.dp))
                        PingChip(r.ping)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        T("نوع اتصال", size = 11.sp, color = K.Sub, weight = FontWeight.Normal, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        Segmented(ui.protos, ui.selectedProto, Modifier.guideTarget("protocols"), enabled = !busy, onSelect = act.pickProto)
        Spacer(Modifier.height(6.dp))
        T(protoHint(ui.selectedProto), size = 11.sp, color = if (ui.selectedProto == "amneziawg-direct") K.Gold else K.Sub, weight = FontWeight.Normal, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** نوار باریک اشتراک: حجم، زمان و وضعیت + نوار حجم باقی‌مانده */
@Composable
fun SubStrip(s: SubUi, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val active = isActive(s.status)
    val f = s.leftFraction
    val barColor = when {
        f == null -> K.Gold
        f < .10f -> K.Red
        f < .25f -> K.Warn
        else -> K.Green
    }
    KCard(modifier.fillMaxWidth().clickable(onClick = onClick), padding = 12.dp, radius = 20.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SubCell("data", K.Gold, s.dataLeft, "حجم باقی‌مانده", Modifier.weight(1f))
            VDivider()
            SubCell("clock", K.Gold, s.timeLeft, "زمان باقی‌مانده", Modifier.weight(1f))
            VDivider()
            SubCell("status", if (active) K.Green else K.Red, faStatus(s.status), "وضعیت", Modifier.weight(1f))
        }
        if (f != null) {
            Spacer(Modifier.height(10.dp))
            ProgressLine(f, barColor)
        }
    }
}

@Composable
private fun SubCell(icon: String, tint: Color, value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniIcon(icon, tint, 13.dp)
            Spacer(Modifier.width(5.dp))
            AutoText(value, size = 14.sp, color = if (icon == "status") tint else K.Text)
        }
        Spacer(Modifier.height(1.dp))
        T(label, size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
    }
}

// ================================================================== انتخاب سریع سرور

@Composable
fun ServerSheet(
    visible: Boolean,
    nodes: List<NodeUi>,
    smartBusy: Boolean,
    onPick: (String) -> Unit,
    onSmart: () -> Unit,
    onAll: () -> Unit,
    onClose: () -> Unit,
) {
    AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Box(
            Modifier.fillMaxSize().background(Color(0xB3000000))
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
                Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(shape)
                    .background(Brush.verticalGradient(listOf(K.Surface, K.Panel)))
                    .border(1.dp, K.Gold.copy(alpha = .35f), shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(42.dp).height(4.dp).clip(CircleShape).background(K.Sub.copy(alpha = .4f)))
                Spacer(Modifier.height(12.dp))
                Heading("انتخاب لوکیشن", trf("%d کشور در دسترس", nodes.size))
                Spacer(Modifier.height(12.dp))
                // انتخاب هوشمند
                val sm = RoundedCornerShape(18.dp)
                Row(
                    Modifier.fillMaxWidth().clip(sm)
                        .background(Brush.horizontalGradient(listOf(K.Gold.copy(alpha = .22f), K.Accent.copy(alpha = .10f))))
                        .border(1.dp, K.Gold.copy(alpha = .6f), sm)
                        .clickable(enabled = !smartBusy, onClick = onSmart)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (smartBusy) Spinner(18.dp) else MiniIcon("spark", K.Gold, 18.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        T(if (smartBusy) "در حال سنجش پینگ و جیتر…" else "انتخاب هوشمند بهترین سرور", size = 14.sp, color = K.Gold, weight = FontWeight.Black)
                        T("کمترین پینگ و پایدارترین اتصال را خودم پیدا می‌کنم", size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    nodes.forEach { n ->
                        val rs = RoundedCornerShape(18.dp)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(rs)
                                .background(if (n.selected) K.Gold.copy(alpha = .10f) else Color(0x0DFFFFFF))
                                .border(1.dp, if (n.selected) K.Gold.copy(alpha = .7f) else K.Gold.copy(alpha = .10f), rs)
                                .clickable { onPick(n.id) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FlagBadge(n.flag, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                AutoText(n.name, size = 14.sp, color = if (n.selected) K.Gold else K.Text)
                                T(trf("%d پروتکل", n.protocols), size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
                            }
                            PingChip(n.ping)
                            Spacer(Modifier.width(8.dp))
                            CheckMark(n.selected)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(12.dp)).clickable(onClick = onAll).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T("مدیریت کامل سرورها و تست پینگ", size = 12.sp, color = K.Gold)
                    Spacer(Modifier.width(4.dp))
                    Chevron(K.Gold, 12.dp)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** تیک انتخاب (دایره‌ی سبز) */
@Composable
fun CheckMark(on: Boolean, size: androidx.compose.ui.unit.Dp = 22.dp) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (on) K.Green else Color.Transparent)
            .border(1.5.dp, if (on) K.Green else K.Sub.copy(alpha = .4f), CircleShape),
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
    else -> protoDesc(p)
}
