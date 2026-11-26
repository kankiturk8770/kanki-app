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

/**
 * ارزیابی کاربردی (۱.۴۱) با مقیاس‌های رایج (ITU-T G.114 و QoE بازی): ۰ ضعیف، ۱ متوسط، ۲ خوب، ۳ عالی؛ null تا عدد لازم نیامده.
 * پینگ بازی: زیر ۶۰ عالی، ۶۰ تا ۱۰۰ خوب، ۱۰۰ تا ۱۵۰ متوسط، بالای ۱۵۰ ضعیف. جیتر زیر ۱۵ پایدار. پکت‌لاس ۰٪ ایده‌آل، بالای ۱٪ بحرانی.
 * هر کاربرد = بدترین شاخص خودش.
 */
object Qoe {
    val NAMES = listOf("ضعیف", "متوسط", "خوب", "عالی")
    fun color(l: Int): Color = when (l) { 0 -> K.Red; 1 -> K.Warn; else -> K.Good }

    private fun pingGame(p: Double) = when { p < 60 -> 3; p < 100 -> 2; p < 150 -> 1; else -> 0 }
    private fun jit(j: Double, stable: Double) = when { j < stable -> 3; j < stable * 2 -> 1; else -> 0 }
    private fun loss(l: Double) = when { l <= 0.0 -> 3; l <= 1.0 -> 1; else -> 0 }

    fun gaming(ping: Double?, jitter: Double?, lossPct: Double?): Int? {
        ping ?: return null
        return listOfNotNull(pingGame(ping), jitter?.let { jit(it, 15.0) }, lossPct?.let { loss(it) }).min()
    }

    /** تماس تصویری: پینگ یک‌طرفه تا ۱۵۰ms خوب (G.114)، جیتر زیر ۳۰، پکت‌لاس تا ۱٪، دست‌کم ۱.۵ مگ دانلود و آپلود */
    fun call(ping: Double?, jitter: Double?, lossPct: Double?, down: Double?, up: Double?): Int? {
        ping ?: return null
        val p = when { ping < 100 -> 3; ping < 150 -> 2; ping < 300 -> 1; else -> 0 }
        val bw = listOfNotNull(down, up).minOrNull()?.let { when { it >= 4 -> 3; it >= 1.5 -> 2; it >= .8 -> 1; else -> 0 } }
        return listOfNotNull(p, jitter?.let { jit(it, 30.0) }, lossPct?.let { if (it <= 1.0) 3 else if (it <= 3) 1 else 0 }, bw).min()
    }

    /** استریم: سرعت دانلود (۲۵ مگ 4K، ۱۰ مگ HD، ۵ مگ SD) */
    fun stream(down: Double?, lossPct: Double?): Int? {
        down ?: return null
        val d = when { down >= 25 -> 3; down >= 10 -> 2; down >= 5 -> 1; else -> 0 }
        return listOfNotNull(d, lossPct?.let { if (it <= 2.0) 3 else 1 }).min()
    }
}

@Composable
fun SpeedContent(
    ui: SpeedUi,
    net: NetUi = NetUi(),
    section: String = "speed",
    history: List<SpeedRecord> = emptyList(),
    @Suppress("UNUSED_PARAMETER") tip: RobotAlert? = null,
    act: SpeedActions = SpeedActions(),
) {
    // یک صفحه، بی آمپر و (روی گوشی معمولی) بی اسکرول؛ اسکرول فقط برای صفحه‌های خیلی کوتاه
    // ۱.۴۱: همه‌چیز وسط صفحه (پایین خالی نماند)؛ صفحه‌ی کوتاه: اسکرول
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = D.PagePad)) {
        Spacer(Modifier.height(D.S2))
        Segmented(
            listOf(SegItem("speed", "تست سرعت", "bolt"), SegItem("net", "اینترنت من", "ping")),
            section, enabled = !ui.running && !net.running, onSelect = act.section,
        )
        Spacer(Modifier.height(D.S2))
        // برچسب مسیر تست: اینترنت آزاد گوشی، یا سروری که الان به آن وصلی
        RouteChip(
            if (section == "net" || !ui.viaVpn) tr("مسیر تست: اینترنت آزاد گوشی")
            else trf("مسیر تست: VPN · %s", ui.vpnPlace ?: tr("تونل کانکی")),
            if (section == "net" || !ui.viaVpn) K.Gold2 else K.Gold,
        )
        Spacer(Modifier.height(D.S3))
        if (section == "net") NetSection(net, act) else SpeedSection(ui, history, act)
        Spacer(Modifier.height(D.S4))
    }
    }
}

