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

/**
 * تست پینگ سرورها.
 * AmneziaWG، WireGuard و Hysteria2 روی UDP کار می‌کنند و پورتشان به اتصال TCP جواب نمی‌دهد؛
 * پس برای آن‌ها اول پینگ ICMP، بعد isReachable و در آخر اتصال TCP به یک پورت TCP شناخته‌شده‌ی
 * همان سرور سنجیده می‌شود. Xray (روی TCP) با زمان اتصال TCP به پورت خودش سنجیده می‌شود.
 */
object Pinger {
    /** پورت‌های TCP که معمولاً روی سرورهای کانکی باز است (پنل، وب، SSH) */
    private val knownTcp = listOf(443, 2053, 80, 22, 8443)

    private fun pingBin(): String =
        listOf("/system/bin/ping", "/system/xbin/ping").firstOrNull { java.io.File(it).exists() } ?: "ping"

    /** زمان‌های پاسخ ICMP (میلی‌ثانیه) */
    private fun icmpTimes(host: String, count: Int): List<Double> = runCatching {
        val args = mutableListOf(pingBin(), "-c", count.toString(), "-W", "2")
        if (count > 1) { args.add("-i"); args.add("0.3") }
        args.add(host)
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        if (!p.waitFor(4L + count * 2L, TimeUnit.SECONDS)) { p.destroy(); return@runCatching emptyList<Double>() }
        val out = p.inputStream.bufferedReader().readText()
        Regex("""time[=<]\s*([\d.]+)""").findAll(out).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
    }.getOrDefault(emptyList())

    /** پینگ ICMP؛ میانگین به میلی‌ثانیه یا null */
    fun icmp(host: String, count: Int = 2): Long? {
        val t = icmpTimes(host, count)
        return if (t.isEmpty()) null else t.average().toLong().coerceAtLeast(1)
    }

    /** InetAddress.isReachable (ICMP یا TCP echo)؛ زمان پاسخ یا null */
    fun reach(host: String): Long? = runCatching {
        val a = java.net.InetAddress.getByName(host)
        val t0 = System.nanoTime()
        if (a.isReachable(2000)) ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1) else null
    }.getOrNull()

    /** پینگ TCP (زمان برقراری اتصال) */
    fun tcp(host: String, port: Int, tries: Int = 3): Long? {
        val r = (1..tries).mapNotNull {
            runCatching {
                Socket().use { s ->
                    val t0 = System.nanoTime()
                    s.connect(InetSocketAddress(host, port), 2500)
                    (System.nanoTime() - t0) / 1_000_000
                }
            }.getOrNull()
        }
        return if (r.isEmpty()) null else r.average().toLong().coerceAtLeast(1)
    }

    /** اتصال TCP به اولین پورت TCP باز از پورت‌های شناخته‌شده‌ی همان سرور */
    fun tcpAny(host: String): Long? {
        for (pt in knownTcp) tcp(host, pt, 1)?.let { return it }
        return null
    }

    /** پینگ و جیتر (میلی‌ثانیه)؛ اول ICMP، اگر بسته بود TCP */
    fun stats(host: String, count: Int = 4): Pair<Long, Long>? {
        val times = icmpTimes(host, count).ifEmpty {
            val port = knownTcp.firstOrNull { tcp(host, it, 1) != null } ?: return null
            (1..3).mapNotNull { tcp(host, port, 1)?.toDouble() }
        }
        return summarize(times)
    }

    private fun summarize(times: List<Double>): Pair<Long, Long>? {
        if (times.isEmpty()) return null
        val avg = times.average().toLong().coerceAtLeast(1)
        val jitter = if (times.size < 2) 0L else times.zipWithNext { a, b -> abs(a - b) }.average().toLong()
        return avg to jitter
    }

    /** پینگ و جیتر یک کانفیگ با روش درست همان پروتکل (برای انتخاب هوشمند) */
    fun measure(info: ConfigInfo): Pair<Long, Long>? {
        val host = info.host
        if (host.isBlank() || host == "—") return null
        if (info.transport == "TCP" && info.port > 0) {
            val t = (1..3).mapNotNull { tcp(host, info.port, 1)?.toDouble() }
            return summarize(t) ?: stats(host, 3)
        }
        return stats(host, 3) ?: reach(host)?.let { it to 0L }
    }

    /** پینگ یک کانفیگ با روش مناسب پروتکلش */
    fun test(info: ConfigInfo): Long? {
        val host = info.host
        if (host.isBlank() || host == "—") return null
        // Xray و OpenVPN TCP: زمان اتصال به خود پورت
        if (info.transport == "TCP" && info.port > 0) return tcp(host, info.port) ?: icmp(host)
        // UDP (WireGuard، AmneziaWG، Hysteria2/QUIC): پورت UDP به TCP جواب نمی‌دهد
        return icmp(host) ?: reach(host) ?: tcpAny(host)
    }
}

/** پینگ واقعی مثل v2rayNG: زمان پاسخ HTTP از مسیر فعلی (وقتی تونل بالاست، از داخل تونل) */
object RealPing {
    private const val URL_204 = "http://www.gstatic.com/generate_204"
    private const val URL_CF = "http://cp.cloudflare.com/generate_204"

    /** برای نظارت بر سلامت تونل: اگر یکی از دو آدرس جواب داد، تونل زنده است */
    fun alive(): Boolean = once(URL_204) != null || once(URL_CF) != null

    /** یک درخواست کوتاه برای بررسی اتصال تازه: دو آدرس به نوبت، هر کدام با مهلت کوتاه */
    fun quick(attempt: Int): Long? = once(if (attempt % 2 == 0) URL_204 else URL_CF, 3000)

    fun once(url: String = URL_204, timeoutMs: Int = 5000): Long? = runCatching {
        val t0 = System.nanoTime()
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs; readTimeout = timeoutMs; useCaches = false
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

