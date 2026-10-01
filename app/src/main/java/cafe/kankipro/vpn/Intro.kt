package cafe.kankipro.vpn

import android.media.MediaPlayer
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/** صفحه‌ی ورودی: ذرات طلایی جمع می‌شوند، لوگو ظاهر و اسم تایپ می‌شود (۵ ثانیه، با صدای سینمایی) */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val soundOn = ctx.getSharedPreferences("kanki", android.content.Context.MODE_PRIVATE).getBoolean("sound", true)
        val player = if (!soundOn) null else runCatching { MediaPlayer.create(ctx, R.raw.kanki_logo)?.apply { start() } }.getOrNull()
        p.animateTo(1f, tween(7000, easing = LinearEasing))
        onDone()
        delay(300)
        runCatching { player?.release() }
    }
    val v = p.value
    // هماهنگ با موزیک: اوج‌گرفتن ۰ تا ۱ ثانیه، ضربه‌ی اول ۱.۱ ثانیه، تایپ اسم، موج دوم ۴.۳ ثانیه، محوشدن آخر
    val gather = FastOutSlowInEasing.transform((v / 0.15f).coerceIn(0f, 1f))
    val logo = FastOutSlowInEasing.transform(((v - 0.15f) / 0.07f).coerceIn(0f, 1f))
    val typed = ((v - 0.23f) / 0.28f).coerceIn(0f, 1f)
    val swell = ((v - 0.61f) / 0.16f).coerceIn(0f, 1f)
    val fadeOut = 1f - ((v - 0.89f) / 0.11f).coerceIn(0f, 1f)
    val title = "KANKI VPN"

    KankiBackground {
        Box(Modifier.fillMaxSize().alpha(fadeOut), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2, size.height / 2 - 60.dp.toPx())
                for (i in 0 until 60) {
                    val ang = i * 137.5 * Math.PI / 180
                    val far = size.maxDimension * (0.35f + (i % 7) * 0.05f)
                    val r = far * (1f - gather)
                    val pos = Offset(c.x + r * cos(ang).toFloat(), c.y + r * sin(ang).toFloat())
                    val col = if (i % 5 == 0) K.Green else K.Gold
                    drawCircle(col.copy(alpha = .25f * (1f - logo)), 9f, pos)
                    drawCircle(Color.White.copy(alpha = (1f - logo)), 2.5f, pos)
                }
                if (logo > 0.01f && logo < 0.99f) neonRing(K.Gold, c, 60.dp.toPx() + logo * 160.dp.toPx(), 3.dp.toPx(), 1f - logo)
                // موج دوم نور هم‌زمان با اوج دوم موزیک
                if (swell > 0.01f && swell < 0.99f) {
                    neonRing(K.Ok, c, 80.dp.toPx() + swell * 220.dp.toPx(), 3.dp.toPx(), 1f - swell)
                    neonRing(K.Gold, c, 50.dp.toPx() + swell * 150.dp.toPx(), 2.dp.toPx(), (1f - swell) * .8f)
                }
                drawCircle(Brush.radialGradient(listOf(K.Gold.copy(alpha = .35f * logo), Color.Transparent), c, 170.dp.toPx()), 170.dp.toPx(), c)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.graphicsLayer { scaleX = .5f + .5f * logo; scaleY = .5f + .5f * logo; alpha = logo }) { Logo3D(150.dp) }
                Spacer(Modifier.height(20.dp))
                Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
                    BrandTitle(34.sp, title.take((typed * title.length).toInt().coerceIn(0, title.length)))
                }
                T("Secure · Fast · Low Ping", size = 13.sp, color = K.Sub, font = Poppins, weight = FontWeight.SemiBold, ltr = true, modifier = Modifier.alpha(((v - 0.62f) / 0.06f).coerceIn(0f, 1f)))
            }
        }
    }
}

