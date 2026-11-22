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

// صفحه‌ی ورود (فقط تصویر؛ زمان‌بندی در Intro.kt است)

/** طول صفحه‌ی ورود به ثانیه (بی‌صدا) */
const val SPLASH_SEC = 4f

private val SoftOut = CubicBezierEasing(.16f, 1f, .3f, 1f)        // آرام نشستن
private val BackOut = CubicBezierEasing(.34f, 1.36f, .64f, 1f)    // کمی جلوتر رفتن و برگشتن

/**
 * صفحه‌ی ورود آرام و ساده (v از ۰ تا ۱):
 *  ۰ تا ۱.۳ ثانیه  نور ملایم وسط صفحه روشن می‌شود، ذرات طلایی آهسته جمع می‌شوند و
 *                  یک حلقه‌ی نازک طلایی دور خودش کامل می‌شود (یعنی «اتصال امن در حال آماده شدن»)
 *  ۱.۳ ثانیه       حلقه کامل شد ← لوگو با چرخش نرم ظاهر می‌شود و یک موج نور آرام پخش می‌شود
 *  ۱.۷ تا ۲.۷      حرف‌های KANKI VPN یکی‌یکی بالا می‌آیند، خط زیرش باز می‌شود، شعار ظاهر می‌شود
 *  ۳.۴ تا ۴.۰      همه‌چیز آرام محو می‌شود و برنامه باز می‌شود
 */
@Composable
fun SplashVisual(v: Float) {
    val t = v * SPLASH_SEC
    fun seg(a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)

    val glow = SoftOut.transform(seg(0f, 1.2f))
    val trace = FastOutSlowInEasing.transform(seg(.15f, 1.3f))
    val gather = SoftOut.transform(seg(0f, 1.35f))
    val logo = seg(1.2f, 1.95f)
    val logoA = SoftOut.transform(logo)
    val logoS = BackOut.transform(logo)
    val wave = seg(1.3f, 2.6f)
    val line = SoftOut.transform(seg(2.25f, 2.9f))
    val tag = SoftOut.transform(seg(2.45f, 3.05f))
    val exit = FastOutSlowInEasing.transform(seg(3.4f, 4f))
    val title = "KANKI VPN"

    KankiBackground {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = 1f - exit
                scaleX = 1f + .05f * exit; scaleY = 1f + .05f * exit
            },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                    // حلقه، نور و ذرات دور لوگو (بزرگ‌تر از کادر لوگو کشیده می‌شود)
                    Canvas(Modifier.requiredSize(560.dp)) {
                        val c = center
                        val u = 1.dp.toPx()
                        // نور نرم وسط که بعد از ظاهر شدن لوگو کمی آرام می‌شود
                        val ga = .30f * glow + .14f * (1f - wave) * logoA
                        drawCircle(Brush.radialGradient(listOf(K.Gold.copy(alpha = ga), Color.Transparent), c, 210 * u), 210 * u, c)

                        // ذرات طلایی که آهسته به سمت حلقه می‌آیند و محو می‌شوند
                        for (i in 0 until 34) {
                            val ang = i * 137.508 * Math.PI / 180
                            val start = 170f + (i % 5) * 22f
                            val r = (start - (start - 100f) * gather) * u
                            val a = (1f - gather) * .55f * (if (i % 3 == 0) 1f else .6f)
                            if (a > .01f) {
                                val p = Offset(c.x + r * cos(ang).toFloat(), c.y + r * sin(ang).toFloat())
                                drawCircle(K.Gold.copy(alpha = a * .35f), 5f * u, p)
                                drawCircle(K.GoldLight.copy(alpha = a), 1.6f * u, p)
                            }
                        }

                        // حلقه‌ی نازک: مسیر کم‌رنگ + قوسی که کامل می‌شود
                        val rr = (98f + 8f * logoA) * u
                        val tl = Offset(c.x - rr, c.y - rr)
                        val sz = Size(rr * 2, rr * 2)
                        val ringA = 1f - .55f * logoA
                        drawCircle(K.Gold.copy(alpha = .10f * glow), rr, c, style = Stroke(1.2f * u))
                        if (trace > 0f) {
                            drawArc(K.Gold.copy(alpha = .18f * ringA), -90f, 360f * trace, false, tl, sz, style = Stroke(6f * u, cap = StrokeCap.Round))
                            drawArc(K.GoldLight.copy(alpha = .9f * ringA), -90f, 360f * trace, false, tl, sz, style = Stroke(1.8f * u, cap = StrokeCap.Round))
                            if (trace < 1f) {
                                val hr = Math.toRadians((-90.0 + 360.0 * trace))
                                val hp = Offset(c.x + rr * cos(hr).toFloat(), c.y + rr * sin(hr).toFloat())
                                drawCircle(K.Gold.copy(alpha = .25f), 9f * u, hp)
                                drawCircle(Color.White.copy(alpha = .95f), 2.6f * u, hp)
                            }
                        }

                        // موج نور آرام بعد از ظاهر شدن لوگو
                        if (wave > 0f && wave < 1f) {
                            val wr = (100f + 105f * SoftOut.transform(wave)) * u
                            val wa = (1f - wave) * .28f
                            drawCircle(K.Gold.copy(alpha = wa * .35f), wr, c, style = Stroke(10f * u))
                            drawCircle(K.GoldLight.copy(alpha = wa), wr, c, style = Stroke(1.4f * u))
                        }
                    }
                    // لوگو: محو، کمی بزرگ شدن و چرخیدن نرم تا رو به رو
                    Box(
                        Modifier.graphicsLayer {
                            alpha = logoA
                            val s = .82f + .18f * logoS
                            scaleX = s; scaleY = s
                            rotationY = -30f * (1f - logoA)
                            cameraDistance = 14f * density
                        },
                    ) { Logo3D(140.dp, animate = false) }
                }

                Spacer(Modifier.height(26.dp))

                // اسم: حرف‌ها یکی‌یکی از پایین بالا می‌آیند (همیشه چپ‌به‌راست)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(Modifier.height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                        title.forEachIndexed { i, ch ->
                            val k = SoftOut.transform(seg(1.7f + i * .07f, 2.15f + i * .07f))
                            Box(Modifier.graphicsLayer { alpha = k; translationY = (1f - k) * 14.dp.toPx() }) {
                                T(ch.toString(), size = 33.sp, color = K.Gold, weight = FontWeight.Black, font = Orbitron, shadow = glow(K.Gold, 22f * k), ltr = true)
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
