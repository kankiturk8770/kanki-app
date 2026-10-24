package cafe.kankipro.vpn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// تنظیمات و پنل حساب با طراحی جدید (فقط ظاهر)

data class SettingsUi(
    val dns: String,
    val autoConnect: Boolean,
    val sounds: Boolean,
    val lang: String,
    val excludedCount: Int,
    val version: String,
)

class SettingsActions(
    val setDns: (String) -> Unit = {},
    val setAuto: (Boolean) -> Unit = {},
    val setSounds: (Boolean) -> Unit = {},
    val setTheme: (String) -> Unit = {},
    val setLang: (String) -> Unit = {},
    val openSplit: () -> Unit = {},
    val openKill: () -> Unit = {},
    val guide: () -> Unit = {},
    val buy: () -> Unit = {},
    val support: () -> Unit = {},
    val share: () -> Unit = {},
    val logout: () -> Unit = {},
)

@Composable
fun SettingsContent(ui: SettingsUi, tip: RobotAlert?, act: SettingsActions, onCloseTip: () -> Unit) {
    var dnsOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = D.PagePad)) {
        Spacer(Modifier.height(D.S1))
        Heading("تنظیمات")
        tip?.let { Spacer(Modifier.height(D.S3)); RobotBanner(it, onClose = onCloseTip) }

        Group("اتصال", Modifier.guideTarget("settings")) {
            MenuRow("dns", "DNS", dnsTitle(ui.dns), dnsOpen, tint = K.Accent) { dnsOpen = !dnsOpen }
            AnimatedVisibility(visible = dnsOpen) { DnsPicker(ui.dns, act.setDns) }
            HDivider()
            SwitchRow("auto", "اتصال خودکار", "با باز کردن اپ، خودکار وصل شود", ui.autoConnect, act.setAuto)
            HDivider()
            MenuRow("kill", "کیل‌سوییچ و همیشه‌روشن", "جلوگیری از نشت اینترنت هنگام قطعی", false, arrow = true, tint = K.Green, onClick = act.openKill)
            HDivider()
            MenuRow(
                "split", "تونل‌زنی انتخابی",
                if (ui.excludedCount == 0) "همه‌ی برنامه‌ها از VPN رد می‌شوند" else trf("%d برنامه بدون VPN", ui.excludedCount),
                false, arrow = true, tint = K.Accent, onClick = act.openSplit,
            )
        }

        Group("ظاهر و زبان") {
            ThemePicker(act.setTheme)
            HDivider()
            Row(Modifier.fillMaxWidth().height(D.RowH), verticalAlignment = Alignment.CenterVertically) {
                SettingIcon("lang", K.Gold)
                Spacer(Modifier.width(D.S3))
                T("زبان", size = D.TxtTitle, modifier = Modifier.weight(1f))
                Segmented(
                    listOf(SegItem("fa", "فارسی"), SegItem("en", "English")), ui.lang,
                    Modifier.width(170.dp), onSelect = act.setLang,
                )
            }
            HDivider()
            SwitchRow("sound", "صداهای اپ", "صدای ورود و دکمه‌ها", ui.sounds, act.setSounds)
        }

        Group("حساب و پشتیبانی") {
            Row(
                Modifier.fillMaxWidth().height(D.RowH).clip(RoundedCornerShape(D.RS)).clickable(onClick = act.guide),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(D.IconBox), contentAlignment = Alignment.Center) { RobotFace(D.IconBox) }
                Spacer(Modifier.width(D.S3))
                Column(Modifier.weight(1f)) {
                    T("راهنمای برنامه", size = D.TxtTitle, maxLines = 1)
                    T("ربات کانکی همه‌جای برنامه را نشانت می‌دهد", size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal, maxLines = 1)
                }
                Chevron(K.Gold)
            }
            HDivider()
            MenuRow("cart", "خرید و تمدید اشتراک", "از طریق ربات تلگرام", false, arrow = true, onClick = act.buy)
            HDivider()
            MenuRow("support", "پشتیبانی", "@KANKI_VPN_ADMIN", false, arrow = true, onClick = act.support)
            HDivider()
            MenuRow("share", "معرفی به دوستان", "ارسال لینک ربات کانکی", false, arrow = true, onClick = act.share)
            HDivider()
            MenuRow("logout", "خروج از حساب", "حذف اشتراک از این گوشی", false, danger = true, onClick = act.logout)
        }

        Spacer(Modifier.height(D.S4))
        T("Kanki VPN · v${ui.version}", size = 12.sp, color = K.Sub, font = Poppins, ltr = true, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(D.S4))
    }
}

