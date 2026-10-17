package cafe.kankipro.vpn

import java.net.URLDecoder

/*
 * اتصال KANKI MIX (پروتکل خود کانکی)
 * ------------------------------------
 * هسته‌ی Go همین پروتکل (پوشه‌ی kmix-core) برنامه‌ی جدای libkmix.so است (فایل build.yml) و tun2socks ندارد:
 * کارت شبکه‌ی VPN را می‌گیرد و خودش بسته‌های IP را می‌خواند:
 *   UDP بازی ← مسیر سریع دیتاگرام (بدون صف و ارسال مجدد؛ در حالت گیمینگ هر بسته دو بار)
 *   TCP     ← KCP + FEC
 * پنل لینک kanki://کلید@سرور:پورت?fec=10-3&ports=4001-4010&gaming=1#اسم را در اشتراک می‌گذارد؛
 * کلید مال همین کاربر است و کاربر آن را نمی‌بیند.
 */

/** لینک kanki:// */
data class KmixUri(
    val key: String,
    val host: String,
    val port: Int,
    /** پورت‌های اضافه‌ی سرور (همه به همان سرور می‌رسند)؛ هر اتصال یکی را تصادفی برمی‌دارد */
    val ports: IntRange?,
    val fecData: Int,
    val fecParity: Int,
    /** پیشنهاد پنل برای حالت گیمینگ (ارسال دوتایی) */
    val gaming: Boolean,
    val name: String,
) {
    /** پورت این اتصال: پورت اصلی یا یکی از پورت‌های اضافه */
    fun pickPort(): Int = ports?.let { r -> (r.toList() + port).random() } ?: port

    companion object {
        private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

        fun isKmix(text: String) = text.contains("kanki://")

        fun parse(text: String): KmixUri {
            val raw = Regex("kanki://\\S+").find(text.trim())?.value ?: error("kanki mix: no link")
            val u = java.net.URI(raw)
            val key = u.rawUserInfo ?: error("kanki mix: no key")
            require(HEX64.matches(key)) { "kanki mix: bad key" }
            val host = u.host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotBlank() } ?: error("kanki mix: no server address")
            require(u.port in 1..65535) { "kanki mix: bad port" }
            val q = (u.rawQuery ?: "").split("&").filter { it.contains("=") }.associate {
                val p = it.split("=", limit = 2)
                p[0] to URLDecoder.decode(p[1], "UTF-8")
            }
            val fec = q["fec"]?.split("-")?.mapNotNull { it.trim().toIntOrNull() }
            val ports = q["ports"]?.split("-")?.mapNotNull { it.trim().toIntOrNull() }
                ?.takeIf { it.size == 2 && it[0] in 1..65535 && it[1] in it[0]..65535 && it[1] - it[0] < 1000 }
                ?.let { it[0]..it[1] }
            return KmixUri(
                key = key.lowercase(),
                host = host,
                port = u.port,
                ports = ports,
                fecData = fec?.getOrNull(0)?.takeIf { it in 1..64 } ?: 10,
                fecParity = fec?.getOrNull(1)?.takeIf { it in 0..64 } ?: 3,
                gaming = q["gaming"] == "1",
                name = u.rawFragment?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty(),
            )
        }

        /**
         * متنی که برنامه برای KANKI MIX نگه می‌دارد، شبیه کانفیگ وایرگارد ساخته می‌شود تا DNS،
         * برنامه‌های مستثنی و «فقط بازی‌ها» با همان توابع قبلی روی آن اعمال شوند.
         */
        fun wrap(uri: String): String {
            val u = uri.trim()
            val k = parse(u)
            return "[Interface]\n[KankiMix]\nEndpoint = ${k.host}:${k.port}\nURI = $u\n"
        }
    }
}

