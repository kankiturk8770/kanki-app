package cafe.kankipro.vpn

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// تب تست سرعت (۱.۳۵): دو بخش «تست سرعت» (از همان مسیری که الان وصلی) و «اینترنت من» (بیرون از VPN)

/** مرحله‌های تست: "" (آماده)، server، ping، down، up، done */
data class SpeedUi(
    val running: Boolean = false,
    val phase: String = "",
    val live: Double = 0.0,
    val down: Double? = null,
    val up: Double? = null,
    /** اوج (صدک ۹۰) دانلود و آپلود */
    val downPeak: Double? = null,
    val upPeak: Double? = null,
    /** پینگ بی‌بار و جیتر (ms) */
    val ping: Double? = null,
    val jitter: Double? = null,
    /** پینگ زیر بار دانلود و آپلود (ms، میانه) */
    val loadedDown: Double? = null,
    val loadedUp: Double? = null,
    /** پکت‌لاس UDP (درصد) و مقصد سنجش UDP */
    val loss: Double? = null,
    val udpTarget: String? = null,
    /** نمودارها: نمونه‌های پینگ، دانلود و آپلود */
    val pingPts: List<Float> = emptyList(),
    val downPts: List<Float> = emptyList(),
    val upPts: List<Float> = emptyList(),
    val server: String? = null,
    /** محل سرور Cloudflare (کد فرودگاه، مثل LHR)، IP بیرونی و شرکت اینترنت مسیر تست */
    val colo: String? = null,
    val ip: String? = null,
    val isp: String? = null,
    val where: String? = null,
    val viaVpn: Boolean = false,
    /** کشور سروری که الان به آن وصلیم (برای زیرعنوان صفحه) */
    val vpnPlace: String? = null,
    val usedBytes: Long? = null,
    val error: String? = null,
    /** quick (حدود ۲۰ ثانیه) یا full (حدود ۳۵ ثانیه، دقیق‌تر) */
    val mode: String = "quick",
    /** auto (نزدیک‌ترین Speedtest، بعد Cloudflare) یا cf */
    val pick: String = "auto",
) {
    val hasResult: Boolean get() = down != null || up != null || ping != null
    fun scores(): Map<String, Int> = Aim.scores(ping, jitter, loss, down, loadedDown, loadedUp)
    /** بیشترین افزایش پینگ زیر بار (ms) */
    val bloat: Double? get() = ping?.let { p -> listOfNotNull(loadedDown, loadedUp).maxOrNull()?.let { (it - p).coerceAtLeast(0.0) } }
}

/** یک نتیجه‌ی ذخیره‌شده (تاریخچه)؛ IP و شرکت اینترنت ذخیره نمی‌شود */
data class SpeedRecord(
    val time: Long,
    /** vpn یا direct */
    val kind: String,
    val place: String?,
    val down: Double?,
    val up: Double?,
    val ping: Double?,
    val jitter: Double?,
    val loss: Double?,
    val loadedDown: Double?,
    val loadedUp: Double?,
    val server: String?,
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("t", time); put("k", kind); place?.let { put("p", it) }; server?.let { put("s", it) }
        listOf("d" to down, "u" to up, "pi" to ping, "j" to jitter, "l" to loss, "ld" to loadedDown, "lu" to loadedUp)
            .forEach { (k, v) -> v?.let { put(k, it) } }
    }

    companion object {
        fun fromJson(o: org.json.JSONObject): SpeedRecord? = runCatching {
            fun d(k: String) = if (o.has(k)) o.getDouble(k).takeIf { it.isFinite() && it >= 0 } else null
            fun s(k: String) = o.optString(k).takeIf { it.isNotBlank() }?.take(80)
            SpeedRecord(o.getLong("t"), if (o.optString("k") == "direct") "direct" else "vpn", s("p"), d("d"), d("u"), d("pi"), d("j"), d("l"), d("ld"), d("lu"), s("s"))
        }.getOrNull()

        fun of(ui: SpeedUi, kind: String, place: String?, now: Long) =
            SpeedRecord(now, kind, place, ui.down, ui.up, ui.ping, ui.jitter, ui.loss, ui.loadedDown, ui.loadedUp, ui.server)
    }
}

/** یک ردیف سنجش UDP در «اینترنت من» */
data class UdpRow(val name: String, val ping: Long?, val jitter: Long?, val loss: Double?)

/** «اینترنت من»: قدم فعلی (۰ تا ۷)، نتیجه‌ی هر قدم و تست سرعت مستقیم */
data class NetUi(
    val running: Boolean = false,
    val step: Int = -1,
    val withSpeed: Boolean = true,
    /** VPN کانکی وصل بود و بررسی بیرون از آن انجام شد */
    val bypass: Boolean = false,
    val info: NetCheck.Info? = null,
    val meta: SpeedEngine.Meta? = null,
    val timing: NetCheck.Timing? = null,
    val targets: List<NetCheck.TargetResult> = emptyList(),
    val udp: List<UdpRow> = emptyList(),
    val sites: List<NetCheck.SiteResult> = emptyList(),
    val speed: SpeedUi? = null,
    val error: String? = null,
    val done: Boolean = false,
)

