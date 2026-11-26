package cafe.kankipro.vpn

import android.net.Network
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * تست سرعت (۱.۳۵): ترکیب روش Speedtest by Ookla و Cloudflare.
 *  ۱) پینگ بی‌بار: ۲۰ درخواست خالی، میانه (مثل Cloudflare؛ زمان کار خود سرور Cloudflare از هدر server-timing کم می‌شود).
 *  ۲) دانلود و آپلود با چند اتصال همزمان؛ ثانیه‌های اول (شروع آهسته‌ی TCP) حساب نمی‌شود و بقیه مثل Ookla
 *     ۲۰ تکه می‌شود و ۳۰٪ کندترین و ۱۰٪ سریع‌ترین کنار می‌رود. «اوج» صدک ۹۰ نمونه‌هاست (مثل خط ۹۰٪ Cloudflare).
 *  ۳) پینگ زیر بار: در همان حال دانلود/آپلود هر ۴۰۰ میلی‌ثانیه یک پینگ (همان «↓/↑ ms» صفحه‌ی Cloudflare؛
 *     فرقش با پینگ بی‌بار همان چیزی است که در بازی وقتی کسی دانلود می‌کند حس می‌شود).
 *  ۴) net: اگر داده شود همه‌ی درخواست‌ها روی همان شبکه‌ی واقعی گوشی می‌روند (بیرون از VPN؛ «اینترنت من»).
 * سرورها: نزدیک‌ترین سرور Ookla (کم‌پینگ‌ترین از چند سرور) یا Cloudflare؛ اگر یکی جواب نداد، دیگری.
 */
object SpeedEngine {
    class Server(val kind: String, val base: String, val name: String, val sponsor: String, val cc: String) {
        val title: String get() = if (kind == "cf") "Cloudflare" else listOf(sponsor, name).filter { it.isNotBlank() }.joinToString(" · ")
        /** سرورهای قدیمی Ookla فقط آدرس‌های قدیمی (/speedtest/...) را دارند */
        @Volatile var legacy = false
    }

    val CLOUDFLARE = Server("cf", "https://speed.cloudflare.com", "Cloudflare", "Cloudflare", "")

    /** نتیجه‌ی دانلود یا آپلود: سرعت (Mbps)، اوج (صدک ۹۰)، بایت مصرفی، نقطه‌های نمودار و پینگ‌های زیر بار (ms) */
    class Bw(val mbps: Double, val peak: Double, val bytes: Long, val points: List<Float>, val loaded: List<Double>)

    /** پینگ بی‌بار: میانه، جیتر (میانگین فرق نمونه‌های پشت‌سرهم) و خود نمونه‌ها */
    class Lat(val ping: Double, val jitter: Double, val samples: List<Double>)

    /** IP بیرونی، شرکت اینترنت و محل (از speed.cloudflare.com/meta) */
    class Meta(val ip: String, val isp: String, val asn: Int?, val city: String, val country: String, val colo: String)

    private fun nocache() = "${System.nanoTime()}${(1000..9999).random()}"

