package cafe.kankipro.vpn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// تب سرورها (فقط ظاهر؛ داده‌ها از Screens.kt):
// بالای صفحه یک کارت «تست پینگ همه» با دکمه‌ی «شروع» و نوار پیشرفت، بعد کارت هر کشور.
// با لمس کارت، کانفیگ‌های آن کشور با پینگ هر کدام باز می‌شود؛ لمس یک کانفیگ یعنی همین را انتخاب و وصل کن.

/** یک کانفیگ داخل کارت کشور */
data class CfgRowUi(
    val node: String,
    val proto: String,
    val title: String,
    val ping: Long?,
    val busy: Boolean,
    val selected: Boolean,
    val host: String?,
)

/** کارت یک کشور */
data class CountryCardUi(
    val id: String,
    val name: String,
    val city: String,
    val flag: String,
    val best: Long?,
    val expanded: Boolean,
    val selected: Boolean,
    val rows: List<CfgRowUi>,
)

/** وضعیت «تست پینگ همه» */
data class PingAllUi(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val udp: Int = 0, val tcp: Int = 0, val finished: Boolean = false)

class ServersActions(
    val toggle: (String) -> Unit = {},
    val pick: (String, String) -> Unit = { _, _ -> },
    val pingAll: () -> Unit = {},
    val mtu: (String) -> String = { "" },
    val setMtu: (String, String) -> Unit = { _, _ -> },
    val closeTip: () -> Unit = {},
    val filterAuto: (Boolean) -> Unit = {},
    val filterXray: (Boolean) -> Unit = {},
)

/** گزینه‌های ضد فیلتر (فقط وقتی حالت ضد فیلتر انتخاب است در تب سرورها دیده می‌شود) */
data class FilterOptsUi(val auto: Boolean, val xrayFirst: Boolean)

@Composable
private fun FilterOptsCard(u: FilterOptsUi, act: ServersActions) {
    val mc = modeColor(KMode.FILTER)
    val shape = RoundedCornerShape(D.RL)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(listOf(mc.copy(alpha = .10f), mc.copy(alpha = .03f))))
            .border(1.dp, mc.copy(alpha = .45f), shape)
            .padding(D.CardPad),
    ) {
        T("ضد فیلتر", size = D.TxtTitle, color = K.Text, weight = FontWeight.Black)
        Spacer(Modifier.height(D.S1))
        FilterCheck("خودکار", "بهترین راه را خودش پیدا و وصل می‌کند؛ با لمس یک کانفیگ، همان دستی انتخاب می‌شود", u.auto, mc) { act.filterAuto(!u.auto) }
        FilterCheck("اولویت Xray", if (u.xrayFirst) "فقط اینباندهای Xray امتحان می‌شوند" else "Xray، AmneziaWG مستقیم و تانل، همه با هم به ترتیب پینگ", u.xrayFirst, mc) { act.filterXray(!u.xrayFirst) }
    }
}

@Composable
private fun FilterCheck(title: String, sub: String, on: Boolean, c: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(D.RS)).clickable(onClick = onClick).padding(vertical = D.S1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckMark(on, D.Icon, c)
        Spacer(Modifier.width(D.S3))
        Column(Modifier.weight(1f)) {
            T(title, size = 13.sp, color = K.Text, weight = FontWeight.Bold)
            T(sub, size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal)
        }
    }
}

/** توضیح کوتاه هر پروتکل */
fun protoDesc(p: String) = when (p) {
    "kankimix" -> "مخصوص بازی · پینگ کم، ضد لگ"
    "amneziawg" -> "عبور از فیلتر"
    "amneziawg-direct" -> "اتصال مستقیم · بدون تونل"
    "wireguard" -> "سریع · مناسب گیم"
    "hysteria2" -> "پرسرعت · مناسب اینترنت ضعیف"
    "xray" -> "فیلتر شدید · سخت برای مسدود شدن"
    "openvpn/tcp" -> "پایدار"
    else -> "سازگاری بالا"
}

@Composable
fun ServersContent(cards: List<CountryCardUi>, ping: PingAllUi, tip: RobotAlert?, act: ServersActions, filter: FilterOptsUi? = null) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = D.PagePad),
        verticalArrangement = Arrangement.spacedBy(D.CardGap),
    ) {
        Heading("سرورها", "یک کلیک تا کانفیگ", Modifier.padding(top = D.S1, bottom = D.S1))
        tip?.let { RobotBanner(it, onClose = act.closeTip) }
        filter?.let { FilterOptsCard(it, act) }
        PingAllCard(ping, act.pingAll)
        if (cards.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(D.S6), contentAlignment = Alignment.Center) { T("سروری پیدا نشد", color = K.Sub) }
        }
        cards.forEach { c -> CountryCard(c, act) }
        Spacer(Modifier.height(D.S2))
    }
}

/** کارت «تست پینگ همه»: همه‌ی کانفیگ‌ها (UDP با پینگ ICMP، TCP با زمان اتصال) با هم سنجیده می‌شوند */
@Composable
private fun PingAllCard(p: PingAllUi, onStart: () -> Unit) {
    val shape = RoundedCornerShape(D.RL)
    Column(
        Modifier.fillMaxWidth().goldGlass(shape)
            .padding(horizontal = D.CardPad, vertical = D.S2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("تست پینگ همه", size = D.TxtTitle, color = K.Text, weight = FontWeight.Black)
                val line = when {
                    p.running -> trf("در حال تست… %d از %d", p.done, p.total)
                    else -> "UDP " + faDigits(p.udp.toString()) + " · TCP " + faDigits(p.tcp.toString())
                }
                T(line, size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal)
            }
            if (p.running) {
                Box(Modifier.size(width = 80.dp, height = D.BtnS), contentAlignment = Alignment.Center) { Spinner(D.Icon) }
            } else {
                SmallGold(if (p.finished) "دوباره" else "شروع", Modifier.widthIn(min = 80.dp), onClick = onStart)
            }
        }
        Spacer(Modifier.height(D.S2))
        val f = when {
            p.total == 0 -> 0f
            p.running -> p.done.toFloat() / p.total
            p.finished -> 1f
            else -> 0f
        }
        ProgressLine(f, K.Gold, height = 4.dp)
    }
}