val NET_STEPS = listOf("شبکه‌ی گوشی", "IP و شرکت اینترنت", "زمان‌بندی اتصال", "پینگ مقصدها", "UDP", "سایت‌ها", "سرعت")

/** کارهای صفحه */
class SpeedActions(
    val start: () -> Unit = {},
    val section: (String) -> Unit = {},
    val mode: (String) -> Unit = {},
    val pick: (String) -> Unit = {},
    val netStart: () -> Unit = {},
    val netSpeed: (Boolean) -> Unit = {},
    val share: (String) -> Unit = {},
    val clearHistory: () -> Unit = {},
    val closeTip: () -> Unit = {},
)

private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
private fun mbps(v: Double) = if (v >= 100) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)
private fun ms(v: Double) = if (v >= 10) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)
private fun pct(v: Double) = if (v == 0.0 || v >= 10) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)

val SCORE_NAMES = listOf("بد", "ضعیف", "متوسط", "خوب", "عالی")
fun scoreColor(i: Int): Color = when (i) { 0, 1 -> K.Red; 2 -> K.Warn; else -> K.Good }
private fun lossColor(l: Double?) = when { l == null -> K.Sub; l == 0.0 -> K.Good; l < 2 -> K.Warn; else -> K.Red }
private fun msColor(v: Double?) = v?.let { pingColor(it.toLong()) } ?: K.Sub

@Composable
fun SpeedContent(
    ui: SpeedUi,
    net: NetUi = NetUi(),
    section: String = "speed",
    history: List<SpeedRecord> = emptyList(),
    tip: RobotAlert? = null,
    act: SpeedActions = SpeedActions(),
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = D.PagePad)) {
        Spacer(Modifier.height(D.S2))
        Heading(
            "تست سرعت",
            if (section == "net") "اینترنت خود گوشی، بیرون از VPN"
            else if (ui.viaVpn) (ui.vpnPlace?.let { trf("متصل به VPN · %s", it) } ?: "از داخل تونل کانکی") else "بدون VPN",
        )
        Spacer(Modifier.height(D.S3))
        Segmented(
            listOf(SegItem("speed", "تست سرعت", "bolt"), SegItem("net", "اینترنت من", "ping")),
            section, enabled = !ui.running && !net.running, onSelect = act.section,
        )
        tip?.let { Spacer(Modifier.height(D.S3)); RobotBanner(it, onClose = act.closeTip) }
        Spacer(Modifier.height(D.S3))
        if (section == "net") NetSection(net, act) else SpeedSection(ui, history, act)
        Spacer(Modifier.height(D.S6))
    }
}

// ------------------------------------------------------------------ بخش تست سرعت