@Composable
private fun Group(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(D.S4))
    T(title, size = 13.sp, color = K.Accent, weight = FontWeight.Black, modifier = Modifier.padding(start = D.S1, bottom = D.S2))
    KCard(modifier.fillMaxWidth(), padding = D.CardPad, content = content)
}

@Composable
fun SwitchRow(icon: String, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(D.RowH), verticalAlignment = Alignment.CenterVertically) {
        SettingIcon(icon, K.Gold)
        Spacer(Modifier.width(D.S3))
        Column(Modifier.weight(1f)) {
            T(title, size = D.TxtTitle, maxLines = 1)
            T(sub, size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal, maxLines = 1)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = K.OnGold, checkedTrackColor = K.ToggleOn,
                uncheckedThumbColor = K.Sub, uncheckedTrackColor = Color(0x33FFFFFF), uncheckedBorderColor = Color(0x33FFFFFF),
            ),
        )
    }
}

@Composable
fun MenuRow(
    icon: String, title: String, sub: String, expanded: Boolean,
    arrow: Boolean = false, danger: Boolean = false, tint: Color = K.Gold, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(D.RowH).clip(RoundedCornerShape(D.RS)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingIcon(icon, if (danger) K.Red else tint)
        Spacer(Modifier.width(D.S3))
        Column(Modifier.weight(1f)) {
            T(title, size = D.TxtTitle, color = if (danger) K.Red else K.Text, maxLines = 1)
            T(sub, size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal, maxLines = 1)
        }
        when {
            danger -> {}
            arrow -> Chevron(K.Gold)
            else -> T(if (expanded) "▲" else "▼", size = 12.sp, color = K.Gold)
        }
    }
}

@Composable
fun DnsPicker(current: String, onSet: (String) -> Unit) {
    val keys = DnsOptions.list.map { it.first }
    var custom by remember { mutableStateOf(if (current !in keys) current else "") }
    var customMode by remember { mutableStateOf(current !in keys) }
    Column(Modifier.padding(bottom = 6.dp)) {
        DnsOptions.list.forEach { (value, title) ->
            DnsRow(title, value.ifBlank { "از کانفیگ سرور" }, !customMode && current == value) { customMode = false; onSet(value) }
        }
        DnsRow("DNS دلخواه", "آدرس را خودتان وارد کنید", customMode) { customMode = true }
        if (customMode) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it; onSet(it.trim()) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { T("1.1.1.1, 8.8.8.8", size = 14.sp, color = K.Sub, font = Poppins, ltr = true) },
                    textStyle = TextStyle(fontFamily = Poppins, fontSize = 14.sp, color = K.Text),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = K.Gold, unfocusedBorderColor = K.Line, cursorColor = K.Gold),
                )
            }
        }
        T("تغییر DNS از اتصال بعدی اعمال می‌شود", size = 11.sp, color = K.Sub, weight = FontWeight.Normal, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun DnsRow(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(D.RowS).clip(RoundedCornerShape(D.RS)).clickable(onClick = onClick).padding(horizontal = D.S1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckMark(selected, D.Icon)
        Spacer(Modifier.width(D.S3))
        Column {
            T(title, size = 13.sp, maxLines = 1)
            AutoText(sub, size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal)
        }
    }
}

/** انتخاب تم: چهار تم AMOLED — زرشکی (پیش‌فرض)، طلایی، زرد کانکی، بنفش سلطنتی */
@Composable
fun ThemePicker(onSet: (String) -> Unit) {
    val items = listOf("crimson" to "زرشکی", "gold" to "طلایی", "yellow" to "زرد کانکی", "purple" to "بنفش سلطنتی")
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingIcon("palette", K.Accent)
            Spacer(Modifier.width(D.S3))
            T("تم برنامه", size = D.TxtTitle)
        }
        Spacer(Modifier.height(D.S3))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(D.S2)) {
            items.forEach { (id, name) ->
                val p = Themes.first { it.id == id }
                val sel = ThemeState.current.id == id
                val shape = RoundedCornerShape(D.RM)
                Column(
                    Modifier.weight(1f).clip(shape)
                        .background(Brush.verticalGradient(listOf(p.bgMid, p.bgBottom)))
                        .border(if (sel) 2.dp else 1.dp, if (sel) p.gold else p.gold.copy(alpha = .3f), shape)
                        .clickable { onSet(id) }
                        .padding(vertical = D.S3),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // سه رنگ خود همان تم: رنگ اصلی، لهجه و رنگ «وصل»
                    Row(horizontalArrangement = Arrangement.spacedBy(D.S1)) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(p.gold))
                        Box(Modifier.size(14.dp).clip(CircleShape).background(p.accent))
                        Box(Modifier.size(14.dp).clip(CircleShape).background(p.connected))
                    }
                    Spacer(Modifier.height(D.S2))
                    T(name, size = 11.sp, color = if (sel) p.gold else p.sub)
                }
            }
        }
    }
}

