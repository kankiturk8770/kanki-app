package cafe.kankipro.vpn

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.drawText

// نقشه‌ی جهان (فقط رسم؛ خواندن فایل نقشه در MapView.kt است)

/** یک نقطه‌ی جغرافیایی */
data class Geo(val lat: Double, val lon: Double)

/** یک کشور: کد ISO و مسیر مرزهایش (در فضای طول/عرض جغرافیایی: x = lon , y = -lat) */
class Country(val iso: String, val path: Path, val minLon: Float, val maxLon: Float, val minLat: Float, val maxLat: Float)

/** پرچم ایموجی از روی کد ISO (DE → 🇩🇪) */
fun flagEmoji(iso: String?): String? {
    if (iso == null || iso.length != 2) return null
    val a = Character.toChars(0x1F1E6 + (iso[0].uppercaseChar() - 'A'))
    val b = Character.toChars(0x1F1E6 + (iso[1].uppercaseChar() - 'A'))
    return String(a) + String(b)
}

/** کد ISO دوحرفی از روی پرچم ایموجی (🇩🇪 → DE) */
fun isoOfFlag(flag: String): String? {
    val cps = flag.codePoints().toArray()
    if (cps.size < 2) return null
    val a = cps[0] - 0x1F1E6; val b = cps[1] - 0x1F1E6
    if (a !in 0..25 || b !in 0..25) return null
    return "${'A' + a}${'A' + b}"
}

/** مختصات تقریبی پایتخت/شهر اصلی هر کشور (وقتی کشور خروجی معلوم است ولی مختصات دقیقش نه) */
fun geoOfIso(iso: String?): Geo? = when (iso?.uppercase()) {
    "DE" -> Geo(50.1, 8.7); "TR" -> Geo(41.0, 29.0); "IR" -> Geo(35.7, 51.4)
    "NL" -> Geo(52.4, 4.9); "FI" -> Geo(60.2, 24.9); "FR" -> Geo(48.9, 2.35)
    "GB" -> Geo(51.5, -0.1); "US" -> Geo(39.0, -77.0); "AE" -> Geo(25.2, 55.3)
    "RU" -> Geo(55.8, 37.6); "SE" -> Geo(59.3, 18.1); "CA" -> Geo(43.7, -79.4)
    "PL" -> Geo(52.2, 21.0); "AT" -> Geo(48.2, 16.4); "AM" -> Geo(40.2, 44.5)
    "SG" -> Geo(1.35, 103.8); "JP" -> Geo(35.7, 139.7); "IT" -> Geo(45.5, 9.2)
    "ES" -> Geo(40.4, -3.7); "CH" -> Geo(47.4, 8.5); "BE" -> Geo(50.8, 4.35)
    "CZ" -> Geo(50.1, 14.4); "RO" -> Geo(44.4, 26.1); "BG" -> Geo(42.7, 23.3)
    "UA" -> Geo(50.45, 30.5); "KZ" -> Geo(43.2, 76.9); "GE" -> Geo(41.7, 44.8)
    "AZ" -> Geo(40.4, 49.9); "IN" -> Geo(19.1, 72.9); "KR" -> Geo(37.6, 127.0)
    "HK" -> Geo(22.3, 114.2); "AU" -> Geo(-33.9, 151.2); "BR" -> Geo(-23.5, -46.6)
    "IE" -> Geo(53.35, -6.26); "NO" -> Geo(59.9, 10.75); "DK" -> Geo(55.7, 12.6)
    "LT" -> Geo(54.7, 25.3); "LV" -> Geo(56.95, 24.1); "EE" -> Geo(59.4, 24.75)
    "PT" -> Geo(38.7, -9.1); "GR" -> Geo(37.98, 23.7); "HU" -> Geo(47.5, 19.04)
    "RS" -> Geo(44.8, 20.5); "SA" -> Geo(24.7, 46.7); "QA" -> Geo(25.3, 51.5)
    "KW" -> Geo(29.4, 48.0); "IQ" -> Geo(33.3, 44.4); "TH" -> Geo(13.75, 100.5)
    "VN" -> Geo(21.0, 105.8); "ID" -> Geo(-6.2, 106.8); "MY" -> Geo(3.1, 101.7)
    "MD" -> Geo(47.0, 28.85); "LU" -> Geo(49.6, 6.13); "CY" -> Geo(35.17, 33.36)
    else -> null
}

