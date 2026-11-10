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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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

/** یک ردیف داخل کارت کشور: یک پروتکل یک سرور، کانفیگ تانل (راست) و مستقیم (چپ) کنار هم؛ place: شهر وقتی کشور چند سرور دارد */
data class CfgLineUi(val place: String?, val tunnel: CfgRowUi?, val direct: CfgRowUi?)

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
    val lines: List<CfgLineUi> = rows.map { CfgLineUi(null, null, it) },
)

/** وضعیت «تست پینگ همه» */
data class PingAllUi(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val udp: Int = 0, val tcp: Int = 0, val finished: Boolean = false)

class ServersActions(
    val toggle: (String) -> Unit = {},
    val pick: (String, String) -> Unit = { _, _ -> },
    val pingAll: () -> Unit = {},
    val mtu: (String) -> String = { "" },
    /** MTU ای که پنل در کانفیگ این سرور گذاشته (null = نامعلوم) */
    val panelMtu: (String, String) -> String? = { _, _ -> null },
    val setMtu: (String, String) -> Unit = { _, _ -> },
    val closeTip: () -> Unit = {},
)

/** توضیح کوتاه هر پروتکل */
fun protoDesc(p: String) = when (p) {
    "kankimix" -> "مخصوص بازی · پینگ کم، ضد لگ"
    "mtun" -> "مخصوص بازی · بسته‌های کوچک دوتایی، کم‌حجم"
    "kping" -> "فقط پابجی گلوبال · وای‌فای و دیتا با هم"
    "amneziawg" -> "پایدار روی شبکه‌ی محدود"
    "amneziawg-direct" -> "اتصال مستقیم · بدون تونل"
    "wireguard" -> "سریع · مناسب گیم"
    "hysteria2" -> "پرسرعت · مناسب اینترنت ضعیف"
    "xray" -> "محدودیت شدید شبکه · سخت برای مسدود شدن"
    "openvpn/tcp" -> "پایدار"
    else -> "سازگاری بالا"
}

/** خانواده‌ی هر پروتکل برای دکمه‌های بالای صفحه‌ی سرورها (Amnezia با تانل و مستقیمش یکی است) */
private fun protoFamily(p: String) = when (p) {
    "amneziawg", "amneziawg-direct" -> "Amnezia"
    "kankimix" -> "KANKI MIX"
    "wireguard" -> "WireGuard"
    "hysteria2" -> "Hysteria2"
    "xray" -> "Xray"
    "mtun" -> "microtun"
    "kping" -> "KPING"
    else -> p
}

/** ترتیب دکمه‌های پروتکل (همان ترتیب ثابت برنامه) */
private val FamilyOrderUi = listOf("KANKI MIX", "Amnezia", "WireGuard", "Xray", "Hysteria2", "microtun", "KPING")