@Composable
private fun SpeedSection(ui: SpeedUi, history: List<SpeedRecord>, act: SpeedActions) {
    KCard(Modifier.fillMaxWidth(), padding = D.CardPad) {
        Row(
            Modifier.align(Alignment.CenterHorizontally).goldGlass(RoundedCornerShape(50)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(D.S2),
        ) {
            PhaseChip("ping", "پینگ", ui)
            PhaseChip("down", "دانلود", ui)
            PhaseChip("up", "آپلود", ui)
        }
        Spacer(Modifier.height(D.S2))
        val gaugeColor = if (ui.phase == "up") K.Accent else if (ui.phase == "done") K.Green else K.Gold
        val shown = if (ui.running && (ui.phase == "down" || ui.phase == "up")) ui.live else ui.down ?: 0.0
        Box(Modifier.fillMaxWidth().height(236.dp).guideTarget("speed"), contentAlignment = Alignment.BottomCenter) {
            SpeedGauge(shown, gaugeColor, Modifier.fillMaxSize())
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 2.dp)) {
                T(f1(shown), size = 40.sp, font = Poppins, weight = FontWeight.Bold, shadow = glow(gaugeColor, 18f), ltr = true)
                T(
                    when (ui.phase) {
                        "server" -> "در حال پیدا کردن نزدیک‌ترین سرور…"
                        "ping" -> "در حال سنجش پینگ…"
                        "down" -> "دانلود · Mbps"
                        "up" -> "آپلود · Mbps"
                        "done" -> "دانلود · Mbps"
                        else -> "Mbps"
                    },
                    size = 12.sp, color = gaugeColor,
                )
            }
        }
        Spacer(Modifier.height(D.S3))
        T("مدت تست", size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
        Spacer(Modifier.height(D.S1))
        Segmented(
            listOf(SegItem("quick", "سریع · ۲۰ ثانیه"), SegItem("full", "کامل · ۴۰ ثانیه")),
            ui.mode, enabled = !ui.running, onSelect = act.mode,
        )
        Spacer(Modifier.height(D.S2))
        T("سرور تست", size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
        Spacer(Modifier.height(D.S1))
        Segmented(
            listOf(SegItem("auto", "نزدیک‌ترین (Speedtest)"), SegItem("cf", "Cloudflare")),
            ui.pick, enabled = !ui.running, onSelect = act.pick,
        )
        ui.error?.let { Spacer(Modifier.height(D.S2)); T(it, size = 12.sp, color = K.Red) }
        Spacer(Modifier.height(D.S3))
        GoldButton(
            when {
                ui.running -> "در حال تست…"
                ui.phase == "done" -> "تست دوباره"
                else -> "شروع تست سرعت"
            },
            Modifier.fillMaxWidth(), loading = ui.running, onClick = act.start,
        )
        Spacer(Modifier.height(D.S2))
        T(
            ui.usedBytes?.let { trf("مصرف اینترنت این تست: %s", ltr(formatBytes(it))) }
                ?: "پینگ بی‌بار و زیر بار، جیتر و پکت‌لاس UDP، بعد دانلود و آپلود با چند اتصال همزمان. وصل باشی، از داخل VPN سنجیده می‌شه.",
            size = 11.sp, color = K.Sub, weight = FontWeight.Normal,
        )
    }
    if (ui.hasResult || ui.running) {
        Spacer(Modifier.height(D.CardGap))
        SpeedResults(ui)
        if (ui.phase == "done") {
            Spacer(Modifier.height(D.CardGap))
            LineButton("اشتراک‌گذاری نتیجه", Modifier.fillMaxWidth()) { act.share(shareText(ui, null)) }
        }
    }
    if (history.isNotEmpty()) {
        Spacer(Modifier.height(D.CardGap))
        HistoryCard(history, act.clearHistory)
    }
}

/** کارت‌های نتیجه (هم تست سرعت، هم «اینترنت من») */
@Composable
fun SpeedResults(ui: SpeedUi) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(D.CardGap)) {
        val dl = if (ui.running && ui.phase == "down") ui.live else ui.down
        val upv = if (ui.running && ui.phase == "up") ui.live else ui.up
        BwCard("دانلود", "down", dl, ui.downPeak, ui.downPts, K.Gold, ui.running && ui.phase == "down", Modifier.weight(1f))
        BwCard("آپلود", "up", upv, ui.upPeak, ui.upPts, K.Accent, ui.running && ui.phase == "up", Modifier.weight(1f))
    }
    Spacer(Modifier.height(D.CardGap))
    LatencyCard(ui)
    val sc = ui.scores()
    if (sc.isNotEmpty() && !ui.running) {
        Spacer(Modifier.height(D.CardGap))
        ScoresCard(sc)
    }
    if (ui.server != null || ui.ip != null) {
        Spacer(Modifier.height(D.CardGap))
        KCard(Modifier.fillMaxWidth()) {
            CardTitle("server", "مسیر تست")
            Spacer(Modifier.height(D.S2))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ui.server?.let { FactRow("سرور تست", it, K.Text) }
                ui.colo?.let { FactRow("مرکز داده‌ی Cloudflare", it, K.Text) }
                ui.where?.let { FactRow("محل اینترنت (از دید سایت‌ها)", it, K.Text) }
                ui.isp?.let { FactRow("شرکت اینترنت", it, K.Text) }
                ui.ip?.let { FactRow("IP بیرونی", ltr(it), K.Text) }
                ui.udpTarget?.let { FactRow("مقصد UDP", it, K.Text) }
                ui.usedBytes?.let { FactRow("حجم مصرف‌شده", ltr(formatBytes(it)), K.Text) }
            }
        }
    }
}

@Composable
private fun CardTitle(icon: String, title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(D.IconBoxS).clip(RoundedCornerShape(D.RT)).background(K.Gold.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            MiniIcon(icon, K.Gold, 14.dp)
        }
        Spacer(Modifier.width(D.S2))
        T(title, size = D.TxtTitle, weight = FontWeight.Black, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun BwCard(label: String, icon: String, value: Double?, peak: Double?, pts: List<Float>, c: Color, live: Boolean, modifier: Modifier) {
    KCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniIcon(icon, c, 13.dp)
            Spacer(Modifier.width(D.S1))
            T(label, size = 12.sp, color = K.Sub)
            if (live) { Spacer(Modifier.width(D.S1)); Spinner(10.dp, c) }
        }
        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.Bottom) {
                T(value?.let { mbps(it) } ?: "–", size = 26.sp, font = Poppins, weight = FontWeight.Bold, color = if (value == null) K.Sub else K.Text, ltr = true)
                Spacer(Modifier.width(4.dp))
                T("Mbps", size = 11.sp, font = Poppins, weight = FontWeight.SemiBold, color = K.Sub, ltr = true, modifier = Modifier.padding(bottom = 5.dp))
            }
        }
        Spacer(Modifier.height(D.S1))
        Chart(pts, c, peak?.toFloat(), Modifier.fillMaxWidth().height(52.dp))
        Spacer(Modifier.height(D.S1))
        T(peak?.let { trf("اوج (۹۰٪): %s Mbps", ltr(mbps(it))) } ?: " ", size = 10.sp, color = K.Sub, weight = FontWeight.Normal, maxLines = 1)
    }
}