data class MapPin(val geo: Geo, val iso: String?, val selected: Boolean)

private data class View(val lon0: Double, val lon1: Double, val lat0: Double, val lat1: Double)

private fun lerp(a: Double, b: Double, t: Float) = a + (b - a) * t

/**
 * محدوده‌ای که نقاط را با حاشیه و نسبت درست نشان می‌دهد.
 * نقاط کمی پایین‌تر از وسط می‌نشینند تا سنجاق‌های پرچم (که بالای نقطه‌اند) هم کامل دیده شوند
 * و کل مسیر بین شما و سرور، از اول تا آخر، داخل نقشه بماند.
 */
private fun fitView(pts: List<Geo>, aspect: Double, minSpan: Double): View {
    val lon0 = pts.minOf { it.lon }; val lon1 = pts.maxOf { it.lon }
    val lat0 = pts.maxOf { it.lat }; val lat1 = pts.minOf { it.lat }
    var w = (lon1 - lon0).coerceAtLeast(minSpan) * 1.55
    var h = (lat0 - lat1).coerceAtLeast(minSpan / aspect) * 1.9
    if (w / h < aspect) w = h * aspect else h = w / aspect
    val cx = (lon0 + lon1) / 2
    val cy = (lat0 + lat1) / 2 + h * .10
    return View(cx - w / 2, cx + w / 2, cy + h / 2, cy - h / 2)
}

/**
 * نقشه‌ی طلایی بدون قاب که لبه‌هایش در زمینه‌ی مشکی محو می‌شود (جزو خود صفحه است، نه یک کارت جدا):
 * خشکی تیره با مرز طلایی، کشور شما فیروزه‌ای، سنجاق‌های درخشان با پرچم برای همه‌ی سرورها،
 * و وقتی وصل است نقشه روی مسیر زوم می‌کند و کمان نئونی سبز از شما تا سرور کامل دیده می‌شود.
 */
