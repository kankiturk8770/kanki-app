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

/** پینگ واقعی مثل v2rayNG: زمان پاسخ HTTP از مسیر فعلی (وقتی تونل بالاست، از داخل تونل) */
object RealPing {
    private const val URL_204 = "http://www.gstatic.com/generate_204"

    fun once(): Long? = runCatching {
        val t0 = System.nanoTime()
        val c = (URL(URL_204).openConnection() as HttpURLConnection).apply {
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

/** تست سرعت با سرورهای Speedtest by Ookla (در صورت خطا Cloudflare) */
object Ookla {
    data class Server(val host: String, val name: String, val sponsor: String)

    private fun get(url: String, timeout: Int = 6000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeout; readTimeout = timeout + 2000
            setRequestProperty("User-Agent", "Mozilla/5.0 KankiVPN")
        }

    /** نزدیک‌ترین سرورها را می‌گیرد و کم‌پینگ‌ترین را انتخاب می‌کند */
    fun pickServer(): Pair<Server, Long>? {
        val json = runCatching {
            val c = get("https://www.speedtest.net/api/js/servers?engine=js&limit=6&https_functional=1")
            c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
        }.getOrNull() ?: return null
        val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return null
        val list = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val host = o.optString("host")
            if (host.isBlank()) null else Server(host, o.optString("name"), o.optString("sponsor"))
        }
        return list.mapNotNull { s -> latency(s, 3)?.let { s to it } }.minByOrNull { it.second }
    }

    fun latency(s: Server, tries: Int = 6): Long? {
        val r = (1..tries).mapNotNull {
            runCatching {
                val t0 = System.nanoTime()
                val c = get("https://${s.host}/hello?nocache=${System.nanoTime()}", 4000)
                c.inputStream.use { it.readBytes() }
                c.disconnect()
                (System.nanoTime() - t0) / 1_000_000
            }.getOrNull()
        }
        return if (r.size < 2) r.firstOrNull() else r.drop(1).minOrNull()
    }

    fun jitter(s: Server): Long? {
        val r = (1..6).mapNotNull {
            runCatching {
                val t0 = System.nanoTime()
                val c = get("https://${s.host}/hello?nocache=${System.nanoTime()}", 4000)
                c.inputStream.use { it.readBytes() }
                c.disconnect()
                (System.nanoTime() - t0) / 1_000_000
            }.getOrNull()
        }.drop(1)
        return if (r.size < 2) null else r.zipWithNext { a, b -> abs(a - b) }.average().toLong()
    }

    fun download(s: Server, seconds: Double = 8.0, onProgress: (Double) -> Unit): Double {
        val start = System.nanoTime()
        var total = 0L
        var last = start
        val buf = ByteArray(64 * 1024)
        fun el() = (System.nanoTime() - start) / 1e9
        while (el() < seconds) {
            val c = get("https://${s.host}/download?nocache=${System.nanoTime()}&size=25000000")
            try {
                c.inputStream.use { input ->
                    while (el() < seconds) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        val now = System.nanoTime()
                        if (now - last > 200_000_000) { last = now; onProgress(total * 8 / 1e6 / el()) }
                    }
                }
            } catch (e: Exception) {
                if (total == 0L) throw e
                break
            } finally { c.disconnect() }
        }
        val mbps = total * 8 / 1e6 / el().coerceAtLeast(0.001)
        onProgress(mbps)
        return mbps
    }

    fun upload(s: Server, seconds: Double = 6.0, onProgress: (Double) -> Unit): Double {
        val chunk = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val start = System.nanoTime()
        var total = 0L
        fun el() = (System.nanoTime() - start) / 1e9
        while (el() < seconds) {
            val c = get("https://${s.host}/upload?nocache=${System.nanoTime()}")
            try {
                c.requestMethod = "POST"; c.doOutput = true
                c.setFixedLengthStreamingMode(chunk.size)
                c.setRequestProperty("Content-Type", "application/octet-stream")
                c.outputStream.use { out ->
                    var off = 0
                    while (off < chunk.size) {
                        val n = minOf(64 * 1024, chunk.size - off)
                        out.write(chunk, off, n); off += n; total += n
                        onProgress(total * 8 / 1e6 / el())
                    }
                }
                c.responseCode
            } catch (e: Exception) {
                if (total == 0L) throw e
                break
            } finally { c.disconnect() }
        }
        val mbps = total * 8 / 1e6 / el().coerceAtLeast(0.001)
        onProgress(mbps)
        return mbps
    }
}

/** تست سرعت با سرورهای Cloudflare (پشتیبان) */
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

    /** دانلود سریع با چند اتصال همزمان (دقیق‌تر و کوتاه‌تر) */
    fun downloadParallel(seconds: Double = 5.0, streams: Int = 4, onProgress: (Double) -> Unit): Double {
        val total = java.util.concurrent.atomic.AtomicLong(0)
        val start = System.nanoTime()
        fun el() = (System.nanoTime() - start) / 1e9
        val errors = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (1..streams).map {
            Thread {
                val buf = ByteArray(64 * 1024)
                while (el() < seconds) {
                    val c = runCatching { open("$BASE/__down?bytes=50000000") }.getOrNull() ?: break
                    try {
                        c.inputStream.use { input ->
                            while (el() < seconds) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total.addAndGet(n.toLong())
                            }
                        }
                    } catch (e: Exception) {
                        errors.incrementAndGet(); break
                    } finally { c.disconnect() }
                }
            }.apply { start() }
        }
        while (el() < seconds && threads.any { it.isAlive }) {
            Thread.sleep(200)
            onProgress(total.get() * 8 / 1e6 / el())
        }
        threads.forEach { it.join(1500) }
        if (total.get() == 0L && errors.get() > 0) throw java.io.IOException("اتصال به سرور تست برقرار نشد")
        val mbps = total.get() * 8 / 1e6 / el().coerceAtLeast(0.001)
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
