package cafe.kankipro.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.ui.graphics.Path
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

/** موقعیت یک IP: مختصات، کد کشور و شهر */
data class GeoHit(val geo: Geo, val iso: String, val city: String)

/**
 * پیدا کردن موقعیت از روی IP عمومی (فقط IP خودِ درخواست؛ هیچ‌وقت از روی IP یا اسم دامنه‌ی سرورها حدس زده نمی‌شود).
 *   lookupSelf: موقعیت واقعی کاربر، از راه شبکه‌ی اصلی گوشی (حتی وقتی VPN روشن است، بیرون از VPN)
 *   lookupExit: IP خروجی تونل، از راه خود VPN (همان IP که سایت‌ها می‌بینند = مقصد واقعی)
 * اول با HTTPS (ipinfo.io)؛ اگر جواب نداد، با ip-api.com که فقط HTTP دارد.
 */
object GeoLookup {
    fun lookupSelf(ctx: Context): GeoHit? {
        val n = network(ctx, vpn = false)
        // شبکه‌ی اصلی پیدا نشد ولی VPN بالاست: درخواست معمولی از VPN می‌رود و کشور خروجی را می‌دهد، نه کاربر
        if (n == null && network(ctx, vpn = true) != null) return null
        return lookup(n)
    }

    /** null اگر VPN بالا نیست (تا نتیجه‌ی شبکه‌ی اصلی به‌جای خروجی تونل ذخیره نشود) */
    fun lookupExit(ctx: Context): GeoHit? {
        val n = network(ctx, vpn = true) ?: return null
        return lookup(n)
    }

    /** شبکه‌ی VPN یا شبکه‌ی اصلی (وای‌فای/دیتا) که اینترنت دارد */
    private fun network(ctx: Context, vpn: Boolean): Network? = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return@runCatching null
        cm.allNetworks.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n)
            c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == vpn &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }.getOrNull()

    private fun lookup(net: Network?): GeoHit? = viaIpinfo(net) ?: viaIpApi(net)

    private fun open(net: Network?, url: String, timeout: Int): HttpURLConnection {
        val u = URL(url)
        val c = (net?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout; c.useCaches = false
        return c
    }

    private fun viaIpinfo(net: Network?): GeoHit? = runCatching {
        val c = open(net, "https://ipinfo.io/json", 5000)
        val j = try { JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
        val loc = j.optString("loc").split(",")
        val cc = j.optString("country")
        if (loc.size != 2 || cc.length != 2) null
        else GeoHit(Geo(loc[0].trim().toDouble(), loc[1].trim().toDouble()), cc.uppercase(), j.optString("city"))
    }.getOrNull()

    private fun viaIpApi(net: Network?): GeoHit? = runCatching {
        val c = open(net, "http://ip-api.com/json/?fields=status,lat,lon,countryCode,city", 6000)
        val j = try { JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
        val cc = j.optString("countryCode")
        if (j.optString("status") != "success" || cc.length != 2) null
        else GeoHit(Geo(j.optDouble("lat"), j.optDouble("lon")), cc.uppercase(), j.optString("city"))
    }.getOrNull()
}