@Composable
fun WorldMap(
    countries: List<Country>?, pins: List<MapPin>, user: Geo?, userIso: String?, connected: Boolean, modifier: Modifier = Modifier,
    /** رنگ سرور انتخابی و خط پیش‌نمایش پیش از اتصال */
    accent: Color = K.Gold,
    /**
     * حالت DNS: آی‌پی عوض نمی‌شود و ترافیک به هیچ سروری نمی‌رود، پس نه سرور انتخابی هست نه مسیر؛
     * وقتی وصل است، سپر DNS دور خود شما (موج‌ها و درخواست‌هایی که دورتان می‌چرخند)
     */
    dns: Boolean = false,
) {
    val tm = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 24)

    val sel = if (dns) null else pins.firstOrNull { it.selected }
    val zoom by animateFloatAsState(if (connected) 1f else 0f, tween(1300, easing = FastOutSlowInEasing), label = "z")
    val draw by animateFloatAsState(if (connected) 1f else 0f, tween(if (connected) 1400 else 150, delayMillis = if (connected) 700 else 0), label = "d")
    val t = rememberInfiniteTransition(label = "map")
    val flow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "f")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "p")

    // لایه‌ی ثابت (پس‌زمینه‌ی تیره، کشورها و نور شهرها) یک بار در یک عکس کشیده می‌شود و فقط وقتی نما یا رنگ‌ها
    // عوض شوند دوباره ساخته می‌شود؛ هر فریم فقط سنجاق‌ها و کمان متحرک کشیده می‌شوند (وگرنه ده‌ها هزار نقطه
    // و مرز کشورها ۶۰ بار در ثانیه کشیده می‌شد و صفحه کند می‌شد)
    val cache = remember { MapCache() }
    androidx.compose.foundation.layout.Spacer(modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawBehind {
        val w = size.width
        val h = size.height
        val aspect = (w / h).toDouble()
        // نمای عادی: همه‌ی سرورها و شما ؛ نمای اتصال: کمی نزدیک‌تر روی مسیر
        val all = (pins.map { it.geo } + listOfNotNull(user)).ifEmpty { listOf(Geo(45.0, 20.0)) }
        val far = fitView(all, aspect, 40.0)
        val near = fitView(listOfNotNull(user, sel?.geo).ifEmpty { all }, aspect, if (dns) 26.0 else 40.0)
        val v = View(lerp(far.lon0, near.lon0, zoom), lerp(far.lon1, near.lon1, zoom), lerp(far.lat0, near.lat0, zoom), lerp(far.lat1, near.lat1, zoom))
        val sx = (w / (v.lon1 - v.lon0)).toFloat()
        val sy = (h / (v.lat0 - v.lat1)).toFloat()
        fun proj(g: Geo) = Offset(((g.lon - v.lon0) * sx).toFloat(), ((v.lat0 - g.lat) * sy).toFloat())

        val selIso = sel?.iso
        val selColor = if (connected) K.Green else accent
        val border = 1f / sx
        // کشور شما: فیروزه‌ای؛ با DNS وصل، رنگ DNS
        val youColor = if (dns && connected) accent else K.Accent
        val key = listOf(size, zoom, pins, user, userIso, connected, accent, dns, countries?.size, ThemeState.current.id)
        val old = cache.bmp
        val bmp = if (old != null && old.width == w.toInt().coerceAtLeast(1) && old.height == h.toInt().coerceAtLeast(1)) old
            else ImageBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
        if (cache.key != key || cache.bmp !== bmp) {
        cache.key = key
        cache.bmp = bmp
        androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(bmp), size) {
            drawRect(Color.Black, blendMode = BlendMode.Clear)
            // نور طلایی خیلی ملایم پشت نقشه؛ زمینه همان مشکی صفحه است تا نقشه جزو خود صفحه باشد
            drawRect(Brush.radialGradient(listOf(K.Gold.copy(alpha = .07f), Color.Transparent), Offset(w * .5f, h * .5f), w * .7f))
            clipRect {
                withTransform({
                    translate((-v.lon0 * sx).toFloat(), (v.lat0 * sy).toFloat())
                    scale(sx, sy, pivot = Offset.Zero)
                }) {
                    countries?.forEach { c ->
                        if (c.maxLon < v.lon0 || c.minLon > v.lon1 || c.maxLat < v.lat1 || c.minLat > v.lat0) return@forEach
                        // خشکی شیشه‌ای تیره با مرز طلایی؛ کشور شما فیروزه‌ای، کشور سرور طلایی (وصل: سبز) و درخشان
                        drawPath(c.path, Color(0xFF1E1A12).copy(alpha = .85f))
                        when (c.iso) {
                            selIso -> {
                                drawPath(c.path, selColor.copy(alpha = .32f))
                                drawPath(c.path, selColor.copy(alpha = .30f), style = Stroke(border * 4.5f))
                                drawPath(c.path, selColor, style = Stroke(border * 1.8f))
                            }
                            userIso -> {
                                drawPath(c.path, youColor.copy(alpha = .20f))
                                drawPath(c.path, youColor.copy(alpha = .6f), style = Stroke(border * 1.2f))
                            }
                            else -> {
                                drawPath(c.path, K.Gold.copy(alpha = .10f), style = Stroke(border * 3f))
                                drawPath(c.path, K.Gold.copy(alpha = .38f), style = Stroke(border))
                            }
                        }
                    }
                }
            }
        }
        }

        drawImage(bmp)
        // پیش از اتصال: خط‌چین کم‌رنگ از شما تا سرور انتخابی
        if (user != null && sel != null && draw < 1f) {
            val a = proj(user)
            val b = proj(sel.geo)
            val lift = (kotlin.math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) * .30).toFloat()
            val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2 - lift)
            val path = Path().apply { moveTo(a.x, a.y); quadraticBezierTo(mid.x, mid.y, b.x, b.y) }
            val sw = w / 320f
            drawPath(
                path, accent.copy(alpha = .6f * (1f - draw)),
                style = Stroke(sw, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(sw * 4f, sw * 4f), flow * sw * 8f)),
            )
        }
        // کمان نئونی سبز از شما تا سرور
        if (user != null && sel != null && draw > 0f) {
            val a = proj(user)
            val b = proj(sel.geo)
            val lift = (kotlin.math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) * .30).toFloat()
            val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2 - lift)
            fun bez(q: Float) = Offset(
                (1 - q) * (1 - q) * a.x + 2 * (1 - q) * q * mid.x + q * q * b.x,
                (1 - q) * (1 - q) * a.y + 2 * (1 - q) * q * mid.y + q * q * b.y,
            )
            val path = Path().apply {
                moveTo(a.x, a.y)
                val steps = 48
                for (i in 1..(steps * draw).toInt().coerceAtLeast(1)) { val o = bez(i / steps.toFloat()); lineTo(o.x, o.y) }
            }
            val sw = w / 240f
            for ((m, al) in listOf(5f to .07f, 3f to .16f, 1.8f to .36f, 1f to 1f)) {
                drawPath(path, K.Green.copy(alpha = al), style = Stroke(sw * m, cap = StrokeCap.Round))
            }
            drawPath(path, Color.White.copy(alpha = .7f), style = Stroke(sw * .35f, cap = StrokeCap.Round))
            if (draw >= 1f) neonDot(K.Green, bez(flow), sw * 1.5f)
        }

        val pr = w / 34f
        // بقیه‌ی سرورها: نقطه‌ی درخشان طلایی با سنجاق کوچک پرچم
        // (سروری که در کشور خود کاربر است جدا کشیده نمی‌شود تا دو سنجاق روی هم نیفتند)
        // (با DNS وصل کم‌رنگ‌اند: ترافیک به هیچ‌کدام نمی‌رود)
        val pinAlpha = if (dns) 1f - .5f * draw else 1f
        pins.filter { (dns || !it.selected) && (user == null || it.iso != userIso) }.forEach { p ->
            val o = proj(p.geo)
            neonDot(K.Gold.copy(alpha = .85f * pinAlpha), o, w / 170f)
            flagPin(tm, o, p.iso, K.Gold.copy(alpha = .8f * pinAlpha), pr * .78f)
        }
        // سرور انتخابی: هاله، موج تپنده و سنجاق بزرگ‌تر
        sel?.let {
            val o = proj(it.geo)
            val r = w / 14f
            drawCircle(Brush.radialGradient(listOf(selColor.copy(alpha = .32f), selColor.copy(alpha = .10f), Color.Transparent), o, r * 1.3f), r * 1.3f, o)
            if (connected) drawCircle(selColor.copy(alpha = (1 - pulse) * .7f), r * (.35f + pulse * .9f), o, style = Stroke(w / 300f))
            neonDot(selColor, o, w / 110f)
            flagPin(tm, o, it.iso, selColor, pr)
        }
        // DNS وصل: سپر دور شما (سه موج پشت هم، مدار خط‌چین و سه درخواست که دورتان می‌چرخند)
        if (dns && user != null && draw > 0f) {
            val o = proj(user)
            val r = w / 8.5f * eased(draw)
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .22f * draw), accent.copy(alpha = .06f * draw), Color.Transparent), o, r * 1.25f), r * 1.25f, o)
            for (k in 0 until 3) {
                val q = (pulse + k / 3f) % 1f
                drawCircle(accent.copy(alpha = (1 - q) * .6f * draw), r * (.22f + q), o, style = Stroke(w / 330f))
            }
            val orbit = r * .62f
            val sw = w / 420f
            drawCircle(
                accent.copy(alpha = .45f * draw), orbit, o,
                style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(sw * 5f, sw * 5f), -flow * sw * 10f)),
            )
            for (k in 0 until 3) {
                val ang = (flow + k / 3f) * 2f * Math.PI.toFloat()
                neonDot(accent.copy(alpha = draw), Offset(o.x + orbit * kotlin.math.cos(ang), o.y + orbit * kotlin.math.sin(ang)), w / 260f)
            }
            val tag = tm.measure(
                tr("DNS فعال · آی‌پی همان آی‌پی خودتان"),
                androidx.compose.ui.text.TextStyle(fontFamily = Vazir, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = (w / 38f).toSp(), color = accent.copy(alpha = draw)),
            )
            val ty = (o.y + r * .95f).coerceAtMost(h * .80f - tag.size.height)
            drawText(tag, topLeft = Offset((o.x - tag.size.width / 2f).coerceIn(w * .08f, (w * .92f - tag.size.width).coerceAtLeast(w * .08f)), ty))
        }
        // شما: نقطه‌ی تپنده، سنجاق با پرچم و برچسب
        user?.let {
            val o = proj(it)
            drawCircle(youColor.copy(alpha = (1 - pulse) * .55f), w / 120f * (1.5f + pulse * 3f), o, style = Stroke(w / 400f))
            neonDot(youColor, o, w / 125f)
            // اگر سرور انتخابی در همین کشور است، سنجاق پرچم همان سرور کافی است
            if (sel == null || sel.iso != userIso) flagPin(tm, o, userIso, youColor, pr * .85f)
            val layout = tm.measure(tr("شما"), androidx.compose.ui.text.TextStyle(fontFamily = Vazir, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = (w / 34f).toSp(), color = K.Text))
            drawText(layout, topLeft = Offset(o.x + pr * 1.1f, o.y - pr * 2.2f - layout.size.height / 2f))
        }

        // محو شدن لبه‌ها در پس‌زمینه (بدون قاب)
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, .08f to Color.Black, .80f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, .07f to Color.Black, .93f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    })
}

