package cafe.kankipro.vpn

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// تب تست سرعت با طراحی جدید (فقط ظاهر)

/** مرحله‌های تست: "" (آماده)، server، ping، down، up، done */
data class SpeedUi(
    val running: Boolean = false,
    val phase: String = "",
    val live: Double = 0.0,
    val down: Double? = null,
    val up: Double? = null,
    val ping: Long? = null,
    val jitter: Long? = null,
    /** پکت‌لاس UDP (درصد) و مقصد سنجش UDP */
    val loss: Int? = null,
    val udpTarget: String? = null,
    val server: String? = null,
    val viaVpn: Boolean = false,
    /** کشور سروری که الان به آن وصلیم (برای زیرعنوان صفحه) */
    val vpnPlace: String? = null,
    val usedBytes: Long? = null,
    val error: String? = null,
)

@Composable
fun SpeedContent(ui: SpeedUi, tip: RobotAlert?, onStart: () -> Unit, onCloseTip: () -> Unit) {
    // ۱.۲۹: روی صفحه‌ی بلند فضای خالی پایین نمی‌ماند؛ کارت و عقربه به وسط صفحه می‌آیند و عقربه بزرگ‌تر می‌شود
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val gaugeH = (maxHeight * .36f).coerceIn(232.dp, 300.dp)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = D.PagePad),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Column {
        Spacer(Modifier.height(D.S2))
        Heading("تست سرعت", if (ui.viaVpn) (ui.vpnPlace?.let { trf("متصل به VPN · %s", it) } ?: "از داخل تونل کانکی") else "بدون VPN")
        tip?.let { Spacer(Modifier.height(D.S3)); RobotBanner(it, onClose = onCloseTip) }
        }
        KCard(Modifier.fillMaxWidth().padding(vertical = D.S3), padding = D.CardPad) {
            // مرحله‌ها: سه برچسب باریک در یک کپسول شیشه‌ای، وسط‌چین با فاصله‌ی ۱۲
            Row(
                Modifier.align(Alignment.CenterHorizontally).goldGlass(RoundedCornerShape(50)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(D.S3),
            ) {
                PhaseChip("ping", "پینگ UDP", ui, Modifier)
                PhaseChip("down", "دانلود", ui, Modifier)
                PhaseChip("up", "آپلود", ui, Modifier)
            }
            Spacer(Modifier.height(D.S2))
            val gaugeColor = if (ui.phase == "up") K.Accent else if (ui.phase == "done") K.Green else K.Gold
            val shown = when {
                ui.running && (ui.phase == "down" || ui.phase == "up") -> ui.live
                else -> ui.down ?: 0.0
            }
            Box(Modifier.fillMaxWidth().height(gaugeH).guideTarget("speed"), contentAlignment = Alignment.BottomCenter) {
                SpeedGauge(shown, gaugeColor, Modifier.fillMaxSize())
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 2.dp)) {
                    T(String.format(java.util.Locale.US, "%.1f", shown), size = 40.sp, font = Poppins, weight = FontWeight.Bold, shadow = glow(gaugeColor, 18f), ltr = true)
                    T(
                        when (ui.phase) {
                            "server" -> "در حال پیدا کردن نزدیک‌ترین سرور…"
                            "ping" -> "در حال سنجش پینگ UDP…"
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(D.S2)) {
                ResultTile("down", "دانلود", ui.down?.let { String.format(java.util.Locale.US, "%.1f", it) }, "Mbps", K.Gold, Modifier.weight(1f))
                ResultTile("up", "آپلود", ui.up?.let { String.format(java.util.Locale.US, "%.1f", it) }, "Mbps", K.Accent, Modifier.weight(1f))
            }
            Spacer(Modifier.height(D.S2))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(D.S2)) {
                ResultTile("ping", if (ui.udpTarget != null) "پینگ UDP" else "پینگ", ui.ping?.toString(), "ms", ui.ping?.let { pingColor(it) } ?: K.Sub, Modifier.weight(1f))
                ResultTile("clock", "جیتر", ui.jitter?.toString(), "ms", K.Sub, Modifier.weight(1f))
            }
            // اطلاعات فنی در یک کارت باریک جدا: هر ردیف اسم در یک طرف، مقدار در طرف دیگر (هیچ متنی به دیگری نمی‌چسبد)
            if (ui.udpTarget != null || ui.server != null) {
                Spacer(Modifier.height(D.S3))
                Column(
                    Modifier.fillMaxWidth().goldGlass(RoundedCornerShape(D.RM)).padding(horizontal = D.S3, vertical = D.S2),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ui.udpTarget?.let { tg ->
                        val lc = ui.loss?.let { l -> if (l == 0) K.Good else if (l < 5) K.Warn else K.Red } ?: K.Sub
                        FactRow("پکت‌لاس UDP", ltr("${ui.loss ?: 0}%"), lc)
                        FactRow("مقصد UDP", tg, K.Text)
                    }
                    ui.server?.let { FactRow("سرور تست", it, K.Text) }
                }
            }
            ui.error?.let { Spacer(Modifier.height(D.S2)); T(it, size = 12.sp, color = K.Red) }
            Spacer(Modifier.height(D.S4))
            GoldButton(
                when {
                    ui.running -> "در حال تست…"
                    ui.phase == "done" -> "تست دوباره"
                    else -> "شروع تست سرعت"
                },
                Modifier.fillMaxWidth(), loading = ui.running, onClick = onStart,
            )
            Spacer(Modifier.height(D.S2))
            T(
                ui.usedBytes?.let { trf("مصرف اینترنت این تست: %s", ltr(formatBytes(it))) }
                    ?: "پینگ، جیتر و پکت‌لاس روی UDP (مثل بازی)؛ دانلود و آپلود با سرورهای Speedtest by Ookla و چند اتصال همزمان",
                size = 11.sp, color = K.Sub, weight = FontWeight.Normal,
            )
        }
        Spacer(Modifier.height(D.S2))
    }
    }
}