    /** اتصال HTTP؛ با net روی همان شبکه (DNS هم از همان شبکه)، اگر نشد مسیر عادی */
    fun open(url: String, timeout: Int = 6000, net: Network? = null): HttpURLConnection {
        val u = URL(url)
        val c = (net?.let { runCatching { it.openConnection(u) }.getOrNull() } ?: u.openConnection()) as HttpURLConnection
        return c.apply {
            connectTimeout = timeout
            readTimeout = timeout + 4000
            useCaches = false
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) KankiVPN")
            setRequestProperty("Cache-Control", "no-cache")
        }
    }

    private fun pingUrl(s: Server) = when {
        s.kind == "cf" -> "${s.base}/__down?bytes=0&r=${nocache()}"
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

    /**
     * زمان کار خود سرور Cloudflare از هدر server-timing (مثل کتابخانه‌ی خود Cloudflare): اول cfRequestDuration،
     * وگرنه جمع cfSpeedEdge و cfSpeedWorker. هدرهای دیگر (cfL4) حساب نمی‌شوند.
     */
    fun serverTime(header: String?): Double {
        if (header.isNullOrBlank()) return 0.0
        Regex("""(?:^|,\s*)cfReq(?:uest)?Dur(?:ation)?;\s*dur=([0-9.]+)""", RegexOption.IGNORE_CASE).find(header)
            ?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > .01 && it < 5000 }?.let { return it }
        val sum = Regex("""(?:^|,\s*)cfSpeed[a-zA-Z]*;\s*dur=([0-9.]+)""", RegexOption.IGNORE_CASE).findAll(header)
            .sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
        return if (sum > .01 && sum < 5000) sum else 0.0
    }

    /** یک رفت‌وبرگشت HTTP به میلی‌ثانیه (اتصال بعد از بار اول دوباره استفاده می‌شود) */
    private fun rtt(url: String, timeout: Int = 4000, net: Network? = null): Double? = runCatching {
        val t0 = System.nanoTime()
        val c = open(url, timeout, net)
        val code = c.responseCode
        c.inputStream.use { it.readBytes() }
        val raw = (System.nanoTime() - t0) / 1e6
        val st = serverTime(c.headerFields.entries.filter { it.key.equals("server-timing", true) }.flatMap { it.value }.joinToString(", "))
        // زمان سرور فقط وقتی کم می‌شود که از کل کمتر باشد (ساعت‌ها دقیق نیستند)
        if (code !in 200..299) null else (if (st < raw * .9) raw - st else raw).coerceAtLeast(.5)
    }.getOrNull()

    /** IP، شرکت اینترنت و شهر (کوچک: چند صد بایت) */
    fun meta(net: Network? = null): Meta? = runCatching {
        val c = open("https://speed.cloudflare.com/meta", 6000, net)
        // بی Referer جواب ۴۰۳ است (همان کاری که صفحه‌ی خود speed.cloudflare.com می‌کند)
        c.setRequestProperty("Referer", "https://speed.cloudflare.com/")
        if (c.responseCode !in 200..299) return null
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText().take(8192) })
        // colo یا یک شیء است ({iata, city, cca2}) یا (قدیمی) فقط کد
        val colo = o.optJSONObject("colo")?.let { co ->
            listOf(co.optString("iata").take(8), co.optString("city").take(40)).filter { it.isNotBlank() }.joinToString(" · ")
        } ?: o.optString("colo").take(8)
        Meta(
            ip = o.optString("clientIp").take(64),
            isp = o.optString("asOrganization").take(80),
            asn = o.optInt("asn").takeIf { it > 0 },
            city = o.optString("city").take(60),
            country = o.optString("country").take(4).uppercase(),
            colo = colo,
        ).takeIf { it.ip.isNotBlank() }
    }.getOrNull()

    /** فهرست نزدیک‌ترین سرورهای Ookla (نزدیک به IP همان شبکه) */
    fun ooklaServers(limit: Int = 8, net: Network? = null): List<Server> = runCatching {
        val c = open("https://www.speedtest.net/api/js/servers?engine=js&https_functional=true&limit=$limit", 7000, net)
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val arr = JSONArray(body)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val host = o.optString("host").trim()
            // فقط «نام:پورت»؛ هر چیز دیگر (مسیر، @، فاصله) کنار می‌رود تا آدرس دلخواه ساخته نشود
            if (host.isBlank() || !Regex("""^[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?$""").matches(host)) null
            else Server("ookla", "https://$host", o.optString("name").take(60), o.optString("sponsor").take(60), o.optString("cc").take(4))
        }
    }.getOrDefault(emptyList())

    /** کم‌پینگ‌ترین سرور Ookla از بین چند سرور نزدیک (همزمان سنجیده می‌شوند) */
    fun pickServer(net: Network? = null): Server? {
        val list = ooklaServers(8, net).take(5)
        if (list.isEmpty()) return null
        val best = arrayOfNulls<Double>(list.size)
        val threads = list.mapIndexed { i, s ->
            Thread {
                // اولی شامل DNS و TLS است؛ اگر آدرس جدید جواب نداد، آدرس قدیمی امتحان می‌شود
                if (rtt(pingUrl(s), 3000, net) == null) {
                    s.legacy = true
                    if (rtt(pingUrl(s), 3000, net) == null) return@Thread
                }
                best[i] = (1..3).mapNotNull { rtt(pingUrl(s), 3000, net) }.minOrNull()
            }.apply { isDaemon = true; start() }
        }
        threads.forEach { it.join(9000) }
        return list.indices.mapNotNull { i -> best[i]?.let { i to it } }.minByOrNull { it.second }?.let { list[it.first] }
    }

    /** پینگ بی‌بار (میانه) و جیتر؛ اولی (DNS و TLS) حساب نمی‌شود */
    fun latency(s: Server, samples: Int = 20, net: Network? = null, onSample: (Double) -> Unit = {}): Lat? {
        rtt(pingUrl(s), net = net)
        val r = ArrayList<Double>()
        repeat(samples) { rtt(pingUrl(s), net = net)?.let { r.add(it); onSample(it) } }
        if (r.size < 3) return null
        return Lat(percentile(r, .5), jitter(r), r)
    }

    fun jitter(r: List<Double>): Double = if (r.size < 2) 0.0 else r.zipWithNext { a, b -> abs(a - b) }.average()

    /** صدک (مثل Cloudflare: درون‌یابی بین دو نمونه‌ی کنار هم) */
    fun percentile(vals: List<Double>, p: Double): Double {
        if (vals.isEmpty()) return 0.0
        val s = vals.sorted()
        val idx = (s.size - 1) * p
        val lo = kotlin.math.floor(idx).toInt()
        val hi = kotlin.math.ceil(idx).toInt()
        return s[lo] + (s[hi] - s[lo]) * (idx - lo)
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

    /** نمونه‌ها را به حداکثر n نقطه برای نمودار کوچک می‌کند (میانگین هر تکه) */
    fun downsample(v: List<Double>, n: Int = 48): List<Float> {
        if (v.size <= n) return v.map { it.toFloat() }
        return (0 until n).map { k ->
            val a = k * v.size / n
            val b = ((k + 1) * v.size / n).coerceAtLeast(a + 1)
            v.subList(a, b).average().toFloat()
        }
    }

    /** پینگ‌های زیر بار: هر ۴۰۰ میلی‌ثانیه یک پینگ تا وقتی alive() درست است (ثانیه‌ی اول گرم شدن حساب نمی‌شود) */
    private fun loadedPinger(s: Server, net: Network?, warmUntil: Long, alive: () -> Boolean): Pair<Thread, MutableList<Double>> {
        val out = java.util.Collections.synchronizedList(ArrayList<Double>())
        val t = Thread {
            while (alive()) {
                val t0 = System.nanoTime()
                val r = rtt(pingUrl(s), 3000, net)
                if (r != null && t0 >= warmUntil && alive()) out.add(r)
                val left = 400 - (System.nanoTime() - t0) / 1_000_000L
                if (left > 0) runCatching { Thread.sleep(left) }
            }
        }.apply { isDaemon = true; start() }
        return t to out
    }

    /**
     * اندازه‌گیری سرعت با چند اتصال همزمان. worker هر بار بایت‌های جابه‌جاشده را به total اضافه می‌کند
     * و تا وقتی alive() درست است ادامه می‌دهد.
     */
    private fun measure(
        s: Server, net: Network?, seconds: Double, warmup: Double, streams: Int, onProgress: (Double) -> Unit,
        worker: (AtomicLong, () -> Boolean) -> Unit,
    ): Bw {
        val total = AtomicLong()
        val start = System.nanoTime()
        val end = start + (seconds * 1e9).toLong()
        val stop = AtomicBoolean(false)
        val alive = { !stop.get() && System.nanoTime() < end }
        val errors = AtomicLong()
        val threads = (1..streams).map {
            Thread {
                while (alive()) {
                    runCatching { worker(total, alive) }.onFailure { errors.incrementAndGet(); runCatching { Thread.sleep(150) } }
                }
            }.apply { isDaemon = true; start() }
        }
        val (pinger, loaded) = loadedPinger(s, net, start + (warmup * 1e9).toLong(), alive)
        val samples = ArrayList<Double>()
        val curve = ArrayList<Double>()
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
                curve.add(ema)
                onProgress(ema)
            }
            lastB = b; lastT = now
            // سرور جواب نمی‌دهد: زود دست می‌کشیم تا سراغ سرور دیگر برویم
            if (b == 0L && errors.get() >= streams && (now - start) / 1e9 > 2.5) stop.set(true)
        }
        stop.set(true)
        threads.forEach { it.join(1500) }
        pinger.join(1500)
        if (total.get() == 0L && errors.get() > 0) throw java.io.IOException("speed test server unreachable")
        val mbps = sliceAverage(samples)
        onProgress(mbps)
        return Bw(mbps, percentile(samples, .9), total.get(), downsample(curve), synchronized(loaded) { loaded.toList() })
    }

    fun download(s: Server, seconds: Double = 8.0, streams: Int = 6, net: Network? = null, onProgress: (Double) -> Unit): Bw =
        measure(s, net, seconds, 1.2, streams, onProgress) { total, alive ->
            val c = open(downUrl(s), net = net)
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
    fun upload(s: Server, seconds: Double = 6.0, streams: Int = 4, net: Network? = null, onProgress: (Double) -> Unit): Bw {
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
                        val c = open(upUrl(s), net = net)
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
                    }.onFailure { if (alive()) { errors.incrementAndGet(); runCatching { Thread.sleep(150) } } }
                }
            }.apply { isDaemon = true; start() }
        }
        val (pinger, loaded) = loadedPinger(s, net, warm, alive)
        // سرعت لحظه‌ای: تأییدهای ۱.۵ ثانیه‌ی اخیر
        val hist = ArrayDeque<Pair<Long, Long>>()
        val curve = ArrayList<Double>()
        val inst = ArrayList<Double>()
        var ema = 0.0
        while (alive()) {
            Thread.sleep(100)
            val now = System.nanoTime()
            hist.addLast(now to acked.get())
            while (hist.size > 2 && now - hist.first().first > 1_500_000_000L) hist.removeFirst()
            val (t0, b0) = hist.first()
            if (now > t0 && acked.get() > b0) {
                val v = (acked.get() - b0) * 8 / 1e6 / ((now - t0) / 1e9)
                if (now >= warm) inst.add(v)
                ema = if (ema == 0.0) v else ema * .7 + v * .3
                onProgress(ema)
            }
            curve.add(ema)
        }
        threads.forEach { it.join(1500) }
        pinger.join(1500)
        if (acked.get() == 0L && errors.get() > 0) throw java.io.IOException("speed test server unreachable")
        val m = synchronized(marks) { marks.filter { it.first >= warm }.sortedBy { it.first } }
        val mbps = if (m.size >= 2 && m.last().first > m.first().first)
            (m.last().second - m.first().second) * 8 / 1e6 / ((m.last().first - m.first().first) / 1e9)
        else acked.get() * 8 / 1e6 / seconds
        onProgress(mbps)
        return Bw(mbps, maxOf(mbps, percentile(inst, .9)), sent.get(), downsample(curve), synchronized(loaded) { loaded.toList() })
    }
}

