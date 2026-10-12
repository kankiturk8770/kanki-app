package cafe.kankipro.vpn

import android.content.Context

/**
 * دسترسی اضطراری: وقتی اینترنت بین‌الملل قطع است، صفحه‌ی پنل روی دامنه‌ای که ادمین گذاشته باز می‌شود.
 * آدرس را پنل توی صفحه‌ی اشتراک می‌فرستد و برنامه همان را ذخیره می‌کند، تا بدون اینترنت هم در دسترس باشد.
 */
object Emergency {
    /** پیش‌فرض، تا وقتی برنامه هنوز آدرسی از پنل نگرفته */
    const val DEFAULT = "https://chat.kanki.pro"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("kanki", Context.MODE_PRIVATE)

    /** آدرس پایه‌ی دامنه‌ی اضطراری (مثل https://chat.kanki.pro) */
    fun base(ctx: Context): String =
        prefs(ctx).getString("emergency_url", null)?.takeIf { it.startsWith("https://") } ?: DEFAULT

    /** اگر پنل آدرس تازه داد، ذخیره می‌شود */
    fun remember(ctx: Context, url: String?) {
        if (!url.isNullOrBlank() && url.startsWith("https://")) prefs(ctx).edit().putString("emergency_url", url).apply()
    }

    fun open(ctx: Context) = openUrl(ctx, base(ctx).trimEnd('/') + "/emergency")
}
