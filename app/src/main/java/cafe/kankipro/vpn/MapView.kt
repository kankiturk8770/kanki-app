package cafe.kankipro.vpn

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.drawText
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** یک نقطه‌ی جغرافیایی */
data class Geo(val lat: Double, val lon: Double)

/** یک کشور: کد ISO و مسیر مرزهایش (در فضای طول/عرض جغرافیایی: x = lon , y = -lat) */
class Country(val iso: String, val path: Path, val minLon: Float, val maxLon: Float, val minLat: Float, val maxLat: Float)

/** داده‌ی نقشه از فایل countries.geojson (Natural Earth) */
object MapData {
    @Volatile
    var countries: List<Country>? = null
        private set

    fun load(ctx: Context): List<Country> {
        countries?.let { return it }
        val out = ArrayList<Country>()
        runCatching {
            val txt = ctx.assets.open("countries.geojson").bufferedReader().use { it.readText() }
            val feats = JSONObject(txt).optJSONArray("features") ?: JSONArray()
            for (i in 0 until feats.length()) {
                val f = feats.optJSONObject(i) ?: continue
                val pr = f.optJSONObject("properties") ?: JSONObject()
                var iso = pr.optString("ISO_A2_EH", "")
                if (iso.length != 2) iso = pr.optString("ISO_A2", "")
                if (iso.length != 2) iso = pr.optString("iso_a2", "")
                val g = f.optJSONObject("geometry") ?: continue
                val coords = g.optJSONArray("coordinates") ?: continue
                val path = Path()
                var mnX = 999f; var mxX = -999f; var mnY = 999f; var mxY = -999f
                fun addRing(r: JSONArray) {
                    for (k in 0 until r.length()) {
                        val pt = r.optJSONArray(k) ?: continue
                        val x = pt.optDouble(0).toFloat(); val y = pt.optDouble(1).toFloat()
                        if (k == 0) path.moveTo(x, -y) else path.lineTo(x, -y)
                        mnX = minOf(mnX, x); mxX = maxOf(mxX, x); mnY = minOf(mnY, y); mxY = maxOf(mxY, y)
                    }
                    path.close()
                }
                when (g.optString("type")) {
                    "Polygon" -> for (r in 0 until coords.length()) coords.optJSONArray(r)?.let { addRing(it) }
                    "MultiPolygon" -> for (pIdx in 0 until coords.length()) {
                        val poly = coords.optJSONArray(pIdx) ?: continue
                        for (r in 0 until poly.length()) poly.optJSONArray(r)?.let { addRing(it) }
                    }
                }
                out.add(Country(iso.uppercase(), path, mnX, mxX, mnY, mxY))
            }
        }
        countries = out
        return out
    }
}

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

/** مختصات تقریبی کشورها (اگر مکان دقیق سرور پیدا نشد) */
fun geoOfName(name: String): Geo? {
    val f = flagOf(name)
    return when (f) {
        "🇩🇪" -> Geo(50.1, 8.7); "🇹🇷" -> Geo(41.0, 29.0); "🇮🇷" -> Geo(35.7, 51.4)
        "🇳🇱" -> Geo(52.4, 4.9); "🇫🇮" -> Geo(60.2, 24.9); "🇫🇷" -> Geo(48.9, 2.35)
        "🇬🇧" -> Geo(51.5, -0.1); "🇺🇸" -> Geo(39.0, -77.0); "🇦🇪" -> Geo(25.2, 55.3)
        "🇷🇺" -> Geo(55.8, 37.6); "🇸🇪" -> Geo(59.3, 18.1); "🇨🇦" -> Geo(43.7, -79.4)
        "🇵🇱" -> Geo(52.2, 21.0); "🇦🇹" -> Geo(48.2, 16.4); "🇦🇲" -> Geo(40.2, 44.5)
        "🇸🇬" -> Geo(1.35, 103.8); "🇯🇵" -> Geo(35.7, 139.7)
        else -> null
    }
}

/**
 * موقعیت یک IP یا دامنه (اگر خالی باشد: همین IP خودِ کاربر).
 * اول با HTTPS (ipinfo.io)؛ اگر جواب نداد، با ip-api.com که فقط HTTP دارد.
 */