/**
 * امتیاز کیفیت شبکه برای پخش ویدیو، بازی و تماس تصویری؛ دقیقاً همان روش AIM کلادفلر
 * (کتابخانه‌ی @cloudflare/speedtest با مجوز MIT، internalConfig و ScoresCalculations):
 * هر عدد بر اساس آستانه‌ها امتیاز می‌گیرد، جمع امتیازهای لازم هر کاربرد یکی از پنج رده (۰ بد تا ۴ عالی) می‌شود.
 */
object Aim {
    private fun scale(domain: DoubleArray, range: IntArray, v: Double): Int {
        var i = 0
        while (i < domain.size && v >= domain[i]) i++
        return range[i]
    }

    private val pLoss = doubleArrayOf(.01, .05, .25, .5) to intArrayOf(10, 5, 0, -10, -20)
    private val pLatency = doubleArrayOf(10.0, 20.0, 50.0, 100.0, 500.0) to intArrayOf(20, 10, 5, 0, -10, -20)
    private val pJitter = doubleArrayOf(10.0, 20.0, 100.0, 500.0) to intArrayOf(10, 5, 0, -10, -20)
    private val pBw = doubleArrayOf(1e6, 1e7, 5e7, 1e8) to intArrayOf(0, 5, 10, 20, 30)