/** «حالت گیمینگ» (ارسال دوتایی) برای این اتصال؛ اگر نباشد، پیشنهاد خود لینک */
fun applyKmixGaming(cfg: String, on: Boolean): String {
    if (!KmixUri.isKmix(cfg)) return cfg
    val lines = cfg.lines().filterNot { it.trim().startsWith("Gaming", ignoreCase = true) }.toMutableList()
    val i = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
    if (i >= 0) lines.add(i + 1, "Gaming = " + if (on) "1" else "0")
    return lines.joinToString("\n")
}

/**
 * هسته‌ی KANKI MIX: برنامه‌ی جدای libkmix.so (از kmix-core/cmd/kmix، ساخته‌شده در build.yml)، مثل هسته‌های
 * Hysteria2 و Xray در پروسه‌ی خودش؛ پس رانتایم Go آن هیچ‌وقت کنار موتور AmneziaWG در پروسه‌ی برنامه نیست.
 *
 * کارت شبکه‌ی VPN از راه یک سوکت یونیکس انتزاعی (اسم تصادفی، فقط برای همین برنامه) با SCM_RIGHTS به آن
 * داده می‌شود و فرمان‌ها (آمار، گیمینگ، اتصال دوباره، توقف) خط‌به‌خط روی همان سوکت می‌روند.
 * اگر برنامه بسته شود یا بمیرد، سوکت بسته می‌شود و هسته هم خودش خارج می‌شود (یتیم نمی‌ماند).
 */
object KmixCore {
    @Volatile private var proc: Process? = null
    @Volatile private var sock: android.net.LocalSocket? = null
    @Volatile private var reader: java.io.BufferedReader? = null
    @Volatile private var stopping = false
    private val lock = Any()
    private val log = StringBuilder()

    fun binary(ctx: android.content.Context) = java.io.File(ctx.applicationInfo.nativeLibraryDir, "libkmix.so")

    private fun lastLog(): String = synchronized(log) {
        log.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(3).joinToString(" | ").take(300)
    }

    /**
     * هسته را اجرا می‌کند و کارت شبکه را به آن می‌دهد؛ تا وقتی سرور کلید را بسنجد صبر می‌کند (حداکثر ~۲۲ ثانیه).
     * onDied: اگر هسته بعداً خودش (بدون stop) از بین برود.
     */
    fun start(
        bin: java.io.File, tun: android.os.ParcelFileDescriptor, server: String, key: String,
        fecData: Int, fecParity: Int, gaming: Boolean, onDied: (String) -> Unit,
    ) {
        stop()
        stopping = false
        synchronized(log) { log.setLength(0) }
        val name = "kanki-kmix-" + java.util.UUID.randomUUID().toString().replace("-", "")
        val srv = android.net.LocalServerSocket(name)
        try {
            val p = ProcessBuilder(bin.path, "-ctl", name).redirectErrorStream(true).start()
            proc = p
            Thread({
                runCatching {
                    p.inputStream.bufferedReader().forEachLine { l ->
                        synchronized(log) { log.append(l).append('\n'); if (log.length > 4000) log.delete(0, log.length - 3000) }
                    }
                }
            }, "kanki-kmix-log").start()
            // accept() منتظر نمی‌ماند اگر هسته اصلاً بالا نیاید: بعد از ۸ ثانیه سوکت بسته می‌شود
            val watchdog = Thread({ runCatching { Thread.sleep(8000); srv.close() } }, "kanki-kmix-wd").apply { start() }
            val s = try { srv.accept() } catch (e: java.io.IOException) { null } finally { watchdog.interrupt() }
                ?: error("kanki mix: the core did not start. " + lastLog())
            // فقط خود همین برنامه (هسته‌ای که خودش اجرا کرده) کارت شبکه را می‌گیرد
            if (s.peerCredentials.uid != android.os.Process.myUid()) { s.close(); error("kanki mix: wrong peer") }
            val msg = org.json.JSONObject()
                .put("server", server).put("key", key)
                .put("fec_data", fecData).put("fec_parity", fecParity).put("gaming", gaming)
                .toString() + "\n"
            s.setFileDescriptorsForSend(arrayOf(tun.fileDescriptor))
            s.outputStream.write(msg.toByteArray())
            s.outputStream.flush()
            s.setFileDescriptorsForSend(null)
            s.soTimeout = 14_000   // سرور تا ۱۰ ثانیه برای سنجش کلید فرصت دارد؛ کل اتصال زیر ۲۵ ثانیه‌ی HyEngine.up می‌ماند
            val r = s.inputStream.bufferedReader()
            val ans = runCatching { r.readLine() }.getOrNull() ?: run { s.close(); error("kanki mix: " + lastLog().ifBlank { "the core exited" }) }
            if (ans != "ok") { s.close(); error("kanki mix: " + ans.removePrefix("err ").trim()) }
            s.soTimeout = 4_000
            synchronized(lock) { sock = s; reader = r }
            lastStats = null
            Thread({
                runCatching { p.waitFor() }
                if (!stopping && proc === p) onDied("kanki mix core exited. " + lastLog())
            }, "kanki-kmix-mon").start()
            // آمار هر ثانیه در پس‌زمینه خوانده می‌شود؛ صفحه و نوتیفیکیشن فقط آخرین عدد را می‌خوانند (بدون انتظار)
            Thread({
                while (!stopping && proc === p) {
                    poll()
                    runCatching { Thread.sleep(1000) }.onFailure { return@Thread }
                }
            }, "kanki-kmix-stats").start()
        } catch (t: Throwable) {
            stop()
            throw t
        } finally {
            runCatching { srv.close() }
        }
    }

