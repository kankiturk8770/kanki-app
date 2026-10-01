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
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** یک نقطه‌ی جغرافیایی */
data class Geo(val lat: Double, val lon: Double)

/** داده‌ی نقشه: نقطه‌های خشکی (هر ۲.۵ درجه) از فایل land.geojson */
object MapData {
    const val TOP = 78.0
    const val BOTTOM = -56.0
    private const val STEP = 2.5

    @Volatile
    var dots: List<Geo>? = null
        private set

    fun load(ctx: Context): List<Geo> {
        dots?.let { return it }
        val rings = mutableListOf<DoubleArray>() // [lon0,lat0,lon1,lat1,...]
        runCatching {
            val txt = ctx.assets.open("land.geojson").bufferedReader().use { it.readText() }
            val feats = JSONObject(txt).optJSONArray("features") ?: JSONArray()
            for (i in 0 until feats.length()) {
                val g = feats.optJSONObject(i)?.optJSONObject("geometry") ?: continue
                val coords = g.optJSONArray("coordinates") ?: continue
                when (g.optString("type")) {
                    "Polygon" -> coords.optJSONArray(0)?.let { rings.add(ring(it)) }
                    "MultiPolygon" -> for (j in 0 until coords.length()) coords.optJSONArray(j)?.optJSONArray(0)?.let { rings.add(ring(it)) }
                }
            }
        }
        val boxes = rings.map { r ->
            var minX = 999.0; var maxX = -999.0; var minY = 999.0; var maxY = -999.0
            var k = 0
            while (k < r.size) { minX = minOf(minX, r[k]); maxX = maxOf(maxX, r[k]); minY = minOf(minY, r[k + 1]); maxY = maxOf(maxY, r[k + 1]); k += 2 }
            doubleArrayOf(minX, maxX, minY, maxY)
        }
        val out = ArrayList<Geo>()
        var lat = TOP - STEP / 2
        while (lat > BOTTOM) {
            var lon = -180.0 + STEP / 2
            while (lon < 180.0) {
                for (idx in rings.indices) {
                    val b = boxes[idx]
                    if (lon < b[0] || lon > b[1] || lat < b[2] || lat > b[3]) continue
                    if (inside(rings[idx], lon, lat)) { out.add(Geo(lat, lon)); break }
                }
                lon += STEP
            }
            lat -= STEP
        }
        dots = out
        return out
    }

    private fun ring(a: JSONArray): DoubleArray {
        val r = DoubleArray(a.length() * 2)
        for (i in 0 until a.length()) {
            val p = a.optJSONArray(i) ?: continue
            r[i * 2] = p.optDouble(0); r[i * 2 + 1] = p.optDouble(1)
        }
        return r
    }