    /** رده‌ها: کلید هر کاربرد (streaming، gaming، rtc) ← ۰ تا ۴. عددهای ناموجود: آن کاربرد حساب نمی‌شود (پکت‌لاس ناموجود = ۰ امتیاز). */
    fun scores(latencyMs: Double?, jitterMs: Double?, lossPct: Double?, downMbps: Double?, loadedDownMs: Double?, loadedUpMs: Double?): Map<String, Int> {
        val pts = HashMap<String, Int>()
        pts["packetLoss"] = lossPct?.let { scale(pLoss.first, pLoss.second, it / 100.0) } ?: 0
        latencyMs?.let { pts["latency"] = scale(pLatency.first, pLatency.second, it) }
        jitterMs?.let { pts["jitter"] = scale(pJitter.first, pJitter.second, it) }
        downMbps?.let { pts["download"] = scale(pBw.first, pBw.second, it * 1e6) }
        val loaded = listOfNotNull(loadedDownMs, loadedUpMs).maxOrNull()
        if (latencyMs != null && loaded != null) pts["loadedLatencyIncrease"] = scale(pLatency.first, pLatency.second, loaded - latencyMs)
        val defs = listOf(
            "streaming" to (listOf("latency", "packetLoss", "download", "loadedLatencyIncrease") to doubleArrayOf(15.0, 20.0, 40.0, 60.0)),
            "gaming" to (listOf("latency", "packetLoss", "loadedLatencyIncrease") to doubleArrayOf(5.0, 15.0, 25.0, 30.0)),
            "rtc" to (listOf("latency", "jitter", "packetLoss", "loadedLatencyIncrease") to doubleArrayOf(5.0, 15.0, 25.0, 40.0)),
        )
        val out = LinkedHashMap<String, Int>()
        for ((k, d) in defs) {
            val (input, th) = d
            if (!input.all { pts.containsKey(it) }) continue
            val sum = maxOf(0, input.sumOf { pts[it] ?: 0 })
            out[k] = scale(th, intArrayOf(0, 1, 2, 3, 4), sum.toDouble())
        }
        return out
    }