    /** یک فرمان و جوابش؛ null اگر هسته جواب ندهد */
    private fun cmd(c: String): String? = synchronized(lock) {
        val s = sock ?: return null
        val r = reader ?: return null
        runCatching {
            s.outputStream.write((c + "\n").toByteArray())
            s.outputStream.flush()
            r.readLine()
        }.getOrNull()
    }

    fun stop() {
        stopping = true
        val p = proc
        runCatching { cmd("stop") }
        synchronized(lock) {
            runCatching { sock?.close() }
            sock = null
            reader = null
        }
        proc = null
        if (p != null) {
            runCatching { if (!p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroy() }
        }
    }

    /** stop در پس‌زمینه (برای رشته‌ی اصلی)؛ اگر تا آن موقع هسته‌ی تازه‌ای اجرا شده باشد، به آن دست نمی‌زند */
    fun stopAsync() {
        val p = proc ?: return
        stopping = true
        Thread({ if (proc === p) stop() }, "kanki-kmix-stop").start()
    }

    fun running(): Boolean = proc?.isAlive == true && sock != null

    /** آخرین (دریافت، ارسال، پینگ) که رشته‌ی پس‌زمینه خوانده */
    @Volatile private var lastStats: Triple<Long, Long, Long>? = null

    private fun poll() {
        val f = cmd("stats")?.split(" ")?.takeIf { it.size == 4 && it[0] == "stats" } ?: return
        lastStats = Triple(f[1].toLongOrNull() ?: 0L, f[2].toLongOrNull() ?: 0L, f[3].toLongOrNull() ?: -1L)
    }

    /** پینگ تا سرور (میلی‌ثانیه) از همان مسیر بسته‌های بازی؛ -1 یعنی جواب نمی‌آید */
    fun ping(): Long = lastStats?.third ?: -1L

    /** عوض شدن شبکه (وای‌فای ⇄ دیتا): سوکت‌ها روی شبکه‌ی جدید ساخته می‌شوند؛ بازی قطع نمی‌شود */
    fun reconnect() { Thread({ cmd("reconnect") }, "kanki-kmix-rc").start() }

    fun setGaming(on: Boolean) { Thread({ cmd("gaming " + if (on) "1" else "0") }, "kanki-kmix-gm").start() }

    /** (دریافت، ارسال) به بایت از شروع اتصال؛ هیچ‌وقت منتظر نمی‌ماند (روی رشته‌ی اصلی هم امن است) */
    fun stats(): Pair<Long, Long>? = lastStats?.let { it.first to it.second } ?: if (running()) 0L to 0L else null
}
