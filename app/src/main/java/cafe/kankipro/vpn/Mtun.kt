package cafe.kankipro.vpn

import android.content.Context
import java.net.URLDecoder

/*
 * microtun: VPN سبک بازی (جای MIX+)
 * ----------------------------------------------
 * هسته‌ی libmtun.so (پوشه‌ی mtun-core) مثل KANKI MIX خود کارت VPN را می‌گیرد؛ بدون tun2socks. هر بسته ۲۸ بایت
 * سربار دارد و بسته‌های کوچک بازی (تا ۳۰۰ بایت) دو بار فرستاده می‌شوند تا گم شدن یک بسته لگ نیاورد؛ دانلود و
 * ویدیو یک بار می‌روند، پس حجم اضافه نمی‌خورد. لینک mtun://کلید@سرور:پورت?obfs=…&ip=… از اشتراک می‌آید
 * (فیلد mtun هر سرور، پنل ۳.۱۸ به بعد).
 */
object MtunCfg {
    const val PROTO = "mtun"

    fun isProto(p: String) = p == PROTO

    fun title(p: String) = "microtun"

    fun supported(link: String) = link.trim().startsWith("mtun://")

    /** متنی که برنامه نگه می‌دارد (مثل Xray، شبیه کانفیگ وایرگارد تا DNS و برنامه‌های مستثنی رویش اعمال شوند) */
    fun wrap(link: String): String {
        val (h, p) = endpoint(link)
        return "[Interface]\n[Mtun]\nEndpoint = $h:$p\nURI = ${link.trim()}\n"
    }

    fun isMtun(text: String) = text.lines().any { it.trim().equals("[Mtun]", ignoreCase = true) }

    fun query(link: String): Map<String, String> =
        link.substringAfter("?", "").substringBefore("#").split("&").filter { it.contains("=") }.associate {
            val p = it.split("=", limit = 2)
            p[0] to runCatching { URLDecoder.decode(p[1], "UTF-8") }.getOrDefault(p[1])
        }

    /** MTU کارت VPN (از لینک پنل؛ پیش‌فرض ۱۴۲۰) */
    fun mtu(link: String): Int = query(link)["mtu"]?.toIntOrNull()?.coerceIn(1280, 1500) ?: 1420

    /** (میزبان، پورت) سرور */
    fun endpoint(link: String): Pair<String, Int> {
        val hp = link.substringAfter("://").substringBefore("?").substringBefore("#").substringAfterLast("@").trimEnd('/')
        return if (hp.startsWith("[")) {
            val i = hp.indexOf(']')
            hp.substring(1, i) to (hp.substring(i + 2).toIntOrNull() ?: error("microtun: bad port"))
        } else hp.substringBeforeLast(":") to (hp.substringAfterLast(":").toIntOrNull() ?: error("microtun: bad port"))
    }

    /** همان لینک با آی‌پی به‌جای اسم سرور (هسته به همان آی‌پی وصل شود که از VPN بیرون گذاشته شده) */
    fun withHost(link: String, ip: String): String {
        val (h, p) = endpoint(link)
        val host = if (ip.contains(':')) "[$ip]" else ip
        val old = if (h.contains(':')) "[$h]:$p" else "$h:$p"
        val head = link.substringBefore("?")
        return head.substringBeforeLast(old) + "$host:$p" + link.removePrefix(head)
    }

    /**
     * اسم ثابت این نصب برای سرور: هر گوشی یک «دستگاه» جدای همان کاربر است (آدرس خودش را می‌گیرد) و وصل
     * شدن دوباره همان دستگاه حساب می‌شود. سقف دستگاه‌ها همان «تعداد اتصال» کاربر در پنل است.
     */
    fun deviceName(ctx: Context): String {
        val p = ctx.getSharedPreferences("kanki_mtun", Context.MODE_PRIVATE)
        p.getString("device", null)?.let { return it }
        val b = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val id = b.joinToString("") { "%02x".format(it) }
        p.edit().putString("device", id).apply()
        return id
    }
}
