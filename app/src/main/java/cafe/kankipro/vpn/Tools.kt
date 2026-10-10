package cafe.kankipro.vpn

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** تست پینگ سرورها */
object Pinger {

    /** پینگ ICMP؛ میانگین به میلی‌ثانیه یا null */
    fun icmp(host: String, count: Int = 3): Long? = runCatching {
        val p = ProcessBuilder("ping", "-c", count.toString(), "-W", "2", host)
            .redirectErrorStream(true).start()
        if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroy(); return@runCatching null }
        val out = p.inputStream.bufferedReader().readText()
        val times = Regex("""time[=<]\s*([\d.]+)""").findAll(out).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        if (times.isEmpty()) null else times.average().toLong().coerceAtLeast(1)
    }.getOrNull()

    /** پینگ و جیتر (میلی‌ثانیه)؛ اول ICMP، اگر بسته بود TCP */
    fun stats(host: String, count: Int = 4): Pair<Long, Long>? {
        val icmp = runCatching {
            val p = ProcessBuilder("ping", "-c", count.toString(), "-i", "0.3", "-W", "2", host).redirectErrorStream(true).start()
            if (!p.waitFor(12, TimeUnit.SECONDS)) { p.destroy(); return@runCatching emptyList<Double>() }
            val out = p.inputStream.bufferedReader().readText()
            Regex("""time[=<]\s*([\d.]+)""").findAll(out).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        }.getOrDefault(emptyList())
        val times = icmp.ifEmpty {
            (1..3).mapNotNull {
                runCatching {
                    Socket().use { s ->
                        val t0 = System.nanoTime()
                        s.connect(InetSocketAddress(host, 443), 2500)
                        (System.nanoTime() - t0) / 1e6
                    }
                }.getOrNull()
            }
        }
        if (times.isEmpty()) return null
        val avg = times.average().toLong().coerceAtLeast(1)
        val jitter = if (times.size < 2) 0L else times.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average().toLong()
        return avg to jitter
    }

    /** پینگ TCP (زمان برقراری اتصال)؛ برای OpenVPN TCP */
    fun tcp(host: String, port: Int, tries: Int = 3): Long? {
        val r = (1..tries).mapNotNull {
            runCatching {
                Socket().use { s ->
                    val t0 = System.nanoTime()
                    s.connect(InetSocketAddress(host, port), 3000)
                    (System.nanoTime() - t0) / 1_000_000
                }
            }.getOrNull()
        }
        return if (r.isEmpty()) null else r.average().toLong().coerceAtLeast(1)
    }

    /** بر اساس نوع کانفیگ بهترین روش را انتخاب می‌کند */
    fun test(info: ConfigInfo): Long? {
        val host = info.host
        if (host.isBlank() || host == "—") return null
        return if (info.transport == "TCP" && info.port > 0) tcp(host, info.port) ?: icmp(host)
        else icmp(host) ?: if (info.port > 0) tcp(host, info.port, 1) else null
    }
}

/** پینگ واقعی مثل v2rayNG: زمان پاسخ HTTP از مسیر فعلی (وقتی تونل بالاست، از داخل تونل) */
object RealPing {
    private const val URL_204 = "http://www.gstatic.com/generate_204"
    private const val URL_CF = "http://cp.cloudflare.com/generate_204"

    /** برای نظارت بر سلامت تونل: اگر یکی از دو آدرس جواب داد، تونل زنده است */
    fun alive(): Boolean = once(URL_204) != null || once(URL_CF) != null

    fun once(url: String = URL_204): Long? = runCatching {
        val t0 = System.nanoTime()
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 5000; useCaches = false
        }
        val code = c.responseCode
        c.inputStream.use { it.readBytes() }
        if (code != 204 && code != 200) return@runCatching null
        (System.nanoTime() - t0) / 1_000_000
    }.getOrNull()

    /** پینگ و جیتر برای تست سرعت */
    fun latency(samples: Int = 6): Pair<Long, Long>? {
        once()
        val r = (1..samples).mapNotNull { once() }
        if (r.size < 2) return r.firstOrNull()?.let { it to 0L }
        return r.min() to r.zipWithNext { a, b -> abs(a - b) }.average().toLong()
    }

    /** یک بار گرم‌کردن، سپس کمترین زمان از چند تلاش */
    fun measure(tries: Int = 4): Long? {
        once()
        val r = (1..tries).mapNotNull { once() }
        return r.minOrNull()
    }
}

/** باز کردن پروفایل OpenVPN در اپ OpenVPN (OpenVPN Connect یا OpenVPN for Android) */
object OpenVpnHandoff {
    private const val MIME = "application/x-openvpn-profile"

    /** true اگر اپ OpenVPN باز شد؛ false اگر نصب نبود (فروشگاه باز می‌شود) */
    fun open(ctx: Context, profileText: String, fileName: String): Boolean {
        val dir = File(ctx.cacheDir, "ovpn").apply { mkdirs() }
        val safe = fileName.replace(Regex("[^A-Za-z0-9_-]"), "-")
        val file = File(dir, "$safe.ovpn")
        file.writeText(profileText)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (view.resolveActivity(ctx.packageManager) != null) {
            ctx.startActivity(view)
            return true
        }
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=net.openvpn.openvpn"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(store) }.onFailure {
            runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=net.openvpn.openvpn"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        return false
    }
}

/** FileProvider اختصاصی برای جلوگیری از تداخل با کتابخانه‌ها */
class KankiFileProvider : FileProvider()

