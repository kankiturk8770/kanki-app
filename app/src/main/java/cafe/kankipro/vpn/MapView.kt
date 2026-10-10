package cafe.kankipro.vpn

import android.content.Context
import androidx.compose.ui.graphics.Path
import org.json.JSONArray
import org.json.JSONObject

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