/** نمودار ناحیه‌ای کوچک (مثل نمودار دانلود و آپلود Cloudflare) با خط چین «اوج» */
@Composable
fun Chart(pts: List<Float>, c: Color, peak: Float?, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawLine(K.Sub.copy(alpha = .18f), Offset(0f, h), Offset(w, h), 1.dp.toPx())
        if (pts.size < 2) return@Canvas
        val top = maxOf(pts.maxOrNull() ?: 0f, peak ?: 0f, .1f) * 1.12f
        val step = w / (pts.size - 1)
        fun y(v: Float) = h - (v / top).coerceIn(0f, 1f) * h
        // راست‌به‌چپ صفحه مهم نیست: زمان همیشه از چپ به راست
        val line = Path()
        pts.forEachIndexed { i, v -> if (i == 0) line.moveTo(0f, y(v)) else line.lineTo(i * step, y(v)) }
        val area = Path().apply { addPath(line); lineTo(w, h); lineTo(0f, h); close() }
        drawPath(area, Brush.verticalGradient(listOf(c.copy(alpha = .45f), c.copy(alpha = .02f))))
        drawPath(line, c, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        peak?.takeIf { it > 0f }?.let { p ->
            drawLine(K.Text.copy(alpha = .45f), Offset(0f, y(p)), Offset(w, y(p)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
    }
}

@Composable
private fun LatencyCard(ui: SpeedUi) {
    KCard(Modifier.fillMaxWidth()) {
        CardTitle("ping", "پینگ و پایداری") {
            ui.bloat?.let { b -> GradeBadge(Aim.bloatGrade(b)) }
        }
        Spacer(Modifier.height(D.S3))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(D.S2)) {
            Metric("پینگ", ui.ping?.let { ms(it) }, "ms", msColor(ui.ping), Modifier.weight(1f)) {
                ui.loadedDown?.let { SubLine("↓", ms(it), K.Gold) }
                ui.loadedUp?.let { SubLine("↑", ms(it), K.Accent) }
            }
            Metric("لرزش (جیتر)", ui.jitter?.let { ms(it) }, "ms", ui.jitter?.let { if (it < 10) K.Good else if (it < 30) K.Warn else K.Red } ?: K.Sub, Modifier.weight(1f)) {}
            Metric("پکت‌لاس", ui.loss?.let { pct(it) }, "%", lossColor(ui.loss), Modifier.weight(1f)) {}
        }
        if (ui.pingPts.size >= 3) {
            Spacer(Modifier.height(D.S2))
            Chart(ui.pingPts, K.Gold2, null, Modifier.fillMaxWidth().height(36.dp))
        }
        ui.bloat?.let { b ->
            Spacer(Modifier.height(D.S2))
            T(
                trf("وقتی دانلود یا آپلود هست، پینگ %s میلی‌ثانیه بالا می‌رود (رتبه %s). عدد کمتر یعنی بازی و تماس وسط دانلود هم روان می‌ماند.", ms(b), Aim.bloatGrade(b)),
                size = 11.sp, color = K.Sub, weight = FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun GradeBadge(g: String) {
    val c = when (g) { "A+", "A" -> K.Good; "B", "C" -> K.Warn; else -> K.Red }
    val sh = RoundedCornerShape(50)
    Row(Modifier.clip(sh).background(c.copy(alpha = .14f)).border(1.dp, c.copy(alpha = .5f), sh).padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        T("بافربلوت", size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
        Spacer(Modifier.width(4.dp))
        T(g, size = 12.sp, font = Poppins, color = c, ltr = true)
    }
}

@Composable
private fun Metric(label: String, value: String?, unit: String, c: Color, modifier: Modifier, extra: @Composable ColumnScope.() -> Unit) {
    Column(modifier.goldGlass(RoundedCornerShape(D.RM)).padding(D.S2)) {
        T(label, size = 11.sp, color = K.Sub, weight = FontWeight.Normal, maxLines = 1)
        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.Bottom) {
                T(value ?: "–", size = 20.sp, font = Poppins, weight = FontWeight.Bold, color = if (value == null) K.Sub else c, ltr = true)
                Spacer(Modifier.width(3.dp))
                T(unit, size = 10.sp, font = Poppins, color = K.Sub, ltr = true, modifier = Modifier.padding(bottom = 3.dp))
            }
            extra()
        }
    }
}

@Composable
private fun SubLine(arrow: String, v: String, c: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        T(arrow, size = 11.sp, font = Poppins, color = c, ltr = true)
        Spacer(Modifier.width(3.dp))
        T("$v ms", size = 11.sp, font = Poppins, weight = FontWeight.SemiBold, color = K.Text, ltr = true)
    }
}

@Composable
private fun ScoresCard(sc: Map<String, Int>) {
    KCard(Modifier.fillMaxWidth()) {
        CardTitle("spark", "امتیاز کیفیت شبکه")
        Spacer(Modifier.height(D.S3))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(D.S2)) {
            listOf("streaming" to "پخش ویدیو", "gaming" to "بازی", "rtc" to "تماس تصویری").forEach { (k, name) ->
                val v = sc[k]
                Column(Modifier.weight(1f).goldGlass(RoundedCornerShape(D.RM)).padding(D.S2), horizontalAlignment = Alignment.CenterHorizontally) {
                    T(name, size = 12.sp, maxLines = 1)
                    Spacer(Modifier.height(D.S1))
                    Stars(v?.let { it + 1 } ?: 0, v?.let { scoreColor(it) } ?: K.Sub)
                    Spacer(Modifier.height(D.S1))
                    T(v?.let { SCORE_NAMES[it] } ?: "–", size = 11.sp, color = v?.let { scoreColor(it) } ?: K.Sub)
                }
            }
        }
        Spacer(Modifier.height(D.S2))
        T("به روش امتیاز کیفیت Cloudflare (AIM): از پینگ، لرزش، پکت‌لاس، سرعت و پینگ زیر بار.", size = 10.sp, color = K.Sub, weight = FontWeight.Normal)
    }
}

/** پنج ستاره؛ n تای اول رنگی */
@Composable
fun Stars(n: Int, c: Color, size: Dp = 14.dp) {
    Canvas(Modifier.width(size * 5 + 8.dp).height(size)) {
        val s = size.toPx()
        val gap = (this.size.width - s * 5) / 4
        for (i in 0 until 5) {
            // ستاره‌ها از راست (راست‌به‌چپ) پر می‌شوند تا با جهت فارسی بخواند
            val cx = this.size.width - (s / 2 + i * (s + gap))
            val p = Path()
            for (k in 0 until 10) {
                val r = if (k % 2 == 0) s / 2 else s / 4.6f
                val a = Math.toRadians((-90 + k * 36).toDouble())
                val x = cx + r * kotlin.math.cos(a).toFloat()
                val y = s / 2 + r * kotlin.math.sin(a).toFloat()
                if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            if (i < n) drawPath(p, c) else drawPath(p, K.Sub.copy(alpha = .5f), style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable
private fun HistoryCard(h: List<SpeedRecord>, clear: () -> Unit) {
    KCard(Modifier.fillMaxWidth()) {
        CardTitle("clock", "تاریخچه‌ی تست‌ها") {
            T("پاک کردن", size = 12.sp, color = K.Gold, modifier = Modifier.clip(RoundedCornerShape(D.RT)).pressable(onClick = clear).padding(horizontal = D.S2, vertical = D.S1))
        }
        Spacer(Modifier.height(D.S2))
        // مقایسه: آخرین تست با VPN کنار آخرین تست بی VPN
        val v = h.firstOrNull { it.kind == "vpn" }
        val d = h.firstOrNull { it.kind == "direct" }
        if (v != null && d != null) {
            Row(Modifier.fillMaxWidth().goldGlass(RoundedCornerShape(D.RM)).padding(D.S2), verticalAlignment = Alignment.CenterVertically) {
                T("مقایسه", size = 11.sp, color = K.Sub, modifier = Modifier.width(56.dp))
                CompareCol("با VPN", v, Modifier.weight(1f))
                CompareCol("اینترنت من", d, Modifier.weight(1f))
            }
            Spacer(Modifier.height(D.S2))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            h.take(8).forEach { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (r.kind == "vpn") K.Green else K.Accent, 6.dp)
                    Spacer(Modifier.width(D.S2))
                    Column(Modifier.weight(1f)) {
                        T(if (r.kind == "vpn") (r.place?.let { trf("VPN · %s", it) } ?: "با VPN") else "اینترنت من", size = 12.sp, maxLines = 1)
                        T(ltr(whenText(r.time)), size = 10.sp, color = K.Sub, weight = FontWeight.Normal, maxLines = 1)
                    }
                    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            T("↓ " + (r.down?.let { mbps(it) } ?: "–"), size = 11.sp, font = Poppins, color = K.Gold, ltr = true)
                            Spacer(Modifier.width(D.S2))
                            T("↑ " + (r.up?.let { mbps(it) } ?: "–"), size = 11.sp, font = Poppins, color = K.Accent, ltr = true)
                            Spacer(Modifier.width(D.S2))
                            T((r.ping?.let { ms(it) } ?: "–") + " ms", size = 11.sp, font = Poppins, color = msColor(r.ping), ltr = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompareCol(title: String, r: SpeedRecord, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        T(title, size = 11.sp, color = K.Sub, maxLines = 1)
        T(ltr("↓ ${r.down?.let { mbps(it) } ?: "–"} · ${r.ping?.let { ms(it) } ?: "–"} ms"), size = 11.sp, font = Poppins, ltr = true, maxLines = 1)
    }
}

private fun whenText(t: Long): String =
    java.text.SimpleDateFormat("MM/dd HH:mm", Locale.US).format(java.util.Date(t))

/** متن نتیجه برای اشتراک‌گذاری (بی IP و بی کد اشتراک) */
fun shareText(ui: SpeedUi, title: String?): String = buildString {
    appendLine(title ?: if (ui.viaVpn) "Kanki VPN speed test" + (ui.vpnPlace?.let { " · $it" } ?: "") else "Kanki speed test (no VPN)")
    ui.down?.let { appendLine("Download: ${mbps(it)} Mbps") }
    ui.up?.let { appendLine("Upload: ${mbps(it)} Mbps") }
    ui.ping?.let { p -> append("Latency: ${ms(p)} ms"); ui.loadedDown?.let { append(" (↓ ${ms(it)})") }; ui.loadedUp?.let { append(" (↑ ${ms(it)})") }; appendLine() }
    ui.jitter?.let { appendLine("Jitter: ${ms(it)} ms") }
    ui.loss?.let { appendLine("Packet loss: ${pct(it)}%") }
    val sc = ui.scores()
    val en = listOf("Bad", "Poor", "Average", "Good", "Great")
    if (sc.isNotEmpty()) appendLine(listOf("streaming" to "Streaming", "gaming" to "Gaming", "rtc" to "Video calls").mapNotNull { (k, n) -> sc[k]?.let { "$n: ${en[it]}" } }.joinToString(" · "))
    ui.server?.let { append("Server: $it") }
}.trim()

@Composable
private fun PhaseChip(key: String, label: String, ui: SpeedUi) {
    val order = listOf("server", "ping", "down", "up", "done")
    val cur = order.indexOf(ui.phase)
    val me = order.indexOf(key)
    val done = cur > me || ui.phase == "done"
    val active = ui.running && (cur == me || (key == "ping" && ui.phase == "server"))
    val c = when { active -> K.Gold; done -> K.Green; else -> K.Sub.copy(alpha = .6f) }
    val sh = RoundedCornerShape(50)
    Row(
        Modifier.height(26.dp).clip(sh).background(c.copy(alpha = .12f)).border(1.dp, c.copy(alpha = .45f), sh).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            active -> Spinner(11.dp, c)
            done -> CheckMark(true, 13.dp)
            else -> Dot(c, 6.dp)
        }
        Spacer(Modifier.width(5.dp))
        T(label, size = 11.sp, color = c, weight = FontWeight.Bold)
    }
}

/** یک ردیف اطلاعات: اسم در یک طرف، مقدار (رنگی) در طرف دیگر */
@Composable
private fun FactRow(label: String, value: String, c: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Dot(c, 5.dp)
        Spacer(Modifier.width(D.S2))
        T(label, size = 11.sp, color = K.Sub, weight = FontWeight.Normal, maxLines = 1)
        Spacer(Modifier.weight(1f).widthIn(min = D.S3))
        AutoText(value, size = 11.5.sp, color = c, weight = FontWeight.Bold)
    }
}

// ------------------------------------------------------------------ بخش «اینترنت من»

/** جمع‌بندی «اینترنت من»: هر خط (رنگ، متن) */
fun netVerdict(n: NetUi): List<Pair<Int, String>> {
    // رنگ: ۰ خوب، ۱ توجه، ۲ بد
    val out = ArrayList<Pair<Int, String>>()
    n.info?.let { if (!it.validated) out.add(2 to "اندروید این شبکه را «بی اینترنت» می‌شناسد (شاید وای‌فای بدون اینترنت یا نیاز به ورود)") }
    val global = n.targets.filter { !it.target.domestic }
    val local = n.targets.filter { it.target.domestic }
    if (global.isNotEmpty() && global.all { it.ms == null } && local.any { it.ms != null })
        out.add(2 to "سایت‌های داخلی باز می‌شوند ولی هیچ سایت خارجی جواب نمی‌دهد؛ با VPN کانکی وصل شو")
    else if (n.targets.isNotEmpty() && n.targets.all { it.ms == null })
        out.add(2 to "هیچ مقصدی جواب نداد؛ اینترنت گوشی وصل نیست")
    val udpAll = n.udp.filter { it.loss != null }
    if (udpAll.isNotEmpty() && udpAll.all { it.ping == null })
        out.add(2 to "UDP در این شبکه بسته است: WireGuard، AmneziaWG و KANKI MIX وصل نمی‌شوند؛ «ضد فیلتر» (Xray) را بزن")
    else udpAll.mapNotNull { it.loss }.maxOrNull()?.takeIf { it >= 2 }?.let {
        out.add(1 to trf("پکت‌لاس UDP %s٪ است؛ بازی ممکن است لگ بزند. حالت گیمینگ کانکی (KANKI MIX) کمکت می‌کند", pct(it)))
    }
    val dnsBad = n.sites.count { it.status == "dns" }
    if (dnsBad > 0) out.add(1 to trf("DNS شبکه‌ات برای %d سایت آدرس درست نداد", dnsBad))
    val certBad = n.sites.count { it.status == "cert" }
    if (certBad > 0) out.add(2 to trf("برای %d سایت گواهی امنیتی نادرست آمد: کسی وسط راه جواب می‌دهد. بدون VPN رمز وارد نکن", certBad))
    n.speed?.let { s ->
        s.scores()["gaming"]?.let { g -> out.add((if (g >= 3) 0 else if (g == 2) 1 else 2) to trf("اینترنتت برای بازی: %s", tr(SCORE_NAMES[g]))) }
        s.bloat?.takeIf { it >= 60 }?.let { b -> out.add(1 to trf("وقتی دانلود هست پینگت %s میلی‌ثانیه بالا می‌رود (رتبه %s)", ms(b), Aim.bloatGrade(b))) }
    }
    if (out.isEmpty() && n.done) out.add(0 to "اینترنت گوشی سالم است")
    return out
}

@Composable
private fun NetSection(n: NetUi, act: SpeedActions) {
    KCard(Modifier.fillMaxWidth()) {
        CardTitle("shield", "بررسی کامل اینترنت خودت")
        Spacer(Modifier.height(D.S2))
        T(
            "اینترنت خود گوشی (وای‌فای یا دیتا) را بیرون از VPN می‌سنجد، حتی وقتی وصلی: شبکه، IP، DNS، پینگ داخلی و خارجی، UDP، سایت‌ها و سرعت.",
            size = 11.sp, color = K.Sub, weight = FontWeight.Normal,
        )
        Spacer(Modifier.height(D.S3))
        Segmented(
            listOf(SegItem("1", "با تست سرعت"), SegItem("0", "بدون تست سرعت (کم‌مصرف)")),
            if (n.withSpeed) "1" else "0", enabled = !n.running, onSelect = { act.netSpeed(it == "1") },
        )
        if (n.running || n.done) {
            Spacer(Modifier.height(D.S3))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NET_STEPS.forEachIndexed { i, name ->
                    if (i == NET_STEPS.lastIndex && !n.withSpeed) return@forEachIndexed
                    val st = when { n.done || i < n.step -> 2; i == n.step && n.running -> 1; else -> 0 }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (st) { 2 -> CheckMark(true, 14.dp); 1 -> Spinner(12.dp, K.Gold); else -> Dot(K.Sub.copy(alpha = .5f), 6.dp) }
                        Spacer(Modifier.width(D.S2))
                        T(name, size = 12.sp, color = if (st == 0) K.Sub else K.Text, weight = if (st == 1) FontWeight.Black else FontWeight.Bold)
                    }
                }
            }
        }
        n.error?.let { Spacer(Modifier.height(D.S2)); T(it, size = 12.sp, color = K.Red) }
        Spacer(Modifier.height(D.S3))
        GoldButton(if (n.running) "در حال بررسی…" else if (n.done) "بررسی دوباره" else "بررسی اینترنت من", Modifier.fillMaxWidth(), loading = n.running, onClick = act.netStart)
    }
    val verdict = netVerdict(n)
    if (verdict.isNotEmpty()) {
        Spacer(Modifier.height(D.CardGap))
        KCard(Modifier.fillMaxWidth()) {
            CardTitle("spark", "جمع‌بندی")
            Spacer(Modifier.height(D.S2))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                verdict.forEach { (lv, txt) ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 6.dp)) { Dot(when (lv) { 0 -> K.Good; 1 -> K.Warn; else -> K.Red }, 7.dp) }
                        Spacer(Modifier.width(D.S2))
                        T(txt, size = 12.sp, weight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    if (n.info != null || n.meta != null) {
        Spacer(Modifier.height(D.CardGap))
        KCard(Modifier.fillMaxWidth()) {
            CardTitle("data", "اتصال تو")
            Spacer(Modifier.height(D.S2))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                n.info?.let { i ->
                    FactRow("نوع شبکه", when (i.transport) { "wifi" -> "وای‌فای"; "cell" -> "دیتای موبایل"; "eth" -> "کابل (اترنت)"; else -> "دیگر" } + (i.operator?.let { " · $it" } ?: ""), K.Text)
                    FactRow("اینترنت تأییدشده", if (i.validated) "بله" else "نه", if (i.validated) K.Good else K.Red)
                    FactRow("حجمی (پولی)", if (i.metered) "بله" else "نه", K.Text)
                    i.signal?.let { FactRow("قدرت سیگنال", ltr("$it dBm"), if (it > -67) K.Good else if (it > -80) K.Warn else K.Red) }
                    if (i.downKbps != null && i.upKbps != null) FactRow("برآورد اندروید از لینک", ltr("↓ ${i.downKbps / 1000} / ↑ ${i.upKbps / 1000} Mbps"), K.Text)
                    if (i.dnsServers.isNotEmpty()) FactRow("DNS گوشی", ltr(i.dnsServers.take(2).joinToString(", ")), K.Text)
                    FactRow("DNS خصوصی (DoT)", if (i.privateDns) "روشن" else "خاموش", K.Text)
                    i.mtu?.let { FactRow("MTU", ltr("$it"), K.Text) }
                    FactRow("IPv6", if (i.ipv6) "دارد" else "ندارد", K.Text)
                }
                n.meta?.let { m ->
                    FactRow("IP بیرونی", ltr(m.ip), K.Text)
                    if (m.isp.isNotBlank()) FactRow("شرکت اینترنت", m.isp + (m.asn?.let { " · AS$it" } ?: ""), K.Text)
                    val place = listOfNotNull(m.city.takeIf { it.isNotBlank() }, countryName(m.country) ?: m.country.takeIf { it.isNotBlank() }).joinToString("، ")
                    if (place.isNotBlank()) FactRow("محل", place, K.Text)
                }
                if (n.bypass) FactRow("مسیر", "بیرون از VPN کانکی", K.Green)
            }
        }
    }
    n.timing?.let { t ->
        Spacer(Modifier.height(D.CardGap))
        KCard(Modifier.fillMaxWidth()) {
            CardTitle("clock", "زمان‌بندی یک اتصال HTTPS")
            Spacer(Modifier.height(D.S3))
            val parts = listOf(Triple("DNS", t.dns, K.Gold), Triple("TCP", t.tcp, K.Accent), Triple("TLS", t.tls, K.Gold2), Triple("TTFB", t.ttfb, K.Green))
            val tot = parts.sumOf { it.second }.coerceAtLeast(1)
            CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50))) {
                    parts.forEach { (_, v, c) -> if (v > 0) Box(Modifier.weight(v.toFloat().coerceAtLeast(.01f)).fillMaxHeight().background(c)) }
                }
                Spacer(Modifier.height(D.S2))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    parts.forEach { (name, v, c) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            T(name, size = 10.sp, font = Poppins, color = c, ltr = true)
                            T("$v ms", size = 12.sp, font = Poppins, weight = FontWeight.Bold, ltr = true)
                        }
                    }
                }
            }
            Spacer(Modifier.height(D.S2))
            T(trf("کل: %d میلی‌ثانیه تا اولین بایت (Cloudflare%s)", tot, t.tlsVersion?.let { " · $it" } ?: ""), size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
        }
    }
    if (n.targets.isNotEmpty()) {
        Spacer(Modifier.height(D.CardGap))
        KCard(Modifier.fillMaxWidth()) {
            CardTitle("ping", "پینگ مقصدها (TCP)")
            Spacer(Modifier.height(D.S2))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                n.targets.forEach { r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Dot(r.ms?.let { pingColor(it) } ?: K.Red, 6.dp)
                        Spacer(Modifier.width(D.S2))
                        AutoText(r.target.name, size = 12.sp)
                        Spacer(Modifier.width(D.S2))
                        Tag(if (r.target.domestic) "داخلی" else "خارجی", if (r.target.domestic) K.Accent else K.Gold)
                        Spacer(Modifier.weight(1f))
                        T(r.ms?.let { "$it ms" + (r.jitter?.let { j -> " ±$j" } ?: "") } ?: tr("جواب نداد"), size = 12.sp, font = if (r.ms != null) Poppins else Vazir,
                            color = r.ms?.let { pingColor(it) } ?: K.Red, ltr = r.ms != null)
                    }
                }
            }
        }
    }
    if (n.udp.isNotEmpty()) {
        Spacer(Modifier.height(D.CardGap))
        KCard(Modifier.fillMaxWidth()) {
            CardTitle("bolt", "UDP (بازی و WireGuard)")
            Spacer(Modifier.height(D.S2))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                n.udp.forEach { u ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (u.ping == null) K.Red else lossColor(u.loss), 6.dp)
                        Spacer(Modifier.width(D.S2))
                        AutoText(u.name, size = 12.sp)
                        Spacer(Modifier.weight(1f))
                        if (u.ping == null) T("بسته / بی‌جواب", size = 12.sp, color = K.Red)
                        else T(ltr("${u.ping} ms · ±${u.jitter ?: 0} · ${pct(u.loss ?: 0.0)}%"), size = 12.sp, font = Poppins, color = lossColor(u.loss), ltr = true)
                    }
                }
            }
        }
    }
    if (n.sites.isNotEmpty()) {
        Spacer(Modifier.height(D.CardGap))
        KCard(Modifier.fillMaxWidth()) {
            CardTitle("unlock", "باز شدن سایت‌ها (بدون VPN)")
            Spacer(Modifier.height(D.S2))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                n.sites.forEach { s ->
                    val (txt, c) = when (s.status) {
                        "ok" -> (s.ms?.let { "باز است · $it ms" } ?: "باز است") to K.Good
                        "dns" -> "DNS آدرس درست نداد" to K.Warn
                        "tcp" -> "وصل نشد" to K.Red
                        "cert" -> "گواهی نادرست" to K.Red
                        else -> "دست‌دادن TLS بسته شد" to K.Red
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Dot(c, 6.dp)
                        Spacer(Modifier.width(D.S2))
                        AutoText(s.site.name, size = 12.sp)
                        Spacer(Modifier.weight(1f))
                        T(txt, size = 12.sp, color = c)
                    }
                }
            }
        }
    }
    n.speed?.let { s ->
        if (s.hasResult || s.running) {
            Spacer(Modifier.height(D.CardGap))
            SpeedResults(s)
            if (n.done) {
                Spacer(Modifier.height(D.CardGap))
                LineButton("اشتراک‌گذاری نتیجه", Modifier.fillMaxWidth()) { act.share(shareText(s, "Kanki · my internet (no VPN)")) }
            }
        }
    }
}

@Composable
private fun Tag(text: String, c: Color) {
    val sh = RoundedCornerShape(50)
    Box(Modifier.clip(sh).background(c.copy(alpha = .12f)).border(1.dp, c.copy(alpha = .4f), sh).padding(horizontal = 6.dp, vertical = 1.dp)) {
        T(text, size = 9.sp, color = c)
    }
}
