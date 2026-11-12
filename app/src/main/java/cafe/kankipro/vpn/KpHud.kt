package cafe.kankipro.vpn

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/*
 * پنل کوچک KPING روی صفحه‌ی بازی (فقط وقتی کاربر خودش در تنظیمات KPING روشنش کند و اجازه‌ی «نمایش روی برنامه‌های دیگر» بدهد):
 * پینگ تا سرور خارج، لرزش، افت بالا و پایین (پیش از ترمیم) و بسته‌های نجات‌داده‌شده، هر ثانیه از آمار خود هسته.
 * لمس‌ناپذیر است (هیچ لمسی را از بازی نمی‌گیرد) و با قطع KPING خودش بسته می‌شود.
 */
object KpHud {
    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var wm: WindowManager? = null

    fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    /** صفحه‌ی اجازه‌ی نمایش روی برنامه‌های دیگر، برای همین برنامه */
    fun askPermission(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** روشن یا خاموش، به حالت فعلی: تنظیم روشن، KPING وصل، اجازه داده شده */
    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        main.post {
            val want = KpingCfg.prefs(app).hud && HyEngine.kping && allowed(app)
            if (want && view == null) show(app) else if (!want && view != null) hide()
        }
    }

    fun stop() = main.post { hide() }

    private fun show(ctx: Context) {
        val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val tv = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            val pad = (6 * ctx.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            background = GradientDrawable().apply {
                cornerRadius = 10 * ctx.resources.displayMetrics.density
                setColor(Color.argb(150, 10, 14, 22))
                setStroke(1, Color.argb(120, 0, 230, 160))
            }
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (8 * ctx.resources.displayMetrics.density).toInt()
            y = (8 * ctx.resources.displayMetrics.density).toInt()
        }
        if (runCatching { w.addView(tv, lp) }.isFailure) return
        view = tv
        wm = w
        tick()
    }

    private fun hide() {
        view?.let { v -> runCatching { wm?.removeView(v) } }
        view = null
        wm = null
    }

    private fun tick() {
        val v = view ?: return
        if (!HyEngine.kping) {
            hide()
            return
        }
        v.text = HyEngine.kpStat?.let { line(it) } ?: "KPING …"
        main.postDelayed({ tick() }, 1000)
    }

    /** یک خط کوتاه: پینگ کل (تا سرور خارج)، لرزش، افت بالا/پایین و نجات‌یافته‌ها */
    fun line(s: KpStat): String {
        val ping = if (s.total > 0) s.total else s.ping
        val f = java.util.Locale.US
        val up = String.format(f, "%.1f", s.upLoss)
        val dn = String.format(f, "%.1f", s.downLoss)
        val fight = if (s.fight) " ⚔" else ""
        val paths = s.paths.filter { it.alive && !it.dark }.joinToString("+") { if (it.name == "wifi") "W" else "D" }.ifEmpty { "—" }
        return "${ping.toInt()}ms ±${s.jit.toInt()} · ↑$up% ↓$dn% · ✚${s.rescued + s.fixed} · $paths$fight"
    }
}
