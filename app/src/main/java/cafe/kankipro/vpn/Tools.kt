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

/** تست سرعت با سرورهای Cloudflare */
object SpeedTest {
    private const val BASE = "https://speed.cloudflare.com"

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 8000
            setRequestProperty("User-Agent", "KankiVPN-Android")
        }

    /** پینگ و جیتر (میلی‌ثانیه) */
    fun latency(samples: Int = 8): Pair<Long, Long>? {
        val list = (1..samples).mapNotNull {
            runCatching {
                val t0 = System.nanoTime()
                val c = open("$BASE/__down?bytes=0")
                c.inputStream.use { it.readBytes() }
                c.disconnect()
                (System.nanoTime() - t0) / 1_000_000
            }.getOrNull()
        }
        if (list.size < 3) return null
        val rest = list.drop(1) // اولی شامل دست‌دادن TLS است
        val ping = rest.min()
        val jitter = rest.zipWithNext { a, b -> abs(a - b) }.average().toLong()
        return ping to jitter
    }

    /** دانلود؛ onProgress سرعت لحظه‌ای را به مگابیت بر ثانیه می‌دهد */
    fun download(seconds: Double = 8.0, onProgress: (Double) -> Unit): Double {
        val start = System.nanoTime()
        var total = 0L
        var lastReport = start
        val buf = ByteArray(64 * 1024)
        fun elapsed() = (System.nanoTime() - start) / 1e9
        while (elapsed() < seconds) {
            val c = runCatching { open("$BASE/__down?bytes=25000000") }.getOrNull() ?: break
            try {
                c.inputStream.use { input ->
                    while (elapsed() < seconds) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        val now = System.nanoTime()
                        if (now - lastReport > 200_000_000) {
                            lastReport = now
                            onProgress(total * 8 / 1e6 / elapsed())
                        }
                    }
                }
            } catch (e: Exception) {
                if (total == 0L) throw e
                break
            } finally {
                c.disconnect()
            }
        }
        val mbps = if (elapsed() > 0) total * 8 / 1e6 / elapsed() else 0.0
        onProgress(mbps)
        return mbps
    }

    /** آپلود */
    fun upload(seconds: Double = 6.0, onProgress: (Double) -> Unit): Double {
        val chunk = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val start = System.nanoTime()
        var total = 0L
        fun elapsed() = (System.nanoTime() - start) / 1e9
        while (elapsed() < seconds) {
            val c = open("$BASE/__up")
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setFixedLengthStreamingMode(chunk.size)
                c.setRequestProperty("Content-Type", "application/octet-stream")
                c.outputStream.use { out ->
                    var off = 0
                    while (off < chunk.size) {
                        val n = minOf(64 * 1024, chunk.size - off)
                        out.write(chunk, off, n)
                        off += n
                        total += n
                        onProgress(total * 8 / 1e6 / elapsed())
                    }
                }
                c.responseCode
            } catch (e: Exception) {
                if (total == 0L) throw e
                break
            } finally {
                c.disconnect()
            }
        }
        val mbps = if (elapsed() > 0) total * 8 / 1e6 / elapsed() else 0.0
        onProgress(mbps)
        return mbps
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
