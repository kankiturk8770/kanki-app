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

/** نقشه‌ی طلایی جهان با سرورها، مکان کاربر و خط نئونی اتصال */
@Composable
fun WorldMap(pins: List<MapPin>, user: Geo?, connected: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var dots by remember { mutableStateOf(MapData.dots) }
    LaunchedEffect(Unit) { if (dots == null) dots = withContext(Dispatchers.Default) { MapData.load(ctx) } }

    val t = rememberInfiniteTransition(label = "map")
    val flow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "f")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "p")

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun proj(g: Geo) = Offset(((g.lon + 180) / 360 * w).toFloat(), ((MapData.TOP - g.lat) / (MapData.TOP - MapData.BOTTOM) * h).toFloat())

        // نقطه‌های خشکی
        dots?.let { list ->
            val pts = list.map { proj(it) }
            drawPoints(pts, PointMode.Points, K.Gold.copy(alpha = .42f), strokeWidth = w / 170f, cap = StrokeCap.Round)
        }

        val sel = pins.firstOrNull { it.selected }
        // خط نئونی سبز از کاربر به سرور
        if (connected && user != null && sel != null) {
            val a = proj(user)
            val b = proj(sel.geo)
            val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2 - (kotlin.math.abs(b.x - a.x) * .35f + h * .08f))
            val path = Path().apply { moveTo(a.x, a.y); quadraticBezierTo(mid.x, mid.y, b.x, b.y) }
            for ((m, al) in listOf(5f to .08f, 3f to .18f, 1.8f to .38f, 1f to 1f)) {
                drawPath(path, K.Green.copy(alpha = al), style = Stroke(w / 260f * m * 1.6f, cap = StrokeCap.Round))
            }
            drawPath(
                path, Color.White.copy(alpha = .9f),
                style = Stroke(w / 400f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w / 60f, w / 40f), -flow * (w / 60f + w / 40f) * 3)),
            )
            // ذره‌ی نورانی در حال حرکت
            val q = flow
            val px = (1 - q) * (1 - q) * a.x + 2 * (1 - q) * q * mid.x + q * q * b.x
            val py = (1 - q) * (1 - q) * a.y + 2 * (1 - q) * q * mid.y + q * q * b.y
            neonDot(K.Green, Offset(px, py), w / 110f)
            // موج رسیدن به مقصد
            drawCircle(K.Green.copy(alpha = (1 - pulse) * .8f), w / 90f + pulse * w / 22f, b, style = Stroke(w / 300f * 2))
        }

        // سرورها
        pins.forEach { p ->
            val o = proj(p.geo)
            val c = if (p.selected && connected) K.Green else K.Gold
            val r = if (p.selected) w / 85f else w / 120f
            drawCircle(c.copy(alpha = .18f), r * 3.2f, o)
            drawCircle(c.copy(alpha = .35f), r * 1.8f, o)
            drawCircle(c, r, o)
            drawCircle(Color.White, r * .4f, o)
        }
        // مکان کاربر
        user?.let {
            val o = proj(it)
            val cy = Color(0xFF6FE7FF)
            drawCircle(cy.copy(alpha = (1 - pulse) * .6f), w / 120f + pulse * w / 35f, o, style = Stroke(w / 400f * 2))
            neonDot(cy, o, w / 130f)
        }
    }
}

private fun DrawScope.neonDot(c: Color, o: Offset, r: Float) {
    drawCircle(c.copy(alpha = .15f), r * 3.5f, o)
    drawCircle(c.copy(alpha = .35f), r * 2f, o)
    drawCircle(c, r, o)
    drawCircle(Color.White, r * .45f, o)
}