    private fun inside(r: DoubleArray, x: Double, y: Double): Boolean {
        var c = false
        val n = r.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = r[i * 2]; val yi = r[i * 2 + 1]; val xj = r[j * 2]; val yj = r[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }
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

/** موقعیت یک IP یا دامنه با ip-api.com (بدون کلید) */
fun lookupGeo(target: String = ""): Pair<Geo, String>? = runCatching {
    val c = java.net.URL("http://ip-api.com/json/$target?fields=status,lat,lon,countryCode")
        .openConnection() as java.net.HttpURLConnection
    c.connectTimeout = 5000; c.readTimeout = 5000
    val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    c.disconnect()
    if (j.optString("status") != "success") null
    else Geo(j.optDouble("lat"), j.optDouble("lon")) to j.optString("countryCode")
}.getOrNull()

data class MapPin(val geo: Geo, val flag: String, val selected: Boolean)

/** محدوده‌ی دید نقشه (طول و عرض جغرافیایی) */
private data class View(val lon0: Double, val lon1: Double, val lat0: Double, val lat1: Double)

private fun lerp(a: Double, b: Double, t: Float) = a + (b - a) * t

/** محدوده‌ای که هر دو نقطه را با حاشیه و نسبت درست نشان می‌دهد */
private fun fitView(a: Geo, b: Geo, aspect: Double): View {
    var lon0 = minOf(a.lon, b.lon); var lon1 = maxOf(a.lon, b.lon)
    var lat0 = maxOf(a.lat, b.lat); var lat1 = minOf(a.lat, b.lat)
    var w = (lon1 - lon0).coerceAtLeast(24.0) * 1.7
    var h = (lat0 - lat1).coerceAtLeast(10.0) * 1.9
    if (w / h < aspect) w = h * aspect else h = w / aspect
    val cx = (lon0 + lon1) / 2
    val cy = (lat0 + lat1) / 2 + h * .08
    lon0 = cx - w / 2; lon1 = cx + w / 2; lat0 = cy + h / 2; lat1 = cy - h / 2
    return View(lon0, lon1, lat0, lat1)
}

/** نقشه‌ی طلایی جهان با زوم خودکار روی مسیر اتصال و خط نئونی سبز */
@Composable
fun WorldMap(pins: List<MapPin>, user: Geo?, connected: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var dots by remember { mutableStateOf(MapData.dots) }
    LaunchedEffect(Unit) { if (dots == null) dots = withContext(Dispatchers.Default) { MapData.load(ctx) } }

    val sel = pins.firstOrNull { it.selected }
    val zoomed = connected && user != null && sel != null
    val zoom by animateFloatAsState(if (zoomed) 1f else 0f, tween(1400, easing = FastOutSlowInEasing), label = "z")
    val draw by animateFloatAsState(if (zoomed) 1f else 0f, tween(if (zoomed) 1600 else 200, delayMillis = if (zoomed) 900 else 0), label = "d")

    val t = rememberInfiniteTransition(label = "map")
    val flow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "f")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1700, easing = LinearEasing)), label = "p")

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val aspect = (w / h).toDouble()
        val world = View(-165.0, 180.0, 80.0, 80.0 - 345.0 / aspect)
        val target = if (user != null && sel != null) fitView(user, sel.geo, aspect) else world
        val v = View(
            lerp(world.lon0, target.lon0, zoom), lerp(world.lon1, target.lon1, zoom),
            lerp(world.lat0, target.lat0, zoom), lerp(world.lat1, target.lat1, zoom),
        )
        val scale = (345.0 / (v.lon1 - v.lon0)).toFloat()
        fun proj(g: Geo) = Offset(
            ((g.lon - v.lon0) / (v.lon1 - v.lon0) * w).toFloat(),
            ((v.lat0 - g.lat) / (v.lat0 - v.lat1) * h).toFloat(),
        )

        // نقطه‌های خشکی (با زوم درشت‌تر می‌شوند)
        dots?.let { list ->
            val pts = ArrayList<Offset>(list.size)
            for (g in list) {
                val o = proj(g)
                if (o.x > -10 && o.x < w + 10 && o.y > -10 && o.y < h + 10) pts.add(o)
            }
            val dw = (w / 175f) * scale.coerceAtMost(4.5f) * .9f
            drawPoints(pts, PointMode.Points, K.Gold.copy(alpha = .40f), strokeWidth = dw, cap = StrokeCap.Round)
        }

        // خط نئونی سبز از کاربر تا سرور (با انیمیشن کشیده شدن)
        if (user != null && sel != null && draw > 0f) {
            val a = proj(user)
            val b = proj(sel.geo)
            val lift = (kotlin.math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) * .35).toFloat()
            val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2 - lift)
            fun bez(q: Float) = Offset(
                (1 - q) * (1 - q) * a.x + 2 * (1 - q) * q * mid.x + q * q * b.x,
                (1 - q) * (1 - q) * a.y + 2 * (1 - q) * q * mid.y + q * q * b.y,
            )
            val path = Path().apply {
                moveTo(a.x, a.y)
                val steps = 40
                for (i in 1..(steps * draw).toInt().coerceAtLeast(1)) { val o = bez(i / steps.toFloat()); lineTo(o.x, o.y) }
            }
            val sw = w / 210f
            for ((m, al) in listOf(5f to .08f, 3f to .18f, 1.8f to .38f, 1f to 1f)) {
                drawPath(path, K.Green.copy(alpha = al), style = Stroke(sw * m, cap = StrokeCap.Round))
            }
            drawPath(path, Color.White.copy(alpha = .85f), style = Stroke(sw * .35f, cap = StrokeCap.Round))
            if (draw >= 1f) {
                neonDot(K.Green, bez(flow), sw * 1.6f)
                drawCircle(K.Green.copy(alpha = (1 - pulse) * .85f), sw * 2f + pulse * w / 16f, b, style = Stroke(sw * .8f))
            }
        }

        // سرورها
        pins.forEach { p ->
            val o = proj(p.geo)
            val c = if (p.selected && connected) K.Green else K.Gold
            val r = (if (p.selected) w / 85f else w / 120f) * (1f + zoom * .4f)
            drawCircle(c.copy(alpha = .18f), r * 3.2f, o)
            drawCircle(c.copy(alpha = .35f), r * 1.8f, o)
            drawCircle(c, r, o)
            drawCircle(Color.White, r * .4f, o)
        }
        // مکان واقعی کاربر
        user?.let {
            val o = proj(it)
            val cy = Color(0xFF6FE7FF)
            drawCircle(cy.copy(alpha = (1 - pulse) * .6f), w / 120f + pulse * w / 30f, o, style = Stroke(w / 220f))
            neonDot(cy, o, w / 125f * (1f + zoom * .3f))
        }
    }
}

private fun DrawScope.neonDot(c: Color, o: Offset, r: Float) {
    drawCircle(c.copy(alpha = .15f), r * 3.5f, o)
    drawCircle(c.copy(alpha = .35f), r * 2f, o)
    drawCircle(c, r, o)
    drawCircle(Color.White, r * .45f, o)
}
