package cafe.kankipro.vpn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// تب سرورها با طراحی جدید (فقط ظاهر؛ داده‌ها از Screens.kt)

/** یک کانفیگ (پروتکل) داخل کارت سرور */
data class ProtoRowUi(
    val key: String,
    val selected: Boolean,
    val udp: Long?,
    val udpBusy: Boolean,
    val tcp: Long?,
    val tcpBusy: Boolean,
    val host: String?,
)

/** یک کارت سرور */
data class ServerCardUi(
    val id: String,
    val name: String,
    val flag: String,
    val selected: Boolean,
    val expanded: Boolean,
    val best: Long?,
    val protos: List<ProtoRowUi>,
)

class ServersActions(
    val toggle: (String) -> Unit = {},
    val select: (String, String) -> Unit = { _, _ -> },
    val udp: (String, String) -> Unit = { _, _ -> },
    val tcp: (String, String) -> Unit = { _, _ -> },
    val testAll: (String) -> Unit = {},
    val refresh: (String) -> Unit = {},
    val mtu: (String) -> String = { "" },
    val setMtu: (String, String) -> Unit = { _, _ -> },
    val closeTip: () -> Unit = {},
)

/** توضیح کوتاه هر پروتکل */
fun protoDesc(p: String) = when (p) {
    "amneziawg" -> "عبور از فیلتر"
    "amneziawg-direct" -> "اتصال مستقیم · بدون تونل"
    "wireguard" -> "سریع · مناسب گیم"
    "hysteria2" -> "پرسرعت · مناسب اینترنت ضعیف"
    "openvpn/tcp" -> "پایدار"
    else -> "سازگاری بالا"
}

@Composable
fun ServersContent(cards: List<ServerCardUi>, locked: Boolean, tip: RobotAlert?, act: ServersActions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(4.dp))
        Heading("سرورها", trf("%d لوکیشن · %d کانفیگ", cards.size, cards.sumOf { it.protos.size }))
        tip?.let { Spacer(Modifier.height(10.dp)); RobotBanner(it, onClose = act.closeTip) }
        if (locked) {
            Spacer(Modifier.height(10.dp))
            val sh = RoundedCornerShape(16.dp)
            Row(
                Modifier.fillMaxWidth().clip(sh).background(K.Green.copy(alpha = .08f)).border(1.dp, K.Green.copy(alpha = .35f), sh)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniIcon("shield", K.Green, 16.dp)
                Spacer(Modifier.width(8.dp))
                T("الان وصل هستی. برای تغییر سرور یا پروتکل، اول اتصال را قطع کن.", size = 12.sp, color = K.Text, weight = FontWeight.Normal)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (cards.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { T("سروری پیدا نشد", color = K.Sub) }
        }
        cards.forEach { c ->
            ServerCard(c, locked, act)
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ServerCard(c: ServerCardUi, locked: Boolean, act: ServersActions) {
    val shape = RoundedCornerShape(24.dp)
    val arrow by animateFloatAsState(if (c.expanded) 1f else 0f, label = "arr")
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(K.Surface, K.Surface2)))
            .border(if (c.selected) 1.5.dp else 1.dp, if (c.selected) K.Gold.copy(alpha = .75f) else K.Gold.copy(alpha = .16f), shape),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { act.toggle(c.id) }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlagBadge(c.flag, 46.dp, ring = if (c.selected) K.Gold else K.Gold.copy(alpha = .4f))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                AutoText(c.name, size = 16.sp, color = K.Text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(trf("%d پروتکل", c.protos.size), size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
                    if (c.selected) {
                        Spacer(Modifier.width(6.dp))
                        Dot(K.Green, 6.dp)
                        Spacer(Modifier.width(4.dp))
                        T("انتخاب شده", size = 11.sp, color = K.Green, weight = FontWeight.Bold)
                    }
                }
            }
            PingChip(c.best)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.rotate(90f * arrow * (if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1))) { Chevron(K.Gold, 14.dp) }
        }
        AnimatedVisibility(visible = c.expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                HDivider(Modifier.padding(bottom = 10.dp))
                c.protos.forEach { p ->
                    ProtoRow(c.id, p, locked, act)
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallLine("⚡ تست واقعی همه", Modifier.weight(1f)) { act.testAll(c.id) }
                    RoundIcon(38.dp, onClick = { act.refresh(c.id) }) { androidx.compose.foundation.Canvas(Modifier.size(18.dp)) { refreshIcon(K.Gold) } }
                }
            }
        }
    }
}

@Composable
private fun ProtoRow(nodeId: String, p: ProtoRowUi, locked: Boolean, act: ServersActions) {
    var details by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val isOvpn = p.key.startsWith("openvpn")
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (p.selected) K.Gold.copy(alpha = .12f) else Color(0x0DFFFFFF))
            .border(1.dp, if (p.selected) K.Gold.copy(alpha = .8f) else K.Gold.copy(alpha = .10f), shape)
            .clickable(enabled = !locked) { act.select(nodeId, p.key) }
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CheckMark(p.selected, 20.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                T(protoTitle(p.key), size = 13.sp, font = Poppins, weight = FontWeight.Bold, color = if (p.selected) K.Gold else K.Text, ltr = true)
                T(protoDesc(p.key), size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
            }
            if (!isOvpn) {
                ProtoBadge("UDP", p.udp, p.udpBusy) { act.udp(nodeId, p.key) }
                Spacer(Modifier.width(5.dp))
            }
            ProtoBadge("TCP", p.tcp, p.tcpBusy) { act.tcp(nodeId, p.key) }
            Spacer(Modifier.width(5.dp))
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (details) K.Gold.copy(alpha = .22f) else Color(0x14FFFFFF))
                    .border(1.dp, K.Gold.copy(alpha = .25f), RoundedCornerShape(11.dp))
                    .clickable { details = !details },
                contentAlignment = Alignment.Center,
            ) { androidx.compose.foundation.Canvas(Modifier.size(16.dp)) { navIcon(3, K.Gold) } }
        }
        AnimatedVisibility(visible = details) {
            Column(Modifier.padding(top = 8.dp)) {
                if (isOvpn) {
                    T("تنظیمات OpenVPN در اپ OpenVPN انجام می‌شود", size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
                } else {
                    p.host?.let { h -> T("سرور: $h", size = 11.sp, color = K.Sub, weight = FontWeight.Normal, modifier = Modifier.padding(bottom = 6.dp)) }
                    MtuField(act.mtu(p.key)) { act.setMtu(p.key, it) }
                    T("DNS از بخش تنظیمات تغییر می‌کند", size = 10.sp, color = K.Sub, weight = FontWeight.Normal, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
fun MtuField(initial: String, onChange: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Ltr) {
        OutlinedTextField(
            value = v,
            onValueChange = { t -> v = t.filter { it.isDigit() }.take(4); onChange(v) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { T("MTU دلخواه (خالی = پیش‌فرض)", size = 12.sp, color = K.Sub, weight = FontWeight.Normal) },
            textStyle = TextStyle(fontFamily = Poppins, fontSize = 14.sp, color = K.Text),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = K.Gold, unfocusedBorderColor = K.Line, cursorColor = K.Gold),
        )
    }
}