@Composable
fun ServersContent(cards: List<CountryCardUi>, ping: PingAllUi, tip: RobotAlert?, act: ServersActions) {
    // فیلتر پروتکل (۱.۳۲): «همه» یا یک خانواده؛ فقط کشورهایی که آن پروتکل را دارند و فقط همان کانفیگ‌ها
    // پیش‌فرض اولین پروتکل (مثل طرح)؛ «همه» کارت‌های کامل بازشونده را نشان می‌دهد
    var fam by rememberSaveable { mutableStateOf("?") }
    val fams = remember(cards) {
        val have = cards.flatMap { c -> c.lines.flatMap { listOfNotNull(it.tunnel, it.direct) } }.map { protoFamily(it.proto) }.toSet()
        FamilyOrderUi.filter { it in have } + (have - FamilyOrderUi.toSet()).sorted()
    }
    if (fam == "?" && fams.isNotEmpty()) fam = fams.first()
    if (fam != "?" && fam.isNotEmpty() && fam !in fams) fam = ""
    if (fam == "?") fam = ""
    val shown = if (fam.isEmpty()) cards else cards.mapNotNull { c ->
        val ls = c.lines.mapNotNull { l ->
            val t = l.tunnel?.takeIf { protoFamily(it.proto) == fam }
            val d = l.direct?.takeIf { protoFamily(it.proto) == fam }
            if (t == null && d == null) null else l.copy(tunnel = t, direct = d)
        }
        if (ls.isEmpty()) null else c.copy(lines = ls, best = ls.flatMap { listOfNotNull(it.tunnel?.ping, it.direct?.ping) }.filter { it >= 0 }.minOrNull())
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = D.PagePad),
        verticalArrangement = Arrangement.spacedBy(D.CardGap),
    ) {
        Spacer(Modifier.height(D.S2))
        ServersHero(cards.size, cards.sumOf { c -> c.lines.count { it.tunnel != null } + c.lines.count { it.direct != null } }, ping, act.pingAll)
        tip?.let { RobotBanner(it, onClose = act.closeTip) }
        if (fams.size > 1) ProtoChips(fams, fam) { fam = it }
        fam.takeIf { it.isNotEmpty() }?.let { f ->
            val any = shown.firstNotNullOfOrNull { c -> c.lines.firstNotNullOfOrNull { it.tunnel ?: it.direct } }
            any?.let { r ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Dot(K.Gold, 6.dp)
                    Spacer(Modifier.width(6.dp))
                    T(tr(protoDesc(r.proto)) + " · " + trf("%d کشور", shown.size), size = 11.5.sp, color = K.Sub, weight = FontWeight.Normal, maxLines = 1)
                }
            }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(D.S6), contentAlignment = Alignment.Center) { T("سروری پیدا نشد", color = K.Sub) }
        }
        // کلید هر کارت اسم کشور است: وقتی ترتیب با پینگ تازه عوض می‌شود یا کارتی بسته می‌شود،
        // کانفیگ‌های باز یک کشور هیچ‌وقت زیر کارت کشور دیگری نمی‌مانند
        shown.forEachIndexed { i, c ->
            key(c.id) {
                val m = if (i == 0) Modifier.guideTarget("country") else Modifier
                if (fam.isEmpty()) CountryCard(c, act, m) else CountryRow(c, act, m)
            }
        }
        Spacer(Modifier.height(D.S2))
    }
}

/**
 * سربرگ صفحه‌ی سرورها (۱.۳۲): لوگوی کانکی وسط با حلقه‌ی نور چرخان، عنوان، یک خط و شمار کشورها و کانفیگ‌ها، و زیرش
 * دکمه‌ی کوچک «تست پینگ همه» با نوار پیشرفت باریک (به‌جای کارت جدای تست پینگ).
 */
@Composable
private fun ServersHero(countries: Int, configs: Int, p: PingAllUi, onPing: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(D.RX)
    Column(
        modifier.fillMaxWidth().goldGlass(shape).padding(horizontal = D.CardPad, vertical = D.S4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Logo3D(64.dp, animate = true, glow = .6f)
        Spacer(Modifier.height(D.S2))
        T("پروتکل‌های کانکی", size = 19.sp, color = K.Text, weight = FontWeight.Black, maxLines = 1)
        T("پرسرعت، امن و همیشه آماده؛ با یک لمس وصل شو", size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal, maxLines = 1)
        Spacer(Modifier.height(D.S2))
        Row(horizontalArrangement = Arrangement.spacedBy(D.S2)) {
            HeroChip(trf("%d کشور", countries))
            HeroChip(trf("%d کانفیگ", configs))
        }
        Spacer(Modifier.height(D.S3))
        Box(Modifier.guideTarget("ping_all")) {
            if (p.running) {
                Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(14.dp)
                    Spacer(Modifier.width(D.S2))
                    T(trf("در حال تست… %d از %d", p.done, p.total), size = 12.sp, color = K.Text, weight = FontWeight.Bold)
                }
            } else PingStartButton(if (p.finished) "دوباره" else "تست پینگ همه", p.finished, onPing)
        }
        val f = when {
            p.total == 0 -> 0f
            p.running -> p.done.toFloat() / p.total
            p.finished -> 1f
            else -> 0f
        }
        if (p.running || p.finished) {
            Spacer(Modifier.height(D.S2))
            NeonBar(f, Modifier.padding(horizontal = D.S6))
            Spacer(Modifier.height(D.S1))
            T("UDP " + faDigits(p.udp.toString()) + " · TCP " + faDigits(p.tcp.toString()), size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal)
        }
    }
}