fun lookupGeo(target: String = ""): Pair<Geo, String>? {
    val ip = if (target.isBlank()) "" else runCatching { java.net.InetAddress.getByName(target).hostAddress }.getOrNull() ?: target
    return lookupGeoHttps(ip) ?: lookupGeoHttp(ip)
}

private fun lookupGeoHttps(ip: String): Pair<Geo, String>? = runCatching {
    val path = if (ip.isBlank()) "json" else "$ip/json"
    val c = java.net.URL("https://ipinfo.io/$path").openConnection() as java.net.HttpURLConnection
    c.connectTimeout = 4000; c.readTimeout = 4000
    val j = try { JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
    val loc = j.optString("loc").split(",")
    val cc = j.optString("country")
    if (loc.size != 2 || cc.length != 2) null
    else Geo(loc[0].trim().toDouble(), loc[1].trim().toDouble()) to cc
}.getOrNull()

private fun lookupGeoHttp(target: String): Pair<Geo, String>? = runCatching {
    val c = java.net.URL("http://ip-api.com/json/$target?fields=status,lat,lon,countryCode,city")
        .openConnection() as java.net.HttpURLConnection
    c.connectTimeout = 5000; c.readTimeout = 5000
    val j = try { JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
    if (j.optString("status") != "success") null
    else Geo(j.optDouble("lat"), j.optDouble("lon")) to j.optString("countryCode")
}.getOrNull()

data class MapPin(val geo: Geo, val iso: String?, val selected: Boolean)

private data class View(val lon0: Double, val lon1: Double, val lat0: Double, val lat1: Double)

private fun lerp(a: Double, b: Double, t: Float) = a + (b - a) * t

/** محدوده‌ای که نقاط را با حاشیه و نسبت درست نشان می‌دهد */
private fun fitView(pts: List<Geo>, aspect: Double, minSpan: Double): View {
    val lon0 = pts.minOf { it.lon }; val lon1 = pts.maxOf { it.lon }
    val lat0 = pts.maxOf { it.lat }; val lat1 = pts.minOf { it.lat }
    var w = (lon1 - lon0).coerceAtLeast(minSpan) * 1.5
    var h = (lat0 - lat1).coerceAtLeast(minSpan / aspect) * 1.7
    if (w / h < aspect) w = h * aspect else h = w / aspect
    val cx = (lon0 + lon1) / 2
    val cy = (lat0 + lat1) / 2 + h * .06
    return View(cx - w / 2, cx + w / 2, cy + h / 2, cy - h / 2)
}

/**
 * نقشه‌ی واقعی کشورها (مثل پروتون):
 * کشور شما آبی، کشور سرور طلایی/سبز با هاله، خط منحنی نئونی بین شما و سرور، زوم نرم.
 */
@Composable
fun WorldMap(
    pins: List<MapPin>, user: Geo?, userIso: String?, connected: Boolean, modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val tm = androidx.compose.ui.text.rememberTextMeasurer()
    var countries by remember { mutableStateOf(MapData.countries) }
    LaunchedEffect(Unit) { if (countries == null) countries = withContext(Dispatchers.Default) { MapData.load(ctx) } }

    val sel = pins.firstOrNull { it.selected }
    val zoom by animateFloatAsState(if (connected) 1f else 0f, tween(1300, easing = FastOutSlowInEasing), label = "z")
    val draw by animateFloatAsState(if (connected) 1f else 0f, tween(if (connected) 1400 else 150, delayMillis = if (connected) 700 else 0), label = "d")
    val t = rememberInfiniteTransition(label = "map")
    val flow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "f")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "p")

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val aspect = (w / h).toDouble()
        // نمای عادی: منطقه‌ی اطراف کاربر و سرور انتخابی ؛ نمای اتصال: زوم نزدیک‌تر روی مسیر
        val pts = listOfNotNull(user, sel?.geo).ifEmpty { listOf(Geo(45.0, 20.0)) }
        val far = fitView(pts, aspect, 70.0)
        val near = fitView(pts, aspect, 26.0)
        val v = View(lerp(far.lon0, near.lon0, zoom), lerp(far.lon1, near.lon1, zoom), lerp(far.lat0, near.lat0, zoom), lerp(far.lat1, near.lat1, zoom))
        val sx = (w / (v.lon1 - v.lon0)).toFloat()
        val sy = (h / (v.lat0 - v.lat1)).toFloat()
        fun proj(g: Geo) = Offset(((g.lon - v.lon0) * sx).toFloat(), ((v.lat0 - g.lat) * sy).toFloat())

        val selIso = sel?.iso
        val border = 1.1f / sx
        clipRect {
            withTransform({
                translate((-v.lon0 * sx).toFloat(), (v.lat0 * sy).toFloat())
                scale(sx, sy, pivot = Offset.Zero)
            }) {
                countries?.forEach { c ->
                    if (c.maxLon < v.lon0 || c.minLon > v.lon1 || c.maxLat < v.lat1 || c.minLat > v.lat0) return@forEach
                    val fill = when (c.iso) {
                        selIso -> (if (connected) K.Green else K.Gold).copy(alpha = .30f)
                        userIso -> K.Accent.copy(alpha = .18f)
                        else -> K.Surface.copy(alpha = 1f)
                    }
                    drawPath(c.path, fill)
                    drawPath(
                        c.path,
                        if (c.iso == selIso) (if (connected) K.Green else K.Gold).copy(alpha = .9f) else K.Gold.copy(alpha = .22f),
                        style = Stroke(if (c.iso == selIso) border * 1.8f else border),
                    )
                }
            }
        }

        // خط منحنی نئونی سبز از شما تا سرور
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
            val sw = w / 220f
            for ((m, al) in listOf(5f to .07f, 3f to .16f, 1.8f to .36f, 1f to 1f)) {
                drawPath(path, K.Green.copy(alpha = al), style = Stroke(sw * m, cap = StrokeCap.Round))
            }
            drawPath(path, Color.White.copy(alpha = .8f), style = Stroke(sw * .35f, cap = StrokeCap.Round))
            if (draw >= 1f) neonDot(K.Green, bez(flow), sw * 1.5f)
        }

        // هاله و سنجاق سرور
        sel?.let {
            val o = proj(it.geo)
            val c = if (connected) K.Green else K.Gold
            val r = w / 13f
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = .35f), c.copy(alpha = .12f), Color.Transparent), o, r * 1.3f), r * 1.3f, o)
            drawCircle(c.copy(alpha = .55f), r * .85f, o, style = Stroke(w / 260f))
            if (connected) drawCircle(c.copy(alpha = (1 - pulse) * .7f), r * (.4f + pulse * .9f), o, style = Stroke(w / 300f))
            neonDot(c, o, w / 95f)
            // سنجاق مکان با پرچم کشور روی نقطه‌ی سرور
            val pr = w / 30f
            val head = Offset(o.x, o.y - pr * 2.3f)
            val pin = Path().apply {
                moveTo(o.x, o.y - pr * .35f)
                lineTo(head.x - pr * .75f, head.y + pr * .55f)
                lineTo(head.x + pr * .75f, head.y + pr * .55f)
                close()
            }
            drawPath(pin, c)
            drawCircle(c.copy(alpha = .35f), pr * 1.45f, head)
            drawCircle(c, pr * 1.15f, head)
            drawCircle(K.Panel, pr * .98f, head)
            flagEmoji(it.iso)?.let { f ->
                val layout = tm.measure(f, androidx.compose.ui.text.TextStyle(fontSize = (pr * 1.15f).toSp()))
                drawText(layout, topLeft = Offset(head.x - layout.size.width / 2f, head.y - layout.size.height / 2f))
            }
        }
        // بقیه‌ی سرورها
        pins.filter { !it.selected }.forEach { p -> neonDot(K.Gold.copy(alpha = .8f), proj(p.geo), w / 150f) }
        // شما
        user?.let { neonDot(K.Accent, proj(it), w / 120f) }
    }
}

private fun DrawScope.neonDot(c: Color, o: Offset, r: Float) {
    drawCircle(c.copy(alpha = .15f), r * 3.5f, o)
    drawCircle(c.copy(alpha = .35f), r * 2f, o)
    drawCircle(c, r, o)
    drawCircle(Color.White, r * .45f, o)
}