@Composable
private fun PhaseChip(key: String, label: String, ui: SpeedUi, modifier: Modifier) {
    val order = listOf("server", "ping", "down", "up", "done")
    val cur = order.indexOf(ui.phase)
    val me = order.indexOf(key)
    val done = cur > me || ui.phase == "done"
    val active = ui.running && (cur == me || (key == "ping" && ui.phase == "server"))
    val c = when { active -> K.Gold; done -> K.Green; else -> K.Sub.copy(alpha = .6f) }
    val sh = RoundedCornerShape(50)
    Row(
        modifier.height(26.dp).clip(sh).background(c.copy(alpha = .12f)).border(1.dp, c.copy(alpha = .45f), sh).padding(horizontal = 10.dp),
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

@Composable
private fun ResultTile(icon: String, label: String, value: String?, unit: String, c: Color, modifier: Modifier) {
    val sh = RoundedCornerShape(D.RM)
    Row(
        modifier.height(D.RowH).goldGlass(sh).padding(horizontal = D.S3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(D.IconBoxS).clip(RoundedCornerShape(D.RT)).background(c.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            MiniIcon(icon, c, 14.dp)
        }
        Spacer(Modifier.width(D.S3))
        Column {
            T(label, size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
            // عدد و واحد همیشه چپ‌به‌راست (۸۷.۶ Mbps)
            CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                Row(verticalAlignment = Alignment.Bottom) {
                    T(value ?: "–", size = 17.sp, font = Poppins, weight = FontWeight.Bold, color = if (value == null) K.Sub else K.Text, ltr = true)
                    Spacer(Modifier.width(4.dp))
                    T(unit, size = 10.sp, font = Poppins, weight = FontWeight.SemiBold, color = K.Sub, ltr = true, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
        }
    }
}

/** یک ردیف اطلاعات فنی تست سرعت: اسم در یک طرف، مقدار (رنگی) در طرف دیگر */
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
