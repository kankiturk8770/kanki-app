package cafe.kankipro.vpn

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
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

/**
 * صداهای کوتاه برنامه با SoundPool.
 * صدا یک بار از قبل بارگذاری می‌شود و خود SoundPool در این شیء نگه داشته می‌شود؛ پس (برخلاف
 * MediaPlayer محلی که ممکن بود وسط پخش جمع‌آوری شود یا هنوز آماده نباشد) هر بار پخش می‌شود.
 * اگر هنوز بارگذاری تمام نشده باشد، همان لحظه که آماده شد پخش می‌شود (اگر خیلی دیر نشده باشد).
 * همه‌ی صدا زدن‌ها روی رشته‌ی اصلی است.
 */
object Sfx {
    private var pool: SoundPool? = null
    /** شناسه‌ی منبع ← شناسه‌ی صدا در SoundPool */
    private val ids = HashMap<Int, Int>()
    private val ready = HashSet<Int>()
    /** صداهایی که قبل از آماده شدن درخواست شده‌اند: (بلندی، زمان درخواست) */
    private val pending = HashMap<Int, Pair<Float, Long>>()

    private fun pool(): SoundPool = pool ?: SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
        .also { p ->
            p.setOnLoadCompleteListener { sp, id, status ->
                if (status == 0) {
                    ready.add(id)
                    pending.remove(id)?.let { (v, at) ->
                        if (SystemClock.uptimeMillis() - at < 1500) sp.play(id, v, v, 1, 0, 1f)
                    }
                }
            }
            pool = p
        }

    fun preload(ctx: Context, res: Int) {
        runCatching {
            if (!ids.containsKey(res)) {
                val id = pool().load(ctx.applicationContext, res, 1)
                if (id != 0) {
                    ids[res] = id
                }
            }
            Unit
        }
    }

    fun play(ctx: Context, res: Int, volume: Float = 1f) {
        runCatching {
            preload(ctx, res)
            val id = ids[res] ?: return
            if (id in ready) {
                pool?.play(id, volume, volume, 1, 0, 1f)
            } else {
                pending[id] = volume to SystemClock.uptimeMillis()
            }
            Unit
        }
    }

    fun soundOn(ctx: Context) = ctx.getSharedPreferences("kanki", Context.MODE_PRIVATE).getBoolean("sound", true)
}

/**
 * صفحه‌ی شروع (۴ ثانیه): لوگو با نور و بزرگ‌شدن نرم ظاهر می‌شود و یک نوار نور از رویش رد می‌شود
 * (تصویر در SplashVisual، فایل IntroUi.kt). صدای خیلی آرام res/raw/kanki_splash.wav با بلندی کم پخش می‌شود.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // صدای باز شدن قفل از همین حالا آماده می‌شود تا بعد از ورود حتماً پخش شود
        Sfx.preload(ctx, R.raw.kanki_switch)
        if (Sfx.soundOn(ctx)) Sfx.play(ctx, R.raw.kanki_splash, 0.35f)
        p.animateTo(1f, tween((SPLASH_SEC * 1000).toInt(), easing = LinearEasing))
        onDone()
    }
    SplashVisual(p.value)
}

/** انیمیشن باز شدن قفل با کلید بعد از تأیید اشتراک */
@Composable
fun UnlockScreen(onDone: () -> Unit) {
    val uctx = LocalContext.current
    LaunchedEffect(Unit) {
        if (Sfx.soundOn(uctx)) {
            Sfx.preload(uctx, R.raw.kanki_switch)
            delay(880) // لحظه‌ی چرخیدن کلید
            Sfx.play(uctx, R.raw.kanki_switch, 0.9f)
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

    KankiBackground(accent = if (open > 0.5f) K.Green else K.Gold, calm = true) {
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
                    Box(Modifier.alpha(welcome).scale(0.6f + 0.4f * welcome)) { Logo3D(140.dp, glow = welcome) }
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
