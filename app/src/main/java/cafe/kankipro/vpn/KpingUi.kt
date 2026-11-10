package cafe.kankipro.vpn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * KPING در پنل گیمینگ: آمار زنده (هر ثانیه از هسته)، مسیرها (وای‌فای / دیتا / هر دو) و تنظیمات.
 * وقتی وصل نیست فقط توضیح و دکمه‌ی تنظیمات دیده می‌شود.
 */

private const val WIFI = "M2.5 9a14 14 0 0 1 19 0M5.5 12.3a9.5 9.5 0 0 1 13 0M8.6 15.6a5 5 0 0 1 6.8 0M12 19.2h.01"
private const val CELL = "M4 19v-3M9 19v-6M14 19v-9M19 19V5"
// دو فلش در یک دایره: هر دو مسیر با هم
private const val BOTH = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM8 10h8l-2.5-2.5M16 14H8l2.5 2.5"

@Composable
private fun PathIcon(d: String, on: Boolean, c: Color, size: Int = 20) {
    val col = if (on) c else K.Sub.copy(alpha = .45f)
    Canvas(Modifier.size(size.dp)) { iconPath(d, col, 2f) }
}

/** آمار زنده‌ی KPING و دکمه‌ی تنظیمات؛ زیر توضیح KPING در پنل گیمینگ */
@Composable
fun KpingPanel(c: Color) {
    var st by remember { mutableStateOf(HyEngine.kpStat.takeIf { HyEngine.kping }) }
    LaunchedEffect(Unit) {
        while (true) {
            st = HyEngine.kpStat.takeIf { HyEngine.kping }
            delay(1000)
        }
    }
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(D.RS)
    Column(
        Modifier.fillMaxWidth().padding(top = D.S2).clip(shape)
            .background(c.copy(alpha = .08f)).border(1.dp, c.copy(alpha = .25f), shape).padding(D.S3),
        verticalArrangement = Arrangement.spacedBy(D.S2),
    ) {
        val s = st
        if (s == null) {
            T("وقتی وصل شوی اینجا می‌بینی بسته‌ها از کدام راه می‌روند و چند بسته‌ی گم‌شده نجات پیدا کرد.", size = D.TxtSub, color = K.Sub, weight = FontWeight.Normal)
        } else {
            val wifi = s.paths.firstOrNull { it.name == "wifi" }
            val cell = s.paths.firstOrNull { it.name == "cell" }
            val both = wifi?.alive == true && cell?.alive == true
            // مسیرها: وای‌فای، دیتا، و دایره‌ی دو فلش وقتی هر دو با هم کار می‌کنند
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(D.S2)) {
                if (both) PathIcon(BOTH, true, c, 22)
                PathIcon(WIFI, wifi?.alive == true, c)
                PathIcon(CELL, cell?.alive == true, c)
                Spacer(Modifier.width(D.S1))
                T(
                    when {
                        both -> "وای‌فای و دیتا با هم: هر بسته از هر دو"
                        wifi?.alive == true -> "فقط وای‌فای"
                        cell?.alive == true -> "فقط اینترنت موبایل"
                        else -> "در حال پیدا کردن مسیر…"
                    }, size = 12.sp, color = K.Text,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(D.S2)) {
                Stat("پینگ", if (s.ping > 0) "${s.ping.toInt()} ms" else "—", Modifier.weight(1f))
                Stat("لرزش", if (s.ping > 0) "${s.jit.toInt()} ms" else "—", Modifier.weight(1f))
                Stat("نجات‌یافته", "${s.rescued + s.fixed}", Modifier.weight(1f))
            }
            // هر مسیر: پینگ، افت خودش، و چند بسته را فقط همین مسیر رساند (دیگری گمش کرده بود)
            s.paths.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PathIcon(if (p.name == "wifi") WIFI else CELL, p.alive, c, 16)
                    Spacer(Modifier.width(D.S2))
                    T(if (p.name == "wifi") "وای‌فای" else "دیتا", size = 11.sp, color = K.Text, modifier = Modifier.width(56.dp))
                    T(
                        if (!p.alive) tr("قطع")
                        else trf("%d ms · افت %d٪ · %d نجات", p.rtt.toInt(), p.loss.toInt(), p.rescued),
                        size = 11.sp, color = if (p.alive) K.Sub else K.Warn, weight = FontWeight.Normal,
                    )
                }
            }
            T(
                trf("بازسازی با پریتی: %d · نسخه‌ی دوم: %s · پریتی: %s%s", s.fixed,
                    if (s.dupMs > 0) "${s.dupMs} ms" else tr("خاموش"),
                    if (s.fec >= 2) trf("هر %d بسته", s.fec) else tr("خاموش"),
                    if (s.auto) " · " + tr("خودکار") else ""),
                size = 10.sp, color = K.Sub, weight = FontWeight.Normal,
            )
        }
        SmallLine(if (open) "بستن تنظیمات KPING" else "تنظیمات KPING", onClick = { open = !open })
        AnimatedVisibility(open) { KpingSettings(c) }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x0CFFFFFF)).padding(vertical = D.S2), horizontalAlignment = Alignment.CenterHorizontally) {
        T(value, size = 14.sp, color = K.Text, weight = FontWeight.Black, ltr = true)
        T(label, size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
    }
}