/** دکمه‌های کوچک پروتکل در یک خط («همه» اول)؛ اگر جا نشد خط افقی می‌لغزد */
@Composable
private fun ProtoChips(fams: List<String>, sel: String, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = D.S2).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        (listOf("") + fams).forEach { f ->
            val on = f == sel
            val shape = RoundedCornerShape(D.RS)
            Box(
                Modifier.height(32.dp).widthIn(min = 58.dp)
                    .then(if (on) Modifier.goldGlass(shape, selected = true) else Modifier.goldGlass(shape))
                    .clickable { onPick(f) }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (f.isEmpty()) T("همه", size = 12.sp, color = if (on) K.Gold else K.Text, weight = FontWeight.Black, maxLines = 1)
                else T(f, size = 11.sp, color = if (on) K.Gold else K.Text, font = Poppins, weight = FontWeight.Bold, ltr = true, maxLines = 1)
            }
        }
    }
}

@Composable
private fun HeroChip(text: String) {
    val sh = RoundedCornerShape(50)
    T(
        text, size = 11.sp, color = K.GoldLight, weight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(sh).background(Color.Black.copy(alpha = .3f)).border(1.dp, K.Gold.copy(alpha = .35f), sh).padding(horizontal = D.S3, vertical = 2.dp),
    )
}

/** دکمه‌ی «شروع / دوباره» تست پینگ: قرص برجسته‌ی رنگ تم با آیکون پخش یا تکرار */
@Composable
private fun PingStartButton(text: String, again: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.height(D.BtnS + 4.dp).widthIn(min = 104.dp).goldFill(shape).pressable(onClick = onClick).padding(horizontal = D.S4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(K.OnGold.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(12.dp)) {
                val w = size.width
                val c = K.OnGold
                if (again) {
                    drawArc(c, -60f, 290f, false, style = Stroke(w * .18f, cap = StrokeCap.Round))
                    drawPath(Path().apply { moveTo(w * .78f, -w * .05f); lineTo(w * 1.02f, w * .30f); lineTo(w * .62f, w * .34f); close() }, c)
                } else {
                    // مثلث پخش؛ در فارسی هم رو به جلو (چپ‌به‌راست نمی‌چرخد، نماد جهانی است)
                    drawPath(Path().apply { moveTo(w * .22f, w * .08f); lineTo(w * .92f, w * .5f); lineTo(w * .22f, w * .92f); close() }, c)
                }
            }
        }
        Spacer(Modifier.width(D.S2))
        T(text, size = 14.sp, color = K.OnGold, weight = FontWeight.Black)
    }
}

@Composable
private fun CountryCard(c: CountryCardUi, act: ServersActions, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(D.RL)
    val lit = c.expanded || c.selected
    Column(
        modifier.fillMaxWidth().goldGlass(shape, selected = lit),
    ) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).clickable { act.toggle(c.id) }.padding(horizontal = D.CardPad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlagBadge(c.flag, 30.dp, ring = if (lit) K.Gold else Color.White.copy(alpha = .45f))
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
            // کانفیگ‌ها در یک کادر: سرتیتر «تانل | مستقیم»، هر پروتکل یک ردیف با دو خانه
            val box = RoundedCornerShape(D.RM)
            Column(
                Modifier.fillMaxWidth().padding(start = D.CardPad, end = D.CardPad, bottom = D.CardPad).clip(box)
                    .background(Color(0x08FFFFFF)).border(1.dp, K.Gold.copy(alpha = .18f), box).padding(D.S2),
            ) {
                Row(Modifier.fillMaxWidth().padding(bottom = D.S1), horizontalArrangement = Arrangement.spacedBy(D.S2)) {
                    for (k in listOf("تانل", "مستقیم")) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            T(k, size = 11.sp, color = Color.White, weight = FontWeight.Black)
                        }
                    }
                }
                var lastPlace: String? = null
                c.lines.forEachIndexed { i, l ->
                    if (l.place != null && l.place != lastPlace) {
                        T(l.place, size = 11.sp, color = K.GoldLight, weight = FontWeight.Bold, modifier = Modifier.padding(top = D.S1, bottom = D.S1, start = D.S1))
                        lastPlace = l.place
                    }
                    key(l.tunnel?.let { it.node + "|" + it.proto } ?: "", l.direct?.let { it.node + "|" + it.proto } ?: "", i) { CfgLine(l, act) }
                }
            }
        }
    }
}