    /**
     * رتبه‌ی «بافربلوت» از بیشترین افزایش پینگ زیر بار (میلی‌ثانیه): وقتی دانلود یا آپلود هست پینگ چقدر بالا می‌رود.
     * A+ زیر ۵، A زیر ۳۰، B زیر ۶۰، C زیر ۲۰۰، D زیر ۴۰۰، F بیشتر.
     */
    fun bloatGrade(increaseMs: Double): String = when {
        increaseMs < 5 -> "A+"
        increaseMs < 30 -> "A"
        increaseMs < 60 -> "B"
        increaseMs < 200 -> "C"
        increaseMs < 400 -> "D"
        else -> "F"
    }
}

/**
 * پینگ، جیتر و پکت‌لاس روی UDP (بیشتر پروتکل‌های کانکی و بازی‌ها روی UDP اند؛ پینگ HTTP روی TCP این‌ها را نمی‌سنجد).
 * هر بسته فقط یک بار فرستاده می‌شود و اگر تا waitMs جواب نیامد گم‌شده حساب می‌شود.
 */
object UdpSeries {
    /** make: یک بسته‌ی تازه و تابعی که جواب همان بسته را می‌شناسد (جواب دیر بسته‌ی قبلی قاطی نمی‌شود) */
    fun run(
        s: java.net.DatagramSocket, to: java.net.InetSocketAddress, count: Int, gapMs: Long, waitMs: Int = 1000,
        make: () -> Pair<ByteArray, (ByteArray) -> Boolean>,
    ): List<Long?> {
        val out = ArrayList<Long?>(count)
        val buf = ByteArray(2048)
        repeat(count) { i ->
            val (pkt, ok) = make()
            val t0 = System.nanoTime()
            s.send(java.net.DatagramPacket(pkt, pkt.size, to))
            var got: Long? = null
            val end = t0 + waitMs * 1_000_000L
            while (got == null) {
                val left = ((end - System.nanoTime()) / 1_000_000L).toInt()
                if (left <= 0) break
                s.soTimeout = left
                val dp = java.net.DatagramPacket(buf, buf.size)
                try { s.receive(dp) } catch (e: java.net.SocketTimeoutException) { break }
                // زمان رسیدن همان لحظه‌ی دریافت است، نه بعد از بررسی جواب: باز کردن جواب دست‌دادن WireGuard (دو X25519) روی گوشی
                // صدها میلی‌ثانیه طول می‌کشید و پینگ و لرزش WireGuard/AmneziaWG را غلط (۳۰۰ تا ۸۰۰ms) نشان می‌داد (۱.۴۲.۱)
                val at = System.nanoTime()
                if (ok(buf.copyOf(dp.length))) got = ((at - t0) / 1_000_000L).coerceAtLeast(1)
            }
            out.add(got)
            if (i < count - 1) Thread.sleep(gapMs)
        }
        return out
    }

