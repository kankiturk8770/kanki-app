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
    val server: String? = null,
    val viaVpn: Boolean = false,
    val usedBytes: Long? = null,
    val error: String? = null,
)

@Composable
fun SpeedContent(ui: SpeedUi, tip: RobotAlert?, onStart: () -> Unit, onCloseTip: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(4.dp))
        Heading("تست سرعت", if (ui.viaVpn) "از داخل تونل کانکی" else "بدون VPN")
        tip?.let { Spacer(Modifier.height(10.dp)); RobotBanner(it, onClose = onCloseTip) }
        Spacer(Modifier.height(12.dp))
        KCard(Modifier.fillMaxWidth(), padding = 14.dp) {
            // مرحله‌ها
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PhaseChip("ping", "پینگ", ui, Modifier.weight(1f))
                PhaseChip("down", "دانلود", ui, Modifier.weight(1f))
                PhaseChip("up", "آپلود", ui, Modifier.weight(1f))
            }
            val gaugeColor = if (ui.phase == "up") K.Accent else if (ui.phase == "done") K.Green else K.Gold
            val shown = when {
                ui.running && (ui.phase == "down" || ui.phase == "up") -> ui.live
                else -> ui.down ?: 0.0
            }
            Box(Modifier.fillMaxWidth().height(232.dp), contentAlignment = Alignment.BottomCenter) {
                SpeedGauge(shown, gaugeColor, Modifier.fillMaxSize())
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 2.dp)) {
                    T(String.format(java.util.Locale.US, "%.1f", shown), size = 40.sp, font = Poppins, weight = FontWeight.Bold, shadow = glow(gaugeColor, 18f), ltr = true)
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
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResultTile("down", "دانلود", ui.down?.let { String.format(java.util.Locale.US, "%.1f", it) }, "Mbps", K.Gold, Modifier.weight(1f))
                ResultTile("up", "آپلود", ui.up?.let { String.format(java.util.Locale.US, "%.1f", it) }, "Mbps", K.Accent, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResultTile("ping", "پینگ", ui.ping?.toString(), "ms", ui.ping?.let { pingColor(it) } ?: K.Sub, Modifier.weight(1f))
                ResultTile("clock", "جیتر", ui.jitter?.toString(), "ms", K.Sub, Modifier.weight(1f))
            }
            ui.server?.let {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MiniIcon("status", K.Green, 10.dp)
                    Spacer(Modifier.width(6.dp))
                    T("سرور تست:", size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
                    Spacer(Modifier.width(4.dp))
                    AutoText(it, size = 11.sp, color = K.Text)
                }
            }
            ui.error?.let { Spacer(Modifier.height(8.dp)); T(it, size = 12.sp, color = K.Red) }
            Spacer(Modifier.height(14.dp))
            GoldButton(
                when {
                    ui.running -> "در حال تست…"
                    ui.phase == "done" -> "تست دوباره"
                    else -> "شروع تست سرعت"
                },
                Modifier.fillMaxWidth(), loading = ui.running, onClick = onStart,
            )
            Spacer(Modifier.height(8.dp))
            T(
                ui.usedBytes?.let { trf("مصرف اینترنت این تست: %s", ltr(formatBytes(it))) }
                    ?: "با سرورهای Speedtest by Ookla و چند اتصال همزمان، مثل اپ اصلی Speedtest",
                size = 11.sp, color = K.Sub, weight = FontWeight.Normal,
            )
        }
        Spacer(Modifier.height(16.dp))
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
        modifier.clip(sh).background(c.copy(alpha = .10f)).border(1.dp, c.copy(alpha = .45f), sh).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            active -> Spinner(11.dp, c)
            done -> CheckMark(true, 13.dp)
            else -> Dot(c, 6.dp)
        }
        Spacer(Modifier.width(6.dp))
        T(label, size = 11.sp, color = c, weight = FontWeight.Bold)
    }
}

@Composable
private fun ResultTile(icon: String, label: String, value: String?, unit: String, c: Color, modifier: Modifier) {
    val sh = RoundedCornerShape(18.dp)
    Row(
        modifier.clip(sh).background(K.Panel.copy(alpha = .75f)).border(1.dp, K.Gold.copy(alpha = .12f), sh)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            MiniIcon(icon, c, 14.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            T(label, size = 11.sp, color = K.Sub, weight = FontWeight.Normal)
            // عدد و واحد همیشه چپ‌به‌راست (۸۷.۶ Mbps)
            CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                Row(verticalAlignment = Alignment.Bottom) {
                    T(value ?: "–", size = 18.sp, font = Poppins, weight = FontWeight.Bold, color = if (value == null) K.Sub else K.Text, ltr = true)
                    Spacer(Modifier.width(3.dp))
                    T(unit, size = 10.sp, font = Poppins, weight = FontWeight.SemiBold, color = K.Sub, ltr = true, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
        }
    }
}