/** تنظیمات KPING: خودکار (پیش‌فرض) یا دستی؛ کدام شبکه‌ها */
@Composable
private fun KpingSettings(c: Color) {
    val ctx = LocalContext.current
    var v by remember { mutableStateOf(KpingCfg.prefs(ctx)) }
    fun set(n: KpingCfg.Prefs) { v = n; KpingCfg.save(ctx, n) }
    Column(Modifier.fillMaxWidth().padding(top = D.S1), verticalArrangement = Arrangement.spacedBy(D.S1)) {
        T("مسیرها (با اتصال بعدی)", size = 12.sp, color = K.Text)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(D.S1)) {
            listOf(KpingCfg.Paths.BOTH to "هر دو", KpingCfg.Paths.WIFI to "فقط وای‌فای", KpingCfg.Paths.CELL to "فقط دیتا").forEach { (p, t) ->
                Chip(t, v.paths == p, c, Modifier.weight(1f)) { set(v.copy(paths = p)) }
            }
        }
        T("«هر دو» بهترین است: وقتی یکی یک لحظه قطع شود، همان بسته از دیگری می‌رسد. دیتای موبایل کنار وای‌فای روشن می‌ماند (کمی حجم دیتا).", size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("خودکار", size = 13.sp, color = K.Text)
                T("هسته هر مسیر را با بسته‌هایی به اندازه‌ی بسته‌ی پابجی می‌سنجد و فاصله‌ی نسخه‌ی دوم و پریتی را خودش انتخاب می‌کند؛ با یک افت لحظه‌ای چیزی را عوض نمی‌کند.", size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
            }
            Switch(v.auto, { set(v.copy(auto = it)) }, colors = SwitchDefaults.colors(checkedThumbColor = K.OnGold, checkedTrackColor = K.ToggleOn, uncheckedTrackColor = K.Panel))
        }
        if (!v.auto) {
            T("نسخه‌ی دوم وقتی یک مسیر داری", size = 12.sp, color = K.Text)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(D.S1)) {
                listOf(0, 5, 10, 15, 20).forEach { ms -> Chip(if (ms == 0) "خاموش" else "$ms ms", v.dupMs == ms, c, Modifier.weight(1f)) { set(v.copy(dupMs = ms)) } }
            }
            T("پریتی (یک بسته‌ی ترمیم برای هر …)", size = 12.sp, color = K.Text)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(D.S1)) {
                listOf(0, 2, 4, 8).forEach { k -> Chip(if (k == 0) "خاموش" else "$k", v.fec == k, c, Modifier.weight(1f)) { set(v.copy(fec = k)) } }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("نسخه‌ی دوم روی هر دو مسیر", size = 13.sp, color = K.Text)
                    T("وقتی وای‌فای و دیتا هر دو ضعیف‌اند (حجم بیشتر)", size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
                }
                Switch(v.every, { set(v.copy(every = it)) }, colors = SwitchDefaults.colors(checkedThumbColor = K.OnGold, checkedTrackColor = K.ToggleOn, uncheckedTrackColor = K.Panel))
            }
        }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, c: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.height(36.dp).clip(shape)
            .background(if (on) c.copy(alpha = .18f) else Color(0x08FFFFFF))
            .border(if (on) 1.5.dp else 1.dp, if (on) c else K.Gold.copy(alpha = .18f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T(text, size = 11.sp, color = if (on) K.Text else K.Sub, maxLines = 1) }
}