@Composable
private fun RouteChip(text: String, c: Color) {
    val sh = RoundedCornerShape(50)
    Row(
        Modifier.fillMaxWidth().height(30.dp).clip(sh).background(c.copy(alpha = .10f)).border(1.dp, c.copy(alpha = .35f), sh).padding(horizontal = D.S3),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Dot(c, 6.dp)
        Spacer(Modifier.width(D.S2))
        AutoText(text, size = 12.sp, color = K.Text)
    }
}

// ------------------------------------------------------------------ بخش تست سرعت

@Composable
private fun SpeedSection(ui: SpeedUi, history: List<SpeedRecord>, act: SpeedActions) {
    val dl = if (ui.running && ui.phase == "down") ui.live else ui.down
    val upv = if (ui.running && ui.phase == "up") ui.live else ui.up
    // سه کارت هم‌اندازه داخل یک قاب شیشه‌ای
    Column(Modifier.fillMaxWidth().goldGlass(RoundedCornerShape(D.RL)).padding(D.S2).guideTarget("speed")) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(D.S2)) {
            Kpi("دانلود", "down", dl?.let { mbps(it) }, "Mbps", K.Gold, ui.downPts, ui.running && ui.phase == "down", Modifier.weight(1f)) {
                T(ui.downPeak?.let { trf("اوج %s", ltr(mbps(it))) } ?: " ", size = 9.5.sp, color = K.Sub, weight = FontWeight.Normal, maxLines = 1)
            }
            Kpi("آپلود", "up", upv?.let { mbps(it) }, "Mbps", K.Accent, ui.upPts, ui.running && ui.phase == "up", Modifier.weight(1f)) {
                T(ui.upPeak?.let { trf("اوج %s", ltr(mbps(it))) } ?: " ", size = 9.5.sp, color = K.Sub, weight = FontWeight.Normal, maxLines = 1)
            }
            Kpi("پینگ", "ping", ui.ping?.let { ms(it) }, "ms", msColor(ui.ping), ui.pingPts, ui.running && (ui.phase == "ping" || ui.phase == "server"), Modifier.weight(1f)) {
                CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                    T("±" + (ui.jitter?.let { ms(it) } ?: "–") + " · " + (ui.loss?.let { pct(it) + "%" } ?: "–"), size = 9.5.sp, font = Poppins,
                        color = if (ui.loss != null && ui.loss > 1.0) K.Red else K.Sub, ltr = true, maxLines = 1)
                }
            }
        }
    }
    Spacer(Modifier.height(D.S3))
    // ارزیابی کاربردی
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(D.S2)) {
        val loaded = listOfNotNull(ui.loadedDown, ui.loadedUp).maxOrNull()
        val gamePing = ui.ping?.let { p -> loaded?.let { maxOf(p, (p + it) / 2) } ?: p }
        Verdict("بازی آنلاین", if (ui.running) null else Qoe.gaming(gamePing, ui.jitter, ui.loss), Modifier.weight(1f))
        Verdict("تماس تصویری", if (ui.running) null else Qoe.call(ui.ping, ui.jitter, ui.loss, ui.down, ui.up), Modifier.weight(1f))
        Verdict("استریم", if (ui.running) null else Qoe.stream(ui.down, ui.loss), Modifier.weight(1f))
    }
    Spacer(Modifier.height(D.S3))
    // ۱.۴۵: دو ردیف با اسم: سرور تست، بعد مدت تست
    SpeedOptionRow("سرور تست") {
        Segmented(listOf(SegItem("auto", "نزدیک‌ترین"), SegItem("cf", "Cloudflare")), ui.pick, enabled = !ui.running, onSelect = act.pick)
    }
    Spacer(Modifier.height(D.S2))
    SpeedOptionRow("حالت تست") {
        Segmented(listOf(SegItem("quick", "سریع"), SegItem("full", "کامل")), ui.mode, enabled = !ui.running, onSelect = act.mode)
    }
    ui.error?.let { Spacer(Modifier.height(D.S2)); T(it, size = 12.sp, color = K.Red) }
    Spacer(Modifier.height(D.S3))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GoldButton(
            when {
                ui.running -> when (ui.phase) { "server" -> "پیدا کردن سرور…"; "ping" -> "سنجش پینگ…"; "down" -> "سنجش دانلود…"; "up" -> "سنجش آپلود…"; else -> "در حال تست…" }
                ui.phase == "done" -> "تست دوباره"
                else -> "شروع تست"
            },
            Modifier.weight(1f), loading = ui.running, onClick = act.start,
        )
    }
    Spacer(Modifier.height(D.S2))
    // یک خط کوتاه: سرور تست و مصرف
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        T(
            listOfNotNull(ui.server?.let { trf("سرور: %s", it) }, ui.usedBytes?.let { trf("مصرف: %s", ltr(formatBytes(it))) }).joinToString(" · ").ifBlank { " " },
            size = 10.5.sp, color = K.Sub, weight = FontWeight.Normal, maxLines = 1,
        )
    }
    Spacer(Modifier.height(D.S3))
    RecentTests(history.take(3), if (ui.phase == "done") ({ act.share(shareText(ui, null)) }) else null)
}

