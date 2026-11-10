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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
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
    // ۲۴ ثانیه‌ی آخر هر شاخص، برای نمودار کوچک هر کارت
    val hist = remember { List(4) { mutableStateListOf<Float>() } }
    LaunchedEffect(Unit) {
        while (true) {
            st = HyEngine.kpStat.takeIf { HyEngine.kping }
            st?.let { s ->
                val vals = listOf(s.ping.toFloat(), s.jit.toFloat(), kpLoss(s).toFloat(), (s.rescued + s.fixed).toFloat())
                vals.forEachIndexed { i, v -> hist[i].add(v); if (hist[i].size > 24) hist[i].removeAt(0) }
            }
            delay(1000)
        }
    }
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(D.RL)
    Column(
        Modifier.fillMaxWidth().padding(top = D.S2).goldGlass(shape, tint = c).padding(D.S3),
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
            // چهار شاخص زنده، هر کدام کارت کوچک با آیکون و نمودار ۲۴ ثانیه‌ی آخر
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(D.S2)) {
                Metric("پینگ", if (s.ping > 0) "${s.ping.toInt()}" else "—", "ms", ICON_PING, hist[0], c, false, Modifier.weight(1f))
                Metric("لرزش", if (s.ping > 0) "${s.jit.toInt()}" else "—", "ms", ICON_JIT, hist[1], c, false, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(D.S2)) {
                Metric("افت", String.format(java.util.Locale.US, "%.1f", kpLoss(s)), "%", ICON_LOSS, hist[2], c, false, Modifier.weight(1f))
                Metric("نجات‌یافته", "${s.rescued + s.fixed}", "", ICON_SAVE, hist[3], c, true, Modifier.weight(1f))
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

private const val ICON_PING = "M3 12h4l2-6 4 12 2-6h6"
private const val ICON_JIT = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 7v5l3 2"
private const val ICON_LOSS = "M12 4v13m0 0 5-5m-5 5-5-5M5 21h14"
private const val ICON_SAVE = "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10ZM9 12l2 2 4-4"

/** افت مؤثر: وقتی هر دو مسیر زنده‌اند کمترینِ دو مسیر (هر بسته از هر دو می‌رود)، وگرنه افت همان مسیر */
private fun kpLoss(s: KpStat): Double = s.paths.filter { it.alive }.minOfOrNull { it.loss } ?: 0.0

/** یک شاخص KPING: آیکون در دکمه‌ی کوچک، اسم و عدد، و زیرش نمودار کوچک ۲۴ ثانیه‌ی آخر با نقطه‌ی نورانی سرِ آن */
@Composable
private fun Metric(label: String, value: String, unit: String, icon: String, hist: List<Float>, c: Color, hot: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(D.RM)
    Column(modifier.goldGlass(shape, selected = hot, tint = c).padding(horizontal = D.S2, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).then(if (hot) Modifier.goldFill(RoundedCornerShape(8.dp)) else Modifier.raisedCircle(c)), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(14.dp)) { iconPath(icon, if (hot) K.OnGold else c, 2.2f) }
            }
            Spacer(Modifier.width(6.dp))
            T(label, size = 10.5.sp, color = K.Sub, weight = FontWeight.Normal, maxLines = 1, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                T(value, size = 15.sp, color = if (hot) c else K.Text, font = Poppins, weight = FontWeight.Bold, ltr = true, maxLines = 1)
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(3.dp))
                    T(unit, size = 9.5.sp, color = K.Sub, font = Poppins, ltr = true, modifier = Modifier.padding(bottom = 2.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            if (hist.size < 2) {
                drawLine(c.copy(alpha = .25f), Offset(0f, size.height * .7f), Offset(size.width, size.height * .7f), 1.dp.toPx())
                return@Canvas
            }
            val lo = hist.min(); val hi = hist.max()
            val span = (hi - lo).coerceAtLeast(1e-3f)
            val pts = hist.mapIndexed { i, v -> Offset(size.width * i / (hist.size - 1), size.height - 2.dp.toPx() - (v - lo) / span * (size.height - 4.dp.toPx())) }
            val line = Path().apply { pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
            val area = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(area, c.copy(alpha = .14f))
            drawPath(line, c, style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(c.copy(alpha = .4f), 3.5.dp.toPx(), pts.last())
            drawCircle(Color.White, 1.8.dp.toPx(), pts.last())
        }
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
