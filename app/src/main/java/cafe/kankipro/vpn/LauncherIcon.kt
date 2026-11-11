package cafe.kankipro.vpn

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * آیکون برنامه روی صفحه‌ی گوشی به رنگ تم انتخابی (۱.۲۹، خواسته‌ی صاحب اپ). هر تم یک activity-alias با آیکون خودش در
 * مانیفست دارد (`.Icon_<تم>`)؛ این‌جا فقط alias همان تم روشن و بقیه خاموش می‌شوند. وقتی برنامه به پس‌زمینه می‌رود صدا زده
 * می‌شود (MainActivity.onStop) تا عوض شدن آیکون وسط کار کاربر را از برنامه بیرون نیندازد.
 * بعضی لانچرها بعد از عوض شدن آیکون، میان‌بر صفحه‌ی اصلی را یک بار برمی‌دارند؛ برنامه در فهرست برنامه‌ها می‌ماند.
 */
object LauncherIcon {
    private val ALIASES = listOf("gold", "yellow", "crimson", "purple", "lime", "sapphire", "turquoise", "amethyst")

    /**
     * از ۱.۳۰ (خواسته‌ی صاحب اپ) آیکون برنامه همیشه همان لوگوی طلایی اصل است، هر تمی که انتخاب شود؛ فقط لوگوی داخل برنامه و
     * صفحه‌ی شروع رنگ تم را می‌گیرند. گوشی‌ای که آیکون تم دیگری داشت یک بار به طلایی برمی‌گردد.
     */
    fun apply(ctx: Context, @Suppress("UNUSED_PARAMETER") theme: String) {
        val pm = ctx.packageManager
        val want = "gold"
        fun cn(t: String) = ComponentName(ctx.packageName, "cafe.kankipro.vpn.Icon_$t")
        runCatching {
            // اگر همین الان درست است، کاری نکن (هر تغییر لانچر را دوباره بارگذاری می‌کند)
            if (pm.getComponentEnabledSetting(cn(want)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (want == "gold" && pm.getComponentEnabledSetting(cn("gold")) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT &&
                    ALIASES.none { it != "gold" && pm.getComponentEnabledSetting(cn(it)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED })
            ) return
            // اول تازه روشن شود، بعد بقیه خاموش (هیچ لحظه‌ای بی‌آیکون نماند)
            pm.setComponentEnabledSetting(cn(want), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            for (t in ALIASES) if (t != want) {
                pm.setComponentEnabledSetting(cn(t), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
