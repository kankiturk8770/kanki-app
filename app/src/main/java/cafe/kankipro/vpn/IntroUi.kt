package cafe.kankipro.vpn

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

// صفحه‌ی شروع (فقط تصویر؛ پخش صدا و زمان‌بندی در Intro.kt است)

/** طول صفحه‌ی شروع به ثانیه */
const val SPLASH_SEC = 4f

private val SoftOut = CubicBezierEasing(.16f, 1f, .3f, 1f)        // آرام نشستن
private val BackOut = CubicBezierEasing(.34f, 1.30f, .64f, 1f)    // کمی جلوتر رفتن و برگشتن

/**
 * صفحه‌ی شروع آرام (v از ۰ تا ۱، کل آن ۴ ثانیه):
 *  ۰ تا ۱.۳ ثانیه   هاله‌ی طلایی ملایم روشن می‌شود و سپر K با محو و بزرگ‌شدن نرم ظاهر می‌شود
 *  ۱.۳ تا ۲.۳       یک نوار نور از روی سپر رد می‌شود و یک موج نور خیلی آرام پخش می‌شود
 *  ۱.۷ تا ۳.۰       حرف‌های KANKI VPN یکی‌یکی بالا می‌آیند، خط زیرش باز می‌شود، شعار ظاهر می‌شود
 *  ۳.۴ تا ۴.۰       همه‌چیز آرام محو می‌شود و برنامه باز می‌شود
 */
@Composable
fun SplashVisual(v: Float) {
    val t = v * SPLASH_SEC
    fun seg(a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)

    val halo = SoftOut.transform(seg(0f, 1.4f))
    val logo = seg(.25f, 1.3f)
    val logoA = SoftOut.transform(seg(.25f, 1.0f))
    val logoS = BackOut.transform(logo)
    val sweepP = seg(1.35f, 2.3f)
    val wave = seg(1.25f, 2.6f)
    val line = SoftOut.transform(seg(2.3f, 2.9f))
    val tag = SoftOut.transform(seg(2.45f, 3.05f))
    val exit = FastOutSlowInEasing.transform(seg(3.4f, 4f))
    val title = "KANKI VPN"

    KankiBackground(calm = true) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = 1f - exit
                scaleX = 1f + .04f * exit; scaleY = 1f + .04f * exit
            },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                    // هاله و موج نور دور لوگو (بزرگ‌تر از کادر لوگو کشیده می‌شود)
                    Canvas(Modifier.requiredSize(460.dp)) {
                        val c = center
                        val u = 1.dp.toPx()
                        val ga = .26f * halo
                        drawCircle(Brush.radialGradient(listOf(K.Gold.copy(alpha = ga), Color.Transparent), c, 190 * u), 190 * u, c)
                        if (wave > 0f && wave < 1f) {
                            val wr = (88f + 110f * SoftOut.transform(wave)) * u
                            val wa = (1f - wave) * .26f
                            drawCircle(K.Gold.copy(alpha = wa * .35f), wr, c, style = Stroke(9f * u))
                            drawCircle(K.GoldLight.copy(alpha = wa), wr, c, style = Stroke(1.3f * u))
                        }
                    }
                    Box(
                        Modifier.graphicsLayer {
                            alpha = logoA
                            val s = .62f + .38f * logoS
                            scaleX = s; scaleY = s
                        },
                    ) { Logo3D(136.dp, sweep = if (sweepP > 0f && sweepP < 1f) sweepP * 1.6f - .3f else -1f, glow = halo) }
                }

                Spacer(Modifier.height(26.dp))

                // اسم: حرف‌ها یکی‌یکی از پایین بالا می‌آیند (همیشه چپ‌به‌راست)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(Modifier.height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                        title.forEachIndexed { i, ch ->
                            val k = SoftOut.transform(seg(1.7f + i * .07f, 2.15f + i * .07f))
                            Box(Modifier.graphicsLayer { alpha = k; translationY = (1f - k) * 14.dp.toPx() }) {
                                T(ch.toString(), size = 31.sp, color = K.Gold, weight = FontWeight.Black, font = Orbitron, shadow = glow(K.Gold, 18f * k), ltr = true)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // خط طلایی که از وسط باز می‌شود
                Box(
                    Modifier.width((170 * line).dp).height(1.5.dp)
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, K.Gold.copy(alpha = .9f), Color.Transparent))),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Secure · Fast · Low Ping",
                    modifier = Modifier.graphicsLayer { alpha = tag },
                    style = TextStyle(
                        fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = K.Sub,
                        letterSpacing = (1f + 3f * (1f - tag)).sp,
                    ),
                )
            }
        }
    }
}