/**
 * ردیف باریک یک کشور وقتی یک پروتکل انتخاب شده (۱.۳۲، مثل طرح): پرچم گرد، اسم و شهر، و کنارش دو خانه‌ی کوچک تانل و
 * مستقیم با پینگ و آنتن؛ لمس هر خانه = انتخاب و اتصال. کشوری که چند سرور دارد زیرش یک ردیف دیگر برای هر سرور دارد.
 */
@Composable
private fun CountryRow(c: CountryCardUi, act: ServersActions, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(D.RL)
    val lit = c.selected || c.lines.any { l -> l.tunnel?.selected == true || l.direct?.selected == true }
    Column(modifier.fillMaxWidth().goldGlass(shape, selected = lit).padding(horizontal = D.S3, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        c.lines.forEachIndexed { i, l ->
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                if (i == 0) {
                    FlagBadge(c.flag, 30.dp, ring = if (lit) K.Gold else Color.White.copy(alpha = .45f))
                    Spacer(Modifier.width(D.S3))
                    Column(Modifier.weight(1f)) {
                        AutoText(c.name, size = D.TxtTitle, color = K.Text)
                        AutoText(l.place ?: c.city, size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal)
                    }
                } else {
                    Spacer(Modifier.width(30.dp + D.S3))
                    AutoText(l.place ?: c.city, size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal, modifier = Modifier.weight(1f))
                }
                MiniCfg(l.tunnel, "تانل", act)
                Spacer(Modifier.width(6.dp))
                MiniCfg(l.direct, "مستقیم", act)
            }
        }
    }
}