    /** پینگ (میانه)، جیتر (میانگین فرق نمونه‌های پشت‌سرهم) و پکت‌لاس (درصد)؛ null اگر هیچ جوابی نیامد */
    fun summary(r: List<Long?>): Triple<Long, Long, Int>? {
        val ok = r.filterNotNull()
        if (ok.isEmpty()) return null
        val ping = ok.sorted()[ok.size / 2]
        val jitter = if (ok.size < 2) 0L else ok.zipWithNext { a, b -> abs(a - b) }.average().toLong()
        val loss = (r.count { it == null } * 100 + r.size / 2) / r.size
        return Triple(ping, jitter, loss)
    }

    /** سؤال DNS (رکورد A برای one.one.one.one) با شناسه‌ی تصادفی و تابعی که فقط جواب همین سؤال را می‌شناسد */
    fun dnsQuery(): Pair<ByteArray, (ByteArray) -> Boolean> {
        val id = (0..0xffff).random()
        val q = java.io.ByteArrayOutputStream()
        q.write(id shr 8); q.write(id and 0xff)
        q.write(byteArrayOf(0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in listOf("one", "one", "one", "one")) { q.write(label.length); q.write(label.toByteArray()) }
        q.write(byteArrayOf(0, 0, 1, 0, 1))
        return q.toByteArray() to { p: ByteArray ->
            p.size >= 12 && ((p[0].toInt() and 0xff) shl 8 or (p[1].toInt() and 0xff)) == id && (p[2].toInt() and 0x80) != 0
        }
    }

    /** پکت‌لاس با یک رقم اعشار (۳.۹٪)؛ null اگر نمونه‌ای نیست */
    fun lossPct(r: List<Long?>): Double? = if (r.isEmpty()) null else r.count { it == null } * 100.0 / r.size

    /**
     * سری سؤال DNS روی UDP به server (پیش‌فرض 1.1.1.1). بی net: وقتی VPN وصل است از داخل تونل می‌رود؛
     * با net: روی همان شبکه‌ی واقعی گوشی (بیرون از VPN).
     */
    fun dns(count: Int, gapMs: Long, net: android.net.Network? = null, server: String = "1.1.1.1", waitMs: Int = 1000): List<Long?>? = runCatching {
        java.net.DatagramSocket().use { s ->
            if (net != null) runCatching { net.bindSocket(s) }
            run(s, java.net.InetSocketAddress(server, 53), count, gapMs, waitMs) { dnsQuery() }
        }
    }.getOrNull()
}
