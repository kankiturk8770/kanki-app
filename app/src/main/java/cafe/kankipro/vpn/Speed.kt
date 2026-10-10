package cafe.kankipro.vpn

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * تست سرعت به روش Speedtest by Ookla:
 *  ۱) نزدیک‌ترین سرورهای Ookla (بر اساس IP فعلی؛ وقتی وصلی یعنی نزدیک سرور VPN) گرفته می‌شود
 *     و کم‌پینگ‌ترینشان انتخاب می‌شود.
 *  ۲) دانلود و آپلود با چند اتصال همزمان انجام می‌شود (مثل اپ اصلی Speedtest) تا یک اتصال تکی
 *     سرعت را کمتر از واقعی نشان ندهد.
 *  ۳) ثانیه‌های اول (شروع آهسته‌ی TCP) حساب نمی‌شود. بقیه به ۲۰ تکه تقسیم می‌شود و مثل Ookla
 *     ۳۰٪ کندترین و ۱۰٪ سریع‌ترین تکه‌ها کنار گذاشته و از بقیه میانگین گرفته می‌شود.
 * اگر سرورهای Ookla در دسترس نبودند، همین روش با سرورهای Cloudflare انجام می‌شود.
 */
object SpeedEngine {
    class Server(val kind: String, val base: String, val name: String, val sponsor: String, val cc: String) {
        val title: String get() = if (kind == "cf") "Cloudflare" else listOf(sponsor, name).filter { it.isNotBlank() }.joinToString(" · ")
        /** سرورهای قدیمی Ookla فقط آدرس‌های قدیمی (/speedtest/...) را دارند */
        @Volatile var legacy = false
    }

    val CLOUDFLARE = Server("cf", "https://speed.cloudflare.com", "Cloudflare", "Cloudflare", "")

    private fun nocache() = "${System.nanoTime()}${(1000..9999).random()}"

