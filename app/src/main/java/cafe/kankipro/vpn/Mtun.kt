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

/*
 * KPING: هسته‌ی مخفی بازی (پنل ۳.۳۷ به بعد)
 * ----------------------------------------------
 * فقط برای چند تستر که ادمین در پنل انتخاب می‌کند و فقط داخل جواب رمزشده‌ی اشتراک (فیلد kping). همان هسته‌ی microtun
 * است (لینک mtun://) ولی به سرور ایران وصل می‌شود که با تانل جدای خودش (Game-FEC) به سرور خارج می‌رسد.
 * انتخاب برنامه ندارد: فقط پابجی (هر نسخه) و سرویس‌های گوگل که ورود بازی با آن‌هاست وارد VPN می‌شوند؛ بقیه‌ی گوشی
 * اینترنت معمولی خودش را دارد (addAllowedApplication).
 */
object KpingCfg {
    const val PROTO = "kping"
    const val TITLE = "KPING"

    /** همه‌ی نسخه‌های پابجی موبایل: جهانی، کره، هند، ویتنام، تایوان */
    val GAMES = setOf("com.tencent.ig", "com.pubg.krmobile", "com.pubg.imobile", "com.vng.pubgmobile", "com.rekoo.pubgm")

    /** کدام نسخه‌های پابجی روی این گوشی نصب‌اند */
    fun installed(ctx: Context): List<String> =
        GAMES.filter { p -> runCatching { ctx.packageManager.getApplicationInfo(p, 0); true }.getOrDefault(false) }

    /**
     * فقط همین برنامه‌ها وارد VPN شوند (پابجی + GAME_NEEDS)؛ هر دروازه یا استثنای دیگری که روی کانفیگ بود برداشته می‌شود.
     */
    fun onlyApps(cfg: String, games: Collection<String>): String {
        val keep = cfg.lines().filterNot { l ->
            val k = l.trim().substringBefore("=").trim()
            k.equals("IncludedApplications", true) || k.equals("ExcludedApplications", true) || k.equals("GameOnly", true) || k.equals("BlockOthers", true)
        }.toMutableList()
        val i = keep.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        val line = "IncludedApplications = " + (games + GAME_NEEDS).sorted().joinToString(", ")
        if (i >= 0) keep.add(i + 1, line) else keep.add(0, line)
        return keep.joinToString("\n")
    }
}
