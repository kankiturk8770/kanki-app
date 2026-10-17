package cafe.kankipro.vpn

import java.net.URLDecoder

/*
 * اتصال KANKI MIX (پروتکل خود کانکی)
 * ------------------------------------
 * هسته‌ی Go همین پروتکل (پوشه‌ی kmix-core) با gomobile به kmix.aar تبدیل می‌شود (فایل build.yml) و داخل
 * همین پروسه اجرا می‌شود؛ برنامه‌ی جدا یا tun2socks ندارد. خودش بسته‌های IP را از کارت شبکه‌ی VPN می‌خواند:
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
 * هسته‌ی KANKI MIX (کلاس cafe.kankipro.kmix.mobile.Mobile که gomobile می‌سازد).
 * با بازتاب صدا زده می‌شود تا ساخت روی کامپیوتر بدون Go و بدون فایل kmix.aar هم خطا ندهد
 * (فقط KANKI MIX وصل نمی‌شود و پیام روشن می‌دهد).
 */
object KmixCore {
    private val cls: Class<*>? = runCatching { Class.forName("cafe.kankipro.kmix.mobile.Mobile") }.getOrNull()

    val loaded get() = cls != null

    private fun call(name: String, vararg args: Any): Any? {
        val c = cls ?: error("kanki mix: the core is missing in this build")
        val m = c.methods.first { it.name == name && it.parameterTypes.size == args.size }
        return try {
            m.invoke(null, *args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    /** تا ۱۰ ثانیه طول می‌کشد (سرور کلید را می‌سنجد)؛ fd مال هسته می‌شود و Stop آن را می‌بندد */
    fun start(fd: Int, server: String, key: String, fecData: Int, fecParity: Int, gaming: Boolean) {
        call("start", fd.toLong(), server, key, fecData.toLong(), fecParity.toLong(), gaming)
    }

    fun stop() { runCatching { call("stop") } }

    fun running(): Boolean = runCatching { call("isRunning") as Boolean }.getOrDefault(false)

    /** پینگ تا سرور (میلی‌ثانیه) از همان مسیر بسته‌های بازی؛ -1 یعنی جواب نمی‌آید */
    fun ping(): Long = runCatching { call("getPing") as Long }.getOrDefault(-1L)

    /** عوض شدن شبکه (وای‌فای ⇄ دیتا): سوکت‌ها روی شبکه‌ی جدید ساخته می‌شوند؛ بازی قطع نمی‌شود */
    fun reconnect() { runCatching { call("reconnect") } }

    fun setGaming(on: Boolean) { runCatching { call("setGaming", on) } }

    /** (دریافت، ارسال) به بایت از شروع اتصال */
    fun stats(): Pair<Long, Long>? = runCatching { (call("rxBytes") as Long) to (call("txBytes") as Long) }.getOrNull()
}