/** خانه‌ی کوچک یک کانفیگ در ردیف کشور: اسم راه، پینگ و آنتن؛ انتخاب‌شده شیشه‌ی نئونی */
@Composable
private fun MiniCfg(r: CfgRowUi?, label: String, act: ServersActions) {
    val shape = RoundedCornerShape(10.dp)
    val pick by rememberUpdatedState(act.pick)
    Column(
        Modifier.size(width = 68.dp, height = 38.dp)
            .then(if (r != null) Modifier.goldGlass(shape, selected = r.selected) else Modifier.clip(shape).border(1.dp, Color.White.copy(alpha = .06f), shape))
            .alpha(if (r == null) .4f else 1f)
            .then(if (r != null) Modifier.clickable { pick(r.node, r.proto) } else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        T(label, size = 10.sp, color = Color.White, weight = FontWeight.Bold, maxLines = 1)
        if (r == null) { T("—", size = 11.sp, color = K.Sub, ltr = true); return@Column }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r.busy) Spinner(10.dp) else {
                    val col = r.ping?.let { pingColor(it) } ?: K.Sub
                    T(when { r.ping == null -> "-"; r.ping < 0 -> "✕"; else -> "${r.ping}" }, size = 12.sp, color = K.Text, font = Poppins, weight = FontWeight.Bold, ltr = true, maxLines = 1)
                    Spacer(Modifier.width(3.dp))
                    SignalBars(r.ping, col)
                }
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

/** یک ردیف: خانه‌ی تانل و خانه‌ی مستقیم؛ نگه‌داشتن یک خانه جزئیاتش (سرور و MTU) را زیر ردیف باز می‌کند */
@Composable
private fun CfgLine(l: CfgLineUi, act: ServersActions) {
    var details by remember { mutableStateOf<CfgRowUi?>(null) }
    Column(Modifier.fillMaxWidth().padding(bottom = D.S1)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(D.S2)) {
            CfgCell(l.tunnel, Modifier.weight(1f), act) { details = if (details == it) null else it }
            CfgCell(l.direct, Modifier.weight(1f), act) { details = if (details == it) null else it }
        }
        AnimatedVisibility(visible = details != null, enter = fadeIn(tween(200)) + expandVertically(tween(240)), exit = fadeOut(tween(150)) + shrinkVertically(tween(180))) {
            val r = details ?: return@AnimatedVisibility
            Column(Modifier.padding(start = D.S2, end = D.S2, top = D.S1, bottom = D.S1)) {
                T(r.title + " · " + protoDesc(r.proto), size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
                r.host?.let { h -> T(trf("سرور: %s", h), size = 11.sp, color = K.Sub, weight = FontWeight.Normal, modifier = Modifier.padding(bottom = D.S2)) }
                // فقط WireGuard و AmneziaWG: اندازه‌ی بسته‌ی بقیه را هسته‌شان یا پنل تعیین می‌کند
                if (r.proto in setOf("wireguard", "amneziawg", "amneziawg-direct")) MtuField(act.mtu(r.proto), act.panelMtu(r.node, r.proto)) { act.setMtu(r.proto, it) }
            }
        }
    }
}

/**
 * یک خانه: نوار رنگ پروتکل، اسم، نشانگر سیگنال (سه میله از روی پینگ) و پینگ (میلی‌ثانیه)؛ انتخاب‌شده با زمینه و لبه‌ی طلایی.
 * لمس = انتخاب و اتصال؛ نگه‌داشتن = جزئیات. خالی وقتی این سرور این راه را ندارد.
 */
@Composable
private fun CfgCell(r: CfgRowUi?, modifier: Modifier, act: ServersActions, onLong: (CfgRowUi) -> Unit) {
    val shape = RoundedCornerShape(D.RS)
    val pick by rememberUpdatedState(act.pick)
    val long by rememberUpdatedState(onLong)
    val sel = r?.selected == true
    Row(
        modifier.height(44.dp).clip(shape)
            .background(
                if (sel) Brush.horizontalGradient(listOf(K.Gold.copy(alpha = .26f), K.Gold.copy(alpha = .07f)))
                else Brush.horizontalGradient(listOf(Color(0x12FFFFFF), Color(0x05FFFFFF))),
            )
            .border(if (sel) 1.4.dp else 1.dp, if (sel) K.Gold else K.Gold.copy(alpha = if (r != null) .14f else .05f), shape)
            .then(
                if (r == null) Modifier
                else Modifier.pointerInput(r.node, r.proto) { detectTapGestures(onTap = { pick(r.node, r.proto) }, onLongPress = { long(r) }) },
            )
            .padding(start = D.S2, end = D.S2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (r == null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { T("—", size = 12.sp, color = K.Sub.copy(alpha = .5f), ltr = true) }
            return@Row
        }
        Box(Modifier.size(width = 3.dp, height = 20.dp).clip(CircleShape).background(protoColor(r.proto)))
        Spacer(Modifier.width(D.S2))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            AutoText(r.title, size = 12.sp, color = if (sel) K.GoldLight else K.Text, weight = FontWeight.Bold)
        }
        Spacer(Modifier.width(D.S1))
        if (r.busy) Spinner(12.dp)
        else {
            val col = r.ping?.let { pingColor(it) } ?: K.Sub
            SignalBars(r.ping, col)
            Spacer(Modifier.width(D.S1))
            T(
                when { r.ping == null -> "-"; r.ping < 0 -> "✕"; else -> "${r.ping}" },
                size = 12.sp, color = col, font = Poppins, weight = FontWeight.Bold, ltr = true, maxLines = 1,
            )
        }
    }
}

/** سه میله‌ی سیگنال از روی پینگ: زیر ۸۰ سه، زیر ۱۶۰ دو، بیشتر یک، ناموفق یا نامعلوم هیچ */
@Composable
private fun SignalBars(ping: Long?, c: Color) {
    val n = when { ping == null || ping < 0 -> 0; ping < 80 -> 3; ping < 160 -> 2; else -> 1 }
    Canvas(Modifier.size(width = 12.dp, height = 11.dp)) {
        val bw = size.width / 4.2f
        for (i in 0 until 3) {
            val h = size.height * (.4f + .3f * i)
            val x = i * bw * 1.6f
            drawRoundRect(
                if (i < n) c else c.copy(alpha = .22f),
                topLeft = Offset(x, size.height - h), size = androidx.compose.ui.geometry.Size(bw, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw / 2),
            )
        }
    }
}

@Composable
fun MtuField(initial: String, panel: String?, onChange: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        OutlinedTextField(
            value = v,
            onValueChange = { t -> v = t.filter { it.isDigit() }.take(4); onChange(v) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = {
                T(if (panel != null) trf("MTU دلخواه (خالی = پنل: %s؛ فقط کمتر)", panel) else "MTU دلخواه (خالی = پیش‌فرض)", size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
            },
            textStyle = TextStyle(fontFamily = Poppins, fontSize = 14.sp, color = K.Text),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(D.RS),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = K.Gold, unfocusedBorderColor = K.Line, cursorColor = K.Gold),
        )
    }
}