/** عکس لایه‌ی ثابت نقشه و چیزهایی که با آن کشیده شده (فقط وقتی این‌ها عوض شوند دوباره کشیده می‌شود) */
private class MapCache {
    var key: Any? = null
    var bmp: ImageBitmap? = null
}

/** سنجاق کوچک با پرچم بالای یک نقطه */
private fun DrawScope.flagPin(tm: androidx.compose.ui.text.TextMeasurer, o: Offset, iso: String?, c: Color, pr: Float) {
    val head = Offset(o.x, o.y - pr * 2.2f)
    val stem = Path().apply {
        moveTo(o.x, o.y - pr * .3f)
        lineTo(head.x - pr * .62f, head.y + pr * .6f)
        lineTo(head.x + pr * .62f, head.y + pr * .6f)
        close()
    }
    drawPath(stem, c)
    drawCircle(c.copy(alpha = .28f), pr * 1.45f, head)
    drawCircle(c, pr * 1.12f, head)
    drawCircle(K.Panel, pr * .96f, head)
    flagEmoji(iso)?.let { f ->
        val layout = tm.measure(f, androidx.compose.ui.text.TextStyle(fontSize = (pr * 1.1f).toSp()))
        drawText(layout, topLeft = Offset(head.x - layout.size.width / 2f, head.y - layout.size.height / 2f))
    }
}

private fun DrawScope.neonDot(c: Color, o: Offset, r: Float) {
    drawCircle(c.copy(alpha = .15f), r * 3.5f, o)
    drawCircle(c.copy(alpha = .35f), r * 2f, o)
    drawCircle(c, r, o)
    drawCircle(Color.White, r * .45f, o)
}

/** پایان نرم (برای باز شدن سپر DNS) */
private fun eased(x: Float) = 1f - (1f - x) * (1f - x)
