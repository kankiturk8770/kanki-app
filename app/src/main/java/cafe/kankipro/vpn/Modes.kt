package cafe.kankipro.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/*
 * حالت‌های اتصال (چهار دکمه‌ی صفحه‌ی اول)
 * -----------------------------------------
 *   هوشمند    پینگ همه‌ی کانفیگ‌ها (Xray، AmneziaWG تانل/مستقیم، WireGuard، Hysteria2) را می‌گیرد و به
 *              کمترین پینگ وصل می‌شود؛ اگر واقعاً وصل نشد، بعدی
 *   گیمینگ     کاربر پروتکل (AmneziaWG تانل/مستقیم، WireGuard، Hysteria2) و کشور را انتخاب می‌کند؛
 *              «انتخاب هوشمند سرور» کم‌پینگ‌ترین کشور و پروتکل را برمی‌دارد
 *   ضد فیلتر   لمس اول: اول راه‌های مستقیم (اینباندهای Xray، AmneziaWG مستقیم)، اگر جواب ندادند تانل؛
 *              لمس دوباره (وقتی همین حالت انتخاب است): تب سرورها برای انتخاب دستی
 *   DNS        رفع تحریم با DNS کانکی (اول ریلی‌های ایران، بعد خروجی‌های مستقیم)
 * سرور و پروتکلی که کاربر انتخاب کرده قفل است: برنامه خودش آن را عوض نمی‌کند
 * (فقط «هوشمند» و «انتخاب هوشمند سرور» اجازه‌ی امتحان گزینه‌ی بعدی را دارند).
 */
enum class KMode(val key: String, val title: String, val sub: String) {
    SMART("smart", "هوشمند", "پینگ همه را می‌گیرد و به بهترین وصل می‌شود"),
    GAME("game", "گیمینگ", "کمترین پینگ برای بازی · پروتکل و کشور با شما"),
    FILTER("filter", "ضد فیلتر", "یک لمس: بهترین راه عبور از فیلتر · لمس دوباره: انتخاب دستی"),
    DNS("dns", "DNS", "رفع تحریم بازی‌ها و برنامه‌ها با DNS کانکی");

    companion object {
        /** کلیدهای نسخه‌های قبل (auto، web، strict) هم به حالت تازه‌ی هم‌معنا برمی‌گردند */
        fun of(k: String?) = when (k) {
            "auto", "web" -> SMART
            "strict" -> FILTER
            else -> entries.firstOrNull { it.key == k } ?: SMART
        }
    }
}

object Modes {
    /** پروتکل‌های حالت گیمینگ، به ترتیب نمایش */
    val GAME_PROTOS = listOf("amneziawg", "amneziawg-direct", "wireguard", "hysteria2")

    /** اسم شبکه‌ی فعلی برای حافظه‌ی اپراتور: «wifi» یا کد اپراتور موبایل (مثل 43235 ایرانسل) */
    fun networkKey(ctx: Context): String = runCatching {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.allNetworks?.mapNotNull { cm.getNetworkCapabilities(it) }
            ?.firstOrNull { !it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        when {
            caps == null -> "none"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            else -> {
                val tm = ctx.getSystemService(android.telephony.TelephonyManager::class.java)
                "mob-" + (tm?.networkOperator?.takeIf { it.isNotBlank() } ?: tm?.networkOperatorName ?: "x")
            }
        }
    }.getOrDefault("x")

    /** اسم قابل‌فهم اپراتور برای نمایش */
    fun networkName(ctx: Context): String {
        val k = networkKey(ctx)
        if (k == "wifi") return "Wi-Fi"
        val code = k.removePrefix("mob-")
        return when {
            code.startsWith("43235") -> "ایرانسل"
            code.startsWith("43211") -> "همراه اول"
            code.startsWith("43220") -> "رایتل"
            code.startsWith("43214") -> "تالیا"
            k == "none" || k == "x" -> "—"
            else -> runCatching { ctx.getSystemService(android.telephony.TelephonyManager::class.java)?.networkOperatorName }.getOrNull()?.ifBlank { null } ?: "موبایل"
        }
    }

    /** بسته‌های شناخته‌شده‌ی بازی (برای گوشی‌هایی که دسته‌ی «بازی» را درست نمی‌گویند) */
    private val knownGames = listOf(
        "com.tencent.ig", "com.pubg.imobile", "com.pubg.", "com.vng.pubgmobile", "com.rekoo.pubgm", "com.tencent.tmgp.",
        "com.activision.callofduty.shooter", "com.activision.callofduty", "com.garena.game.codm", "com.ea.gp.fifamobile",
        "com.supercell.", "com.miHoYo.", "com.HoYoverse.", "com.garena.",
        "com.ea.gp.", "com.ea.game.", "com.epicgames.", "com.mobile.legends", "com.roblox.", "com.dts.freefire", "com.riotgames.",
        "com.kiloo.", "com.king.", "com.rovio.", "com.gameloft.", "com.netease.", "com.levelinfinite.", "com.proximabeta.",
        "com.krafton.", "com.innersloth.", "com.mojang.", "com.playstation.", "com.valvesoftware.", "com.nianticlabs.",
    )

    /** برنامه‌های بازیِ نصب‌شده روی گوشی (فقط آن‌هایی که آیکون دارند) */
    fun gamePackages(ctx: Context): List<String> = runCatching {
        val pm = ctx.packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(launch, 0).map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
        list.filter { ai ->
            @Suppress("DEPRECATION")
            ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0 ||
                knownGames.any { ai.packageName.startsWith(it) }
        }.map { it.packageName }.filter { it != ctx.packageName }.sorted()
    }.getOrDefault(emptyList())

    /** اسم نمایشی یک برنامه */
    fun label(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)).toString()
    }.getOrDefault(pkg)
}

/** فقط این برنامه‌ها از VPN رد شوند (حالت «فقط بازی‌ها»)؛ برنامه‌های مستثنی در این حالت معنا ندارند */
fun applyIncluded(cfg: String, pkgs: List<String>): String {
    if (pkgs.isEmpty()) return cfg
    val lines = cfg.lines().filterNot {
        val t = it.trim()
        t.startsWith("ExcludedApplications", ignoreCase = true) || t.startsWith("IncludedApplications", ignoreCase = true)
    }.toMutableList()
    val i = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
    if (i >= 0) lines.add(i + 1, "IncludedApplications = " + pkgs.joinToString(", "))
    return lines.joinToString("\n")
}

/** «بقیه‌ی برنامه‌ها اینترنت نداشته باشند»؛ فقط برای Hysteria2/Xray/DNS (موتور امنزیا این کلید را نمی‌شناسد) */
fun applyBlockOthers(cfg: String): String {
    val lines = cfg.lines().filterNot { it.trim().startsWith("BlockOthers", ignoreCase = true) }.toMutableList()
    val i = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
    if (i >= 0) lines.add(i + 1, "BlockOthers = 1")
    return lines.joinToString("\n")
}