/** «تاریخچه تست‌های اخیر» (۱.۴۱): ۳ تست آخر با زمان، مسیر، دانلود/آپلود و پینگ؛ اشتراک‌گذاری نتیجه در سربرگش */
@Composable
private fun RecentTests(h: List<SpeedRecord>, share: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().goldGlass(RoundedCornerShape(D.RL)).padding(horizontal = D.S3, vertical = D.S2)) {
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniIcon("clock", K.Gold, 13.dp)
            Spacer(Modifier.width(6.dp))
            T("تاریخچه تست‌های اخیر", size = 12.5.sp, weight = FontWeight.Black, modifier = Modifier.weight(1f), maxLines = 1)
            share?.let { f ->
                T("اشتراک‌گذاری", size = 11.5.sp, color = K.Gold, weight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(D.RT)).pressable(onClick = f).padding(horizontal = D.S2, vertical = 4.dp))
            }
        }
        if (h.isEmpty()) {
            T("هنوز تستی نگرفته‌ای", size = 11.5.sp, color = K.Sub, weight = FontWeight.Normal, modifier = Modifier.padding(vertical = D.S2))
        }
        h.forEachIndexed { i, r ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .06f)))
            Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(if (r.kind == "vpn") K.Gold else K.Gold2, 6.dp)
                Spacer(Modifier.width(D.S2))
                Column(Modifier.weight(1f)) {
                    T(if (r.kind == "vpn") (r.place?.let { trf("VPN · %s", it) } ?: "با VPN") else "اینترنت آزاد گوشی", size = 11.5.sp, maxLines = 1)
                    T(ltr(whenText(r.time)), size = 9.5.sp, color = K.Sub, weight = FontWeight.Normal, maxLines = 1, ltr = true)
                }
                CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T("↓" + (r.down?.let { mbps(it) } ?: "–"), size = 11.sp, font = Poppins, color = K.Gold, ltr = true)
                        Spacer(Modifier.width(6.dp))
                        T("↑" + (r.up?.let { mbps(it) } ?: "–"), size = 11.sp, font = Poppins, color = K.Accent, ltr = true)
                        Spacer(Modifier.width(6.dp))
                        T((r.ping?.let { ms(it) } ?: "–") + "ms", size = 11.sp, font = Poppins, color = msColor(r.ping), ltr = true)
                    }
                }
            }
        }
    }
}