// ================================================================== پنل حساب

class AccountActions(
    val close: () -> Unit = {},
    val refresh: () -> Unit = {},
    val copy: () -> Unit = {},
    val renew: () -> Unit = {},
    val support: () -> Unit = {},
)

@Composable
fun AccountSheet(visible: Boolean, a: Account?, act: AccountActions) {
    AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Box(
            Modifier.fillMaxSize().background(Color(0xCC000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = act.close),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(visible, enter = scaleIn(initialScale = .92f) + fadeIn(), exit = scaleOut(targetScale = .95f) + fadeOut()) {
                KCard(
                    Modifier.padding(D.PagePad).fillMaxWidth()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                    padding = D.S4, border = K.Gold.copy(alpha = .5f),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoundIcon(D.IconBox + D.S2, filled = true, onClick = {}) { androidx.compose.foundation.Canvas(Modifier.size(D.Icon)) { accountIcon(K.Gold) } }
                        Spacer(Modifier.width(D.S3))
                        Column(Modifier.weight(1f)) {
                            AutoText(a?.username?.ifBlank { "—" } ?: "—", size = 17.sp)
                            T("اطلاعات حساب", size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal)
                        }
                        if (a != null) Pill(faStatus(a.status), color = if (isActive(a.status)) K.Green else K.Red)
                    }
                    if (a != null) {
                        Spacer(Modifier.height(D.S4))
                        val f = a.limitGb?.let { l -> a.usedGb?.let { u -> if (l > 0) ((l - u) / l).toFloat() else null } }
                        if (f != null) {
                            Row {
                                T("حجم باقی‌مانده", size = 12.sp, color = K.Sub, weight = FontWeight.Normal)
                                Spacer(Modifier.weight(1f))
                                AutoText(a.dataRemaining, size = 13.sp)
                            }
                            Spacer(Modifier.height(D.S2))
                            ProgressLine(f, if (f < .1f) K.Red else if (f < .25f) K.Warn else K.Green)
                            Spacer(Modifier.height(D.S2))
                        }
                        InfoRow("مصرف‌شده", a.used)
                        InfoRow("زمان باقی‌مانده", a.timeRemaining)
                        InfoRow("تاریخ انقضا", if (a.expiry == "—") "بدون محدودیت" else a.expiry)
                        InfoRow("اتصال همزمان", a.connections)
                    }
                    Spacer(Modifier.height(D.S4))
                    GoldButton("تمدید سرویس", Modifier.fillMaxWidth(), onClick = act.renew)
                    Spacer(Modifier.height(D.S2))
                    Row(horizontalArrangement = Arrangement.spacedBy(D.S2)) {
                        SmallLine("بروزرسانی", Modifier.weight(1f), onClick = act.refresh)
                        SmallLine("کپی سابلینک", Modifier.weight(1f), onClick = act.copy)
                        SmallLine("پشتیبانی", Modifier.weight(1f), onClick = act.support)
                    }
                    Spacer(Modifier.height(D.S1))
                    T("بستن", size = 13.sp, color = K.Sub, modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(D.RT)).clickable(onClick = act.close).padding(D.S2))
                }
            }
        }
    }
}
