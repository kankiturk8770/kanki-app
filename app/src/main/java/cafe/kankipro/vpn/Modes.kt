package cafe.kankipro.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/*
 * حالت‌های اتصال
 * --------------
 * کاربر به‌جای پروتکل، «حالت» انتخاب می‌کند و برنامه پروتکل مناسب را پیدا می‌کند:
 *   خودکار     تانل (Amnezia از سرور ایران) ← مستقیم (Amnezia Direct) ← فیلتر شدید (Xray)
 *   گیم         کمترین پینگ: WireGuard / Amnezia؛ گزینه‌ی «فقط بازی‌ها از VPN»
 *   وب و استریم سرعت بالا: Hysteria2، بعد Xray
 *   فیلتر شدید  سخت‌ترین برای مسدود شدن: Xray (REALITY / CDN)، بعد Hysteria2
 *   DNS         رفع تحریم بدون VPN (DNS کانکی)
 * برای هر اپراتور (ایرانسل، همراه اول، وای‌فای …) آخرین چیزی که در هر حالت وصل شد یادش می‌ماند
 * و دفعه‌ی بعد اول همان امتحان می‌شود.
 */
enum class KMode(val key: String, val title: String, val sub: String) {
    AUTO("auto", "خودکار", "بهترین راه را خودم پیدا می‌کنم"),
    GAME("game", "گیم", "کمترین پینگ برای بازی"),
    WEB("web", "وب و استریم", "سرعت بالا برای فیلم و دانلود"),
    STRICT("strict", "فیلتر شدید", "وقتی بقیه وصل نمی‌شوند"),
    DNS("dns", "رفع تحریم", "DNS کانکی · بدون VPN");

    companion object {
        fun of(k: String?) = entries.firstOrNull { it.key == k } ?: AUTO
    }
}

object Modes {
    /** پروتکل‌های هر حالت، به ترتیب اولویت */
    fun protos(m: KMode): List<String> = when (m) {
        KMode.AUTO -> listOf("amneziawg", "amneziawg-direct", "xray", "hysteria2", "wireguard")
        KMode.GAME -> listOf("wireguard", "amneziawg", "amneziawg-direct")
        KMode.WEB -> listOf("hysteria2", "xray", "amneziawg", "amneziawg-direct")
        KMode.STRICT -> listOf("xray", "hysteria2", "amneziawg-direct")
        KMode.DNS -> emptyList()
    }

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
        "com.tencent.ig", "com.pubg.", "com.activision.callofduty", "com.supercell.", "com.miHoYo.", "com.HoYoverse.", "com.garena.",
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