/** انیمیشن باز شدن قفل با کلید بعد از تأیید اشتراک */
@Composable
fun UnlockScreen(onDone: () -> Unit) {
    val uctx = LocalContext.current
    LaunchedEffect(Unit) {
        val on = uctx.getSharedPreferences("kanki", android.content.Context.MODE_PRIVATE).getBoolean("sound", true)
        if (on) {
            val mp = runCatching { MediaPlayer.create(uctx, R.raw.kanki_switch) }.getOrNull()
            delay(880) // لحظه‌ی چرخیدن کلید
            runCatching { mp?.apply { setOnCompletionListener { it.release() }; start() } }
        }
    }
    val p = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        scope.launch { p.animateTo(1f, tween(2800, easing = LinearEasing)) }.join()
        delay(150)
        onDone()
    }
    val v = p.value
    val keyIn = FastOutSlowInEasing.transform((v / 0.30f).coerceIn(0f, 1f))          // ورود کلید
    val keyTurn = FastOutSlowInEasing.transform(((v - 0.32f) / 0.16f).coerceIn(0f, 1f)) // چرخش کلید
    val open = FastOutSlowInEasing.transform(((v - 0.48f) / 0.14f).coerceIn(0f, 1f))    // باز شدن قفل
    val burst = ((v - 0.60f) / 0.25f).coerceIn(0f, 1f)
    val welcome = ((v - 0.66f) / 0.18f).coerceIn(0f, 1f)
    val fadeOut = 1f - ((v - 0.92f) / 0.08f).coerceIn(0f, 1f)
    val lockAlpha = 1f - ((v - 0.66f) / 0.12f).coerceIn(0f, 1f)

    KankiBackground(accent = if (open > 0.5f) K.Green else K.Gold) {
        Column(
            Modifier.fillMaxSize().alpha(fadeOut),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = center
                    val u = size.minDimension / 280f
                    // پرتوهای نور بعد از باز شدن
                    if (burst > 0f) {
                        for (i in 0 until 16) {
                            val a = Math.toRadians(i * 22.5)
                            val r1 = 60f * u + burst * 40f * u
                            val r2 = r1 + 70f * u * (1f - burst * 0.5f)
                            drawLine(
                                K.Gold.copy(alpha = (1f - burst) * .9f),
                                Offset(c.x + r1 * cos(a).toFloat(), c.y + r1 * sin(a).toFloat()),
                                Offset(c.x + r2 * cos(a).toFloat(), c.y + r2 * sin(a).toFloat()),
                                4f * u, cap = StrokeCap.Round,
                            )
                        }
                        neonRing(K.Green, c, (70f + burst * 60f) * u, 3f * u, 1f - burst)
                    }
                    if (lockAlpha > 0f) {
                        val bw = 120f * u
                        val bh = 96f * u
                        val top = c.y - 10f * u
                        // حلقه‌ی قفل
                        val lift = open * 34f * u
                        val sr = 38f * u
                        drawArc(
                            Brush.verticalGradient(listOf(K.GoldLight, K.Gold, K.GoldDeep)),
                            180f, 180f, false,
                            topLeft = Offset(c.x - sr, top - sr - 6f * u - lift), size = Size(sr * 2, sr * 2),
                            style = Stroke(15f * u, cap = StrokeCap.Round), alpha = lockAlpha,
                        )
                        drawLine(K.GoldDeep.copy(alpha = lockAlpha), Offset(c.x - sr, top - 6f * u - lift), Offset(c.x - sr, top), 15f * u)
                        drawLine(K.GoldDeep.copy(alpha = lockAlpha * (1f - open)), Offset(c.x + sr, top - 6f * u - lift), Offset(c.x + sr, top), 15f * u)
                        // بدنه‌ی قفل
                        drawRoundRect(
                            Brush.verticalGradient(listOf(K.Shine, K.Gold, K.GoldDeep)),
                            topLeft = Offset(c.x - bw / 2, top), size = Size(bw, bh),
                            cornerRadius = CornerRadius(22f * u), alpha = lockAlpha,
                        )
                        drawRoundRect(
                            Color.White.copy(alpha = .25f * lockAlpha),
                            topLeft = Offset(c.x - bw / 2 + 8f * u, top + 6f * u), size = Size(bw - 16f * u, 14f * u),
                            cornerRadius = CornerRadius(8f * u),
                        )
                        // سوراخ کلید
                        val kc = Offset(c.x, top + bh * .45f)
                        drawCircle(K.OnGold.copy(alpha = lockAlpha), 11f * u, kc)
                        // کلید
                        val kx = c.x + (1f - keyIn) * 150f * u
                        translate(kx - c.x, 0f) {
                            rotate(-90f * keyTurn, kc) {
                                val ka = lockAlpha
                                drawCircle(Brush.radialGradient(listOf(K.GoldLight, K.Gold), Offset(kc.x + 58f * u, kc.y), 20f * u), 20f * u, Offset(kc.x + 58f * u, kc.y), alpha = ka)
                                drawCircle(K.OnGold.copy(alpha = ka), 7f * u, Offset(kc.x + 58f * u, kc.y))
                                drawLine(K.GoldLight.copy(alpha = ka), Offset(kc.x, kc.y), Offset(kc.x + 40f * u, kc.y), 8f * u, cap = StrokeCap.Round)
                                drawLine(K.GoldLight.copy(alpha = ka), Offset(kc.x + 8f * u, kc.y), Offset(kc.x + 8f * u, kc.y + 12f * u), 6f * u, cap = StrokeCap.Round)
                                drawLine(K.GoldLight.copy(alpha = ka), Offset(kc.x + 18f * u, kc.y), Offset(kc.x + 18f * u, kc.y + 9f * u), 6f * u, cap = StrokeCap.Round)
                            }
                        }
                    }
                }
                if (welcome > 0f) {
                    Box(Modifier.alpha(welcome).scale(0.6f + 0.4f * welcome)) { Logo3D(140.dp) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.alpha(welcome)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    T("✓ اشتراک شما فعال شد", size = 22.sp, color = K.Green, weight = FontWeight.Black, shadow = glow(K.Green, 24f))
                    Spacer(Modifier.height(6.dp))
                    T("به کانکی وی‌پی‌ان خوش آمدید", size = 15.sp, color = K.Sub)
                }
            }
        }
    }
}