/** یک کارت KPI جمع‌وجور: آیکون و اسم، عدد بزرگ و واحد، نمودار موجی ظریف و یک خط ریز */
@Composable
private fun Kpi(label: String, icon: String, value: String?, unit: String, c: Color, pts: List<Float>, live: Boolean, modifier: Modifier, foot: @Composable () -> Unit) {
    val shape = RoundedCornerShape(D.RM)
    Column(
        modifier.fillMaxHeight().clip(shape).background(Color.Black.copy(alpha = .22f))
            .border(1.dp, if (live) c.copy(alpha = .7f) else Color.White.copy(alpha = .07f), shape).padding(D.S2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniIcon(icon, c, 12.dp)
            Spacer(Modifier.width(4.dp))
            T(label, size = 11.sp, color = K.Sub, maxLines = 1, modifier = Modifier.weight(1f))
            if (live) Spinner(9.dp, c)
        }
        Spacer(Modifier.height(4.dp))
        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.Bottom) {
                T(value ?: "–", size = 21.sp, font = Poppins, weight = FontWeight.Bold, color = if (value == null) K.Sub else K.Text, ltr = true, maxLines = 1)
                Spacer(Modifier.width(2.dp))
                T(unit, size = 9.sp, font = Poppins, color = K.Sub, ltr = true, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Chart(pts, c, null, Modifier.fillMaxWidth().height(30.dp))
        Spacer(Modifier.height(4.dp))
        foot()
    }
}

/** نشانگر کاربردی کوچک: اسم کاربرد و برچسب رنگی عالی / خوب / متوسط / ضعیف */
@Composable
private fun Verdict(name: String, level: Int?, modifier: Modifier) {
    val c = level?.let { Qoe.color(it) } ?: K.Sub
    val shape = RoundedCornerShape(D.RM)
    Column(
        modifier.clip(shape).background(c.copy(alpha = .08f)).border(1.dp, c.copy(alpha = .30f), shape).padding(vertical = D.S2, horizontal = D.S1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        T(name, size = 11.sp, color = K.Text, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(c, 6.dp)
            Spacer(Modifier.width(4.dp))
            T(level?.let { tr(Qoe.NAMES[it]) } ?: "–", size = 12.sp, color = c, weight = FontWeight.Black, maxLines = 1)
        }
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
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Canvas(Modifier.width(size * 5 + 8.dp).height(size)) {
        val s = size.toPx()
        val gap = (this.size.width - s * 5) / 4
        for (i in 0 until 5) {
            // ستاره‌ها از راست (راست‌به‌چپ) پر می‌شوند تا با جهت فارسی بخواند
            val cx = if (rtl) this.size.width - (s / 2 + i * (s + gap)) else s / 2 + i * (s + gap)
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
    appendLine(title ?: if (ui.viaVpn) "KANKI VPN speed test" + (ui.vpnPlace?.let { " · $it" } ?: "") else "Kanki speed test (no VPN)")
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

/** جمع‌بندی «اینترنت من»: هر خط (رنگ، متن)؛ کوتاه و بی پیشنهاد پروتکل */
fun netVerdict(n: NetUi): List<Pair<Int, String>> {
    // رنگ: ۰ خوب، ۱ توجه، ۲ بد
    val out = ArrayList<Pair<Int, String>>()
    n.info?.let { if (!it.validated) out.add(2 to "اندروید این شبکه را «بی اینترنت» می‌شناسد") }
    val global = n.targets.filter { !it.target.domestic }
    val local = n.targets.filter { it.target.domestic }
    if (global.isNotEmpty() && global.all { it.ms == null } && local.any { it.ms != null })
        out.add(2 to "فقط سایت‌های داخلی جواب می‌دهند")
    else if (n.targets.isNotEmpty() && n.targets.all { it.ms == null })
        out.add(2 to "هیچ مقصدی جواب نداد؛ اینترنت گوشی وصل نیست")
    val udpAll = n.udp.filter { it.loss != null }
    if (udpAll.isNotEmpty() && udpAll.all { it.ping == null })
        out.add(2 to "UDP در این شبکه بسته است")
    else udpAll.mapNotNull { it.loss }.maxOrNull()?.takeIf { it >= 2 }?.let { out.add(1 to trf("پکت‌لاس UDP %s٪", pct(it))) }
    val dnsBad = n.sites.count { it.status == "dns" }
    if (dnsBad > 0) out.add(1 to trf("DNS برای %d سایت آدرس درست نداد", dnsBad))
    val certBad = n.sites.count { it.status == "cert" }
    if (certBad > 0) out.add(2 to trf("گواهی نادرست برای %d سایت: کسی وسط راه است", certBad))
    if (out.isEmpty() && n.done) out.add(0 to "اینترنت گوشی سالم است")
    return out
}

/** «اینترنت من» (۱.۴۱): همه‌ی شاخص‌ها در کارت‌های کوچک دوستونه با نقطه‌ی رنگی وضعیت، در یک صفحه */
@Composable
private fun NetSection(n: NetUi, act: SpeedActions) {
    val pend = n.running
    fun avg(l: List<NetCheck.TargetResult>) = l.mapNotNull { it.ms }.takeIf { it.isNotEmpty() }?.average()?.toLong()
    val local = n.targets.filter { it.target.domestic }
    val global = n.targets.filter { !it.target.domestic }
    val udp = n.udp.firstOrNull { it.ping != null } ?: n.udp.firstOrNull()
    val sitesOk = n.sites.count { it.status == "ok" }
    data class Cell(val title: String, val value: String, val sub: String, val c: Color, val ready: Boolean)
    val cells = listOf(
        n.info.let { i ->
            Cell("اتصال", i?.let { tr(when (it.transport) { "wifi" -> "وای‌فای"; "cell" -> "دیتای موبایل"; "eth" -> "کابل"; else -> "دیگر" }) } ?: "–",
                i?.operator ?: i?.let { if (it.validated) tr("اینترنت تأیید شد") else tr("بی اینترنت") } ?: "",
                i?.let { if (it.validated) K.Good else K.Red } ?: K.Sub, i != null)
        },
        n.meta.let { m -> Cell("IP بیرونی", m?.ip ?: "–", m?.isp?.takeIf { it.isNotBlank() } ?: "", if (m != null) K.Good else K.Sub, m != null) },
        n.timing.let { t ->
            val tot = t?.let { it.dns + it.tcp + it.tls + it.ttfb }
            Cell("TLS / HTTPS", tot?.let { "$it ms" } ?: "–", t?.let { "DNS ${it.dns} · TLS ${it.tls} ms" } ?: "",
                tot?.let { if (it < 400) K.Good else if (it < 900) K.Warn else K.Red } ?: K.Sub, t != null)
        },
        Cell("پینگ داخلی", avg(local)?.let { "$it ms" } ?: if (local.isNotEmpty()) tr("بی‌جواب") else "–", "TCP",
            avg(local)?.let { pingColor(it) } ?: if (local.isNotEmpty()) K.Red else K.Sub, local.isNotEmpty()),
        Cell("پینگ خارجی", avg(global)?.let { "$it ms" } ?: if (global.isNotEmpty()) tr("بی‌جواب") else "–", "TCP",
            avg(global)?.let { pingColor(it) } ?: if (global.isNotEmpty()) K.Red else K.Sub, global.isNotEmpty()),
        Cell("UDP", udp?.ping?.let { "$it ms" } ?: if (udp != null) tr("بسته") else "–",
            udp?.let { u -> u.ping?.let { "±${u.jitter ?: 0} · ${pct(u.loss ?: 0.0)}%" } ?: "" } ?: "",
            udp?.let { u -> if (u.ping == null) K.Red else lossColor(u.loss) } ?: K.Sub, udp != null),
        Cell("دسترسی به سایت‌ها", if (n.sites.isEmpty()) "–" else "$sitesOk / ${n.sites.size}", tr("باز می‌شوند"),
            if (n.sites.isEmpty()) K.Sub else if (sitesOk == n.sites.size) K.Good else if (sitesOk * 2 >= n.sites.size) K.Warn else K.Red, n.sites.isNotEmpty()),
        n.speed.let { s ->
            Cell("سرعت", s?.down?.let { "↓ ${mbps(it)}" } ?: if (n.withSpeed) "–" else tr("خاموش"), s?.up?.let { "↑ ${mbps(it)} Mbps" } ?: "",
                s?.down?.let { if (it >= 10) K.Good else if (it >= 3) K.Warn else K.Red } ?: K.Sub, s?.down != null)
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(D.S2)) {
        cells.chunked(2).forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(D.S2)) {
                row.forEachIndexed { i, x ->
                    val waiting = pend && !x.ready && n.step <= r * 2 + i + 1
                    NetTile(x.title, x.value, x.sub, x.c, waiting, Modifier.weight(1f))
                }
            }
        }
    }
    n.error?.let { Spacer(Modifier.height(D.S2)); T(it, size = 12.sp, color = K.Red) }
    // یک خط جمع‌بندی (مهم‌ترین مورد)
    netVerdict(n).firstOrNull()?.let { (lv, txt) ->
        Spacer(Modifier.height(D.S2))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Dot(when (lv) { 0 -> K.Good; 1 -> K.Warn; else -> K.Red }, 7.dp)
            Spacer(Modifier.width(D.S2))
            T(txt, size = 12.sp, weight = FontWeight.Bold, maxLines = 1)
        }
    }
    Spacer(Modifier.height(D.S3))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(D.S2)) {
        GoldButton(
            if (n.running) (NET_STEPS.getOrNull(n.step)?.let { trf("در حال بررسی: %s…", tr(it)) } ?: "در حال بررسی…") else if (n.done) "بررسی دوباره" else "بررسی اینترنت من",
            Modifier.weight(1f), loading = n.running, onClick = act.netStart,
        )
        Box(Modifier.width(118.dp)) {
            Segmented(listOf(SegItem("1", "با سرعت"), SegItem("0", "بی سرعت")), if (n.withSpeed) "1" else "0", enabled = !n.running, onSelect = { act.netSpeed(it == "1") })
        }
    }
    netAdvice(n)?.let { (title, why) ->
        Spacer(Modifier.height(D.S3))
        Row(
            Modifier.fillMaxWidth().goldGlass(RoundedCornerShape(D.RL), tint = K.Gold).padding(horizontal = D.S3, vertical = D.S2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp).goldFill(androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) { MiniIcon("bolt", K.OnGold, 15.dp) }
            Spacer(Modifier.width(D.S3))
            Column(Modifier.weight(1f)) {
                T("وضعیت پیشنهادی اتصال", size = 10.5.sp, color = K.Sub, weight = FontWeight.Normal, maxLines = 1)
                T(title, size = 13.5.sp, weight = FontWeight.Black, maxLines = 1)
                T(why, size = 10.5.sp, color = K.Sub, weight = FontWeight.Normal, maxLines = 2)
            }
        }
    }
    if (n.done) n.speed?.takeIf { it.hasResult }?.let { s ->
        Spacer(Modifier.height(D.S2))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            T("اشتراک‌گذاری نتیجه", size = 12.sp, color = K.Gold, weight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(D.RT)).pressable { act.share(shareText(s, "Kanki · my internet (no VPN)")) }.padding(D.S1))
        }
    }
}

/** حالت پیشنهادی اتصال از روی نتیجه‌ی «اینترنت من» (عنوان، دلیل کوتاه)؛ null تا بررسی تمام نشده */
fun netAdvice(n: NetUi): Pair<String, String>? {
    if (!n.done) return null
    val udpAll = n.udp.filter { it.loss != null }
    val udpBlocked = udpAll.isNotEmpty() && udpAll.all { it.ping == null }
    val global = n.targets.filter { !it.target.domestic }
    val globalDown = global.isNotEmpty() && global.all { it.ms == null }
    val loss = udpAll.mapNotNull { it.loss }.maxOrNull() ?: 0.0
    return when {
        n.targets.isNotEmpty() && n.targets.all { it.ms == null } -> "اول اینترنت گوشی را وصل کن" to "هیچ مقصدی جواب نداد"
        udpBlocked || globalDown -> "ضد فیلتر" to "UDP یا سایت‌های خارجی بسته‌اند؛ Xray از همه بهتر رد می‌شود"
        loss >= 2 -> "گیمینگ از راه تانل" to "پکت‌لاس UDP بالاست؛ تانل ضد افت کمک می‌کند"
        else -> "هوشمند یا گیمینگ مستقیم" to "شبکه سالم است؛ مستقیم کمترین پینگ را دارد"
    }
}

/** یک کارت کوچک «اینترنت من»: نقطه‌ی رنگی و اسم، مقدار و یک خط ریز */
@Composable
private fun NetTile(title: String, value: String, sub: String, c: Color, waiting: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(D.RM)
    Column(modifier.height(76.dp).goldGlass(shape).padding(horizontal = D.S3, vertical = D.S2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (waiting) Spinner(9.dp, K.Gold) else Dot(c, 7.dp)
            Spacer(Modifier.width(6.dp))
            T(title, size = 11.sp, color = K.Sub, maxLines = 1)
        }
        Spacer(Modifier.height(3.dp))
        AutoText(value, size = 15.sp, color = if (value == "–") K.Sub else K.Text, weight = FontWeight.Black)
        if (sub.isNotBlank()) T(sub, size = 10.sp, color = K.Sub, weight = FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun Tag(text: String, c: Color) {
    val sh = RoundedCornerShape(50)
    Box(Modifier.clip(sh).background(c.copy(alpha = .12f)).border(1.dp, c.copy(alpha = .4f), sh).padding(horizontal = 6.dp, vertical = 1.dp)) {
        T(text, size = 9.sp, color = c)
    }
}

/** یک ردیف تنظیم تست سرعت: اسم با پهنای ثابت، گزینه‌ها بقیه‌ی ردیف */
@Composable
private fun SpeedOptionRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        T(label, size = 12.sp, color = K.Sub, weight = FontWeight.Bold, maxLines = 1, modifier = Modifier.width(72.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}