    private fun open(url: String, timeout: Int = 6000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeout
            readTimeout = timeout + 4000
            useCaches = false
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) KankiVPN")
            setRequestProperty("Cache-Control", "no-cache")
        }

    private fun pingUrl(s: Server) = when {
        s.kind == "cf" -> "${s.base}/__down?bytes=0"
        s.legacy -> "${s.base}/speedtest/latency.txt?x=${nocache()}"
        else -> "${s.base}/hello?nocache=${nocache()}"
    }
    private fun downUrl(s: Server) = when {
        s.kind == "cf" -> "${s.base}/__down?bytes=25000000"
        s.legacy -> "${s.base}/speedtest/random4000x4000.jpg?x=${nocache()}"
        else -> "${s.base}/download?nocache=${nocache()}&size=25000000"
    }
    private fun upUrl(s: Server) = when {
        s.kind == "cf" -> "${s.base}/__up"
        s.legacy -> "${s.base}/speedtest/upload.php?x=${nocache()}"
        else -> "${s.base}/upload?nocache=${nocache()}"
    }

    /** یک رفت‌وبرگشت HTTP به میلی‌ثانیه (اتصال بعد از بار اول دوباره استفاده می‌شود) */
    private fun rtt(url: String, timeout: Int = 4000): Double? = runCatching {
        val t0 = System.nanoTime()
        val c = open(url, timeout)
        val code = c.responseCode
        c.inputStream.use { it.readBytes() }
        if (code !in 200..299) null else (System.nanoTime() - t0) / 1e6
    }.getOrNull()

    /** فهرست نزدیک‌ترین سرورهای Ookla */
    fun ooklaServers(limit: Int = 8): List<Server> = runCatching {
        val c = open("https://www.speedtest.net/api/js/servers?engine=js&https_functional=true&limit=$limit", 7000)
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val arr = JSONArray(body)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val host = o.optString("host").trim()
            if (host.isBlank()) null
            else Server("ookla", "https://$host", o.optString("name"), o.optString("sponsor"), o.optString("cc"))
        }
    }.getOrDefault(emptyList())

    /** کم‌پینگ‌ترین سرور Ookla از بین چند سرور نزدیک (همزمان سنجیده می‌شوند) */
    fun pickServer(): Server? {
        val list = ooklaServers().take(5)
        if (list.isEmpty()) return null
        val best = arrayOfNulls<Double>(list.size)
        val threads = list.mapIndexed { i, s ->
            Thread {
                // اولی شامل DNS و TLS است؛ اگر آدرس جدید جواب نداد، آدرس قدیمی امتحان می‌شود
                if (rtt(pingUrl(s), 3000) == null) {
                    s.legacy = true
                    if (rtt(pingUrl(s), 3000) == null) return@Thread
                }
                best[i] = (1..3).mapNotNull { rtt(pingUrl(s), 3000) }.minOrNull()
            }.apply { isDaemon = true; start() }
        }
        threads.forEach { it.join(9000) }
        return list.indices.filter { best[it] != null }.minByOrNull { best[it]!! }?.let { list[it] }
    }

    /** پینگ (میانه) و جیتر (میانگین اختلاف پشت‌سرهم) به میلی‌ثانیه */
    fun latency(s: Server, samples: Int = 10): Pair<Long, Long>? {
        rtt(pingUrl(s))
        val r = (1..samples).mapNotNull { rtt(pingUrl(s)) }
        if (r.size < 3) return null
        val ping = r.sorted()[r.size / 2]
        val jitter = r.zipWithNext { a, b -> abs(a - b) }.average()
        return ping.toLong().coerceAtLeast(1) to jitter.toLong()
    }

    /** میانگین به روش Ookla: ۲۰ تکه، حذف ۳۰٪ کندترین و ۱۰٪ سریع‌ترین */
    fun sliceAverage(samples: List<Double>): Double {
        if (samples.isEmpty()) return 0.0
        val n = minOf(20, samples.size)
        val slices = (0 until n).map { k ->
            val a = k * samples.size / n
            val b = ((k + 1) * samples.size / n).coerceAtLeast(a + 1)
            samples.subList(a, b).average()
        }.sorted()
        val lo = (n * .3).toInt()
        val hi = n - (n * .1).toInt()
        val kept = if (hi > lo) slices.subList(lo, hi) else slices
        return kept.average()
    }

    /**
     * اندازه‌گیری سرعت با چند اتصال همزمان. worker هر بار بایت‌های جابه‌جاشده را به total اضافه می‌کند
     * و تا وقتی alive() درست است ادامه می‌دهد. خروجی: مگابیت بر ثانیه و کل بایت مصرفی.
     */
    private fun measure(
        seconds: Double, warmup: Double, streams: Int, onProgress: (Double) -> Unit,
        worker: (AtomicLong, () -> Boolean) -> Unit,
    ): Pair<Double, Long> {
        val total = AtomicLong()
        val start = System.nanoTime()
        val end = start + (seconds * 1e9).toLong()
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val alive = { !stop.get() && System.nanoTime() < end }
        val errors = AtomicLong()
        val threads = (1..streams).map {
            Thread {
                while (alive()) {
                    runCatching { worker(total, alive) }.onFailure { errors.incrementAndGet(); Thread.sleep(150) }
                }
            }.apply { isDaemon = true; start() }
        }
        val samples = ArrayList<Double>()
        var lastB = 0L
        var lastT = start
        var ema = 0.0
        while (alive()) {
            Thread.sleep(100)
            val now = System.nanoTime()
            val b = total.get()
            val dt = (now - lastT) / 1e9
            if (dt > 0) {
                val inst = (b - lastB) * 8 / 1e6 / dt
                if ((now - start) / 1e9 >= warmup) samples.add(inst)
                ema = if (ema == 0.0) inst else ema * .75 + inst * .25
                onProgress(ema)
            }
            lastB = b; lastT = now
            // سرور جواب نمی‌دهد: زود دست می‌کشیم تا سراغ سرور دیگر برویم
            if (b == 0L && errors.get() >= streams && (now - start) / 1e9 > 2.5) stop.set(true)
        }
        threads.forEach { it.join(1500) }
        if (total.get() == 0L && errors.get() > 0) throw java.io.IOException("speed test server unreachable")
        val mbps = sliceAverage(samples)
        onProgress(mbps)
        return mbps to total.get()
    }

    fun download(s: Server, seconds: Double = 8.0, streams: Int = 6, onProgress: (Double) -> Unit): Pair<Double, Long> =
        measure(seconds, 1.2, streams, onProgress) { total, alive ->
            val c = open(downUrl(s))
            try {
                c.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (alive()) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total.addAndGet(n.toLong())
                    }
                }
            } finally { c.disconnect() }
        }

    /**
     * آپلود: «نوشتن در حافظه‌ی گوشی» با «رسیدن به سرور» یکی نیست (بافر شبکه چند مگابایت را
     * فوراً می‌گیرد). پس هر تکه فقط وقتی شمرده می‌شود که سرور دریافتش را تأیید کند، و سرعت از
     * فاصله‌ی اولین تا آخرین تأیید بعد از گرم شدن حساب می‌شود. اندازه‌ی تکه‌ها خودکار بزرگ می‌شود.
     */
    fun upload(s: Server, seconds: Double = 6.0, streams: Int = 4, onProgress: (Double) -> Unit): Pair<Double, Long> {
        val data = ByteArray(8 * 1024 * 1024) { (it * 31 % 251).toByte() }
        val start = System.nanoTime()
        val end = start + (seconds * 1e9).toLong()
        val warm = start + 1_000_000_000L
        val alive = { System.nanoTime() < end }
        val acked = AtomicLong()
        val sent = AtomicLong()
        val errors = AtomicLong()
        val marks = java.util.Collections.synchronizedList(ArrayList<Pair<Long, Long>>())   // (زمان، کل تأییدشده)
        val threads = (1..streams).map {
            Thread {
                var size = 256 * 1024
                while (alive()) {
                    runCatching {
                        val t0 = System.nanoTime()
                        val c = open(upUrl(s))
                        try {
                            c.requestMethod = "POST"
                            c.doOutput = true
                            c.setFixedLengthStreamingMode(size)
                            c.setRequestProperty("Content-Type", "application/octet-stream")
                            c.outputStream.use { out ->
                                var off = 0
                                while (off < size) {
                                    if (!alive()) throw java.io.InterruptedIOException("time is up")
                                    val n = minOf(64 * 1024, size - off)
                                    out.write(data, off, n)
                                    off += n
                                    sent.addAndGet(n.toLong())
                                }
                            }
                            if (c.responseCode in 200..299) {
                                val now = System.nanoTime()
                                marks.add(now to acked.addAndGet(size.toLong()))
                                if (now - t0 < 700_000_000L && size < data.size) size *= 2
                            }
                        } finally { c.disconnect() }
                    }.onFailure { if (alive()) { errors.incrementAndGet(); Thread.sleep(150) } }
                }
            }.apply { isDaemon = true; start() }
        }
        // سرعت لحظه‌ای: تأییدهای ۱.۵ ثانیه‌ی اخیر
        val hist = ArrayDeque<Pair<Long, Long>>()
        var ema = 0.0
        while (alive()) {
            Thread.sleep(100)
            val now = System.nanoTime()
            hist.addLast(now to acked.get())
            while (hist.size > 2 && now - hist.first().first > 1_500_000_000L) hist.removeFirst()
            val (t0, b0) = hist.first()
            if (now > t0 && acked.get() > b0) {
                val inst = (acked.get() - b0) * 8 / 1e6 / ((now - t0) / 1e9)
                ema = if (ema == 0.0) inst else ema * .7 + inst * .3
                onProgress(ema)
            }
        }
        threads.forEach { it.join(1500) }
        if (acked.get() == 0L && errors.get() > 0) throw java.io.IOException("speed test server unreachable")
        val m = synchronized(marks) { marks.filter { it.first >= warm }.sortedBy { it.first } }
        val mbps = if (m.size >= 2 && m.last().first > m.first().first)
            (m.last().second - m.first().second) * 8 / 1e6 / ((m.last().first - m.first().first) / 1e9)
        else acked.get() * 8 / 1e6 / seconds
        onProgress(mbps)
        return mbps to sent.get()
    }
}