@Composable
private fun CountryCard(c: CountryCardUi, act: ServersActions) {
    val shape = RoundedCornerShape(D.RL)
    val lit = c.expanded || c.selected
    Column(
        Modifier.fillMaxWidth().goldGlass(shape, selected = lit),
    ) {
        Row(
            Modifier.fillMaxWidth().height(D.RowH).clickable { act.toggle(c.id) }.padding(horizontal = D.CardPad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlagBadge(c.flag, D.Flag, ring = if (lit) K.Gold else K.Accent.copy(alpha = .7f))
            Spacer(Modifier.width(D.S3))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AutoText(c.name, size = D.TxtTitle, color = K.Text)
                    if (c.selected) {
                        Spacer(Modifier.width(D.S2))
                        Dot(K.Green, 6.dp)
                    }
                }
                AutoText(c.city, size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal)
            }
            CardPing(c.best, c.expanded)
        }
        AnimatedVisibility(visible = c.expanded, enter = fadeIn(tween(220)) + expandVertically(tween(260)), exit = fadeOut(tween(160)) + shrinkVertically(tween(200))) {
            Column(Modifier.fillMaxWidth().padding(start = D.CardPad, end = D.CardPad, bottom = D.S2)) {
                HDivider(Modifier.padding(bottom = D.S1))
                c.rows.forEach { r -> CfgRow(r, act) }
            }
        }
    }
}

/** پینگ کنار کارت با یک فلش کوچک باز/بسته */
@Composable
private fun CardPing(ms: Long?, expanded: Boolean) {
    val col = ms?.let { pingColor(it) } ?: K.Sub
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(9.dp)) {
                val w = size.width
                val p = Path().apply {
                    if (expanded) { moveTo(0f, w * .25f); lineTo(w, w * .25f); lineTo(w * .5f, w * .85f) }
                    else { moveTo(0f, w * .8f); lineTo(w, w * .8f); lineTo(w * .5f, w * .2f) }
                    close()
                }
                drawPath(p, if (expanded) K.Gold else col)
            }
            Spacer(Modifier.width(D.S2))
            T(
                when { ms == null -> "-"; ms < 0 -> "✕"; else -> "$ms ms" },
                size = 12.sp, color = col, font = Poppins, weight = FontWeight.SemiBold, ltr = true,
            )
        }
    }
}

/** یک کانفیگ: لمس = انتخاب و اتصال؛ نگه‌داشتن = جزئیات (سرور و MTU) */
@Composable
private fun CfgRow(r: CfgRowUi, act: ServersActions) {
    var details by remember { mutableStateOf(false) }
    val pick by rememberUpdatedState(act.pick)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(D.RowS).clip(RoundedCornerShape(D.RS))
                .background(if (r.selected) K.Gold.copy(alpha = .08f) else Color.Transparent)
                .pointerInput(r.node, r.proto) {
                    detectTapGestures(
                        onTap = { pick(r.node, r.proto) },
                        onLongPress = { details = !details },
                    )
                }
                .padding(horizontal = D.S2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (r.selected) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(K.Gold))
                Spacer(Modifier.width(D.S2))
            }
            // عنوان لاتین (مثل Xray · VLESS) هم از سمت راست شروع شود و به پینگ نچسبد
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                AutoText(r.title, size = 13.sp, color = if (r.selected) K.GoldLight else K.Text, weight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(D.S3))
            if (r.busy) Spinner(14.dp)
            else T(
                when { r.ping == null -> "-"; r.ping < 0 -> "✕"; else -> "${r.ping} ms" },
                size = 12.sp, color = r.ping?.let { pingColor(it) } ?: K.Sub, font = Poppins, weight = FontWeight.SemiBold, ltr = true,
            )
        }
        AnimatedVisibility(visible = details, enter = fadeIn(tween(200)) + expandVertically(tween(240)), exit = fadeOut(tween(150)) + shrinkVertically(tween(180))) {
            Column(Modifier.padding(start = D.S2, end = D.S2, bottom = D.S2)) {
                T(protoDesc(r.proto), size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
                r.host?.let { h -> T(trf("سرور: %s", h), size = 11.sp, color = K.Sub, weight = FontWeight.Normal, modifier = Modifier.padding(bottom = D.S2)) }
                if (r.proto != "xray") MtuField(act.mtu(r.proto)) { act.setMtu(r.proto, it) }
            }
        }
    }
}

@Composable
fun MtuField(initial: String, onChange: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        OutlinedTextField(
            value = v,
            onValueChange = { t -> v = t.filter { it.isDigit() }.take(4); onChange(v) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { T("MTU دلخواه (خالی = پیش‌فرض)", size = 12.sp, color = K.Sub, weight = FontWeight.Normal) },
            textStyle = TextStyle(fontFamily = Poppins, fontSize = 14.sp, color = K.Text),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(D.RS),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = K.Gold, unfocusedBorderColor = K.Line, cursorColor = K.Gold),
        )
    }
}
