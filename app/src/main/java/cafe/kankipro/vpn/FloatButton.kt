package cafe.kankipro.vpn

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * دکمه‌ی شناور کانکی (مثل دکمه‌ی گوشه‌ی Google Play Games روی بازی‌ها)
 * -----------------------------------------------------------------------------
 * یک دکمه‌ی کوچک گرد که روی همه‌ی برنامه‌ها و بازی‌ها می‌ماند، کنار لبه‌ی صفحه (چپ، راست یا بالا کنار
 * دوربین؛ با کشیدن جابه‌جا می‌شود و به نزدیک‌ترین لبه می‌چسبد). لمسش یک پنل کوچک باز می‌کند:
 * وضعیت اتصال، «اتصال هوشمند» (همان حالت هوشمند خانه: پینگ همه و وصل به بهترین)، «قطع»، باز کردن برنامه.
 * سبز = وصل، طلایی = در حال اتصال، خاکستری = قطع.
 * با View های ساده‌ی اندروید ساخته شده (نه Compose) تا بیرون از صفحه‌ی برنامه هم سبک و بی‌دردسر باشد.
 * اجازه‌ی «نمایش روی برنامه‌های دیگر» لازم است؛ از تنظیمات برنامه روشن/خاموش می‌شود.
 */
class KankiFloatService : Service() {
    companion object {
        private const val PREF = "floatOn"
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("kanki", Context.MODE_PRIVATE)
        fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)
        /** کلید تنظیمات: روشن است و اجازه هم دارد (برای نمایش در صفحه‌ی تنظیمات) */
        val on = androidx.compose.runtime.mutableStateOf(false)
        /** صفحه‌ی خود برنامه باز است: دکمه پنهان می‌شود (فقط روی بازی‌ها و برنامه‌های دیگر) */
        @Volatile var appVisible = false
        fun enabled(ctx: Context) = prefs(ctx).getBoolean(PREF, false)

        fun setEnabled(ctx: Context, on: Boolean) {
            prefs(ctx).edit().putBoolean(PREF, on).apply()
            sync(ctx)
        }

        /** دکمه را مطابق تنظیم و اجازه نشان می‌دهد یا برمی‌دارد */
        fun sync(ctx: Context) {
            val i = Intent(ctx, KankiFloatService::class.java)
            val want = enabled(ctx) && canDraw(ctx)
            on.value = want
            if (want) runCatching { ctx.startService(i) } else ctx.stopService(i)
        }

        /** صفحه‌ی اجازه‌ی «نمایش روی برنامه‌های دیگر» برای همین برنامه */
        fun permissionIntent(ctx: Context) =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private lateinit var wm: WindowManager
    private var tab: FrameLayout? = null
    private var dot: View? = null
    private var panel: LinearLayout? = null
    private var status: TextView? = null
    private var mainBtn: TextView? = null
    private val ui = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val gold = Color.rgb(0xE8, 0xB8, 0x4A)
    private val green = Color.rgb(0x3D, 0xD6, 0x8C)
    private val grey = Color.rgb(0x8A, 0x8F, 0x9A)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!canDraw(this)) { stopSelf(); return }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addTab()
        tick()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        scope.cancel()
        closePanel()
        tab?.let { runCatching { wm.removeView(it) } }
        tab = null
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun params(w: Int, h: Int, focus: Boolean = false) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        (if (focus) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // کنار دوربین (بریدگی صفحه) هم بتواند بنشیند، مثل دکمه‌ی Play Games در بازی‌های افقی
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    // ------------------------------------------------------------ دکمه‌ی کوچک
    @SuppressLint("ClickableViewAccessibility")
    private fun addTab() {
        val size = dp(46)
        val box = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(215, 18, 16, 24))
                setStroke(dp(2), gold)
            }
            alpha = 0.88f
            elevation = dp(6).toFloat()
        }
        box.addView(TextView(this).apply {
            text = "K"; setTextColor(gold); textSize = 19f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(size, size))
        val d = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(grey); setStroke(dp(1), Color.BLACK) } }
        box.addView(d, FrameLayout.LayoutParams(dp(11), dp(11), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(3), dp(3)) })
        dot = d

        val p = params(size, size)
        val sp = getSharedPreferences("kanki", MODE_PRIVATE)
        val sw = resources.displayMetrics.widthPixels
        p.x = sp.getInt("floatX", sw - size)
        p.y = sp.getInt("floatY", dp(140))

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        box.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false; v.alpha = 1f }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > dp(6) || abs(dy) > dp(6))) { moved = true; closePanel() }
                    if (moved) { p.x = startX + dx.toInt(); p.y = startY + dy.toInt(); runCatching { wm.updateViewLayout(v, p) } }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 0.88f
                    if (!moved) { if (panel == null) openPanel() else closePanel() }
                    else snap(v, p)
                }
            }
            true
        }
        wm.addView(box, p)
        tab = box
    }

    /** به نزدیک‌ترین لبه می‌چسبد: چپ، راست یا بالا */
    private fun snap(v: View, p: WindowManager.LayoutParams) {
        val m = resources.displayMetrics
        val w = m.widthPixels; val h = m.heightPixels; val s = v.width
        val toLeft = p.x; val toRight = w - (p.x + s); val toTop = p.y
        when (minOf(toLeft, toRight, toTop)) {
            toTop -> p.y = 0
            toLeft -> p.x = 0
            else -> p.x = w - s
        }
        p.x = p.x.coerceIn(0, w - s); p.y = p.y.coerceIn(0, h - s)
        runCatching { wm.updateViewLayout(v, p) }
        getSharedPreferences("kanki", MODE_PRIVATE).edit().putInt("floatX", p.x).putInt("floatY", p.y).apply()
    }

    // ------------------------------------------------------------ پنل کوچک
    @SuppressLint("ClickableViewAccessibility")
    private fun openPanel() {
        if (panel != null) return
        val t = tab ?: return
        val tp = t.layoutParams as WindowManager.LayoutParams
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(240, 16, 15, 22))
                setStroke(dp(1), Color.argb(150, 0xE8, 0xB8, 0x4A))
            }
            elevation = dp(10).toFloat()
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        box.addView(TextView(this).apply { text = "Kanki VPN"; setTextColor(gold); textSize = 14f; typeface = Typeface.DEFAULT_BOLD })
        val st = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12.5f; setPadding(0, dp(4), 0, dp(10)); maxLines = 2 }
        box.addView(st)
        status = st
        val main = button("", gold, Color.rgb(26, 18, 6)) { mainAction() }
        box.addView(main)
        mainBtn = main
        box.addView(button(tr("باز کردن برنامه"), Color.argb(40, 255, 255, 255), Color.WHITE) { openApp(); closePanel() }, lp(top = 8))
        box.addView(button(tr("پنهان کردن این دکمه"), Color.TRANSPARENT, grey) {
            setEnabled(this, false)
        }, lp(top = 4))
        box.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) closePanel(); false }

        val m = resources.displayMetrics
        val pw = dp(230)
        val p = params(pw, WindowManager.LayoutParams.WRAP_CONTENT)
        val tabW = t.width.takeIf { it > 0 } ?: dp(46)
        // کنار دکمه، به سمت وسط صفحه
        p.x = if (tp.x + tabW / 2 > m.widthPixels / 2) (tp.x - pw - dp(8)).coerceAtLeast(0) else (tp.x + tabW + dp(8)).coerceAtMost(m.widthPixels - pw)
        p.y = (if (tp.y < dp(20)) tp.y + tabW + dp(8) else tp.y - dp(10)).coerceIn(0, (m.heightPixels - dp(200)).coerceAtLeast(0))
        wm.addView(box, p)
        panel = box
        refresh()
    }

    private fun closePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null; status = null; mainBtn = null
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun button(label: String, bg: Int, fg: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(fg); textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setPadding(dp(10), dp(9), dp(10), dp(9))
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(bg) }
        setOnClickListener { onClick() }
    }

    private fun openApp() {
        runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
    }

    /** وصل: حالت هوشمند (همان دکمه‌ی هوشمند خانه)؛ وصل یا در حال اتصال: قطع */
    private fun mainAction() {
        val s = AppState.from(this)
        if (s.vstate != VState.OFF || s.smartBusy) { s.userDisconnect(); refresh(); return }
        // اجازه‌ی VPN هنوز داده نشده (یا اشتراکی نیست): خود برنامه باز می‌شود
        if (VpnService.prepare(this) != null || !s.loggedIn) { openApp(); closePanel(); return }
        scope.launch {
            if (s.account == null) runCatching { s.startupLoad() }
            if (s.account == null) { openApp(); closePanel(); return@launch }
            s.tapMode(KMode.SMART)
            refresh()
        }
    }

    private fun tick() {
        refresh()
        ui.postDelayed({ tick() }, 800)
    }

    private fun refresh() {
        tab?.let { t ->
            val v = if (appVisible) View.GONE else View.VISIBLE
            if (t.visibility != v) { t.visibility = v; if (appVisible) closePanel() }
        }
        val s = AppState.from(this)
        val busy = s.vstate == VState.CONNECTING || s.smartBusy
        val on = s.vstate == VState.ON
        (dot?.background as? GradientDrawable)?.setColor(if (on) green else if (busy) gold else grey)
        status?.text = when {
            on -> tr("متصل") + " · " + LastConfig.title(this)
            busy -> (s.tryStep ?: s.smartStatus).ifBlank { tr("در حال اتصال…") }
            else -> tr("قطع")
        }
        mainBtn?.text = when {
            on -> tr("قطع اتصال")
            busy -> tr("لغو")
            else -> "⚡ " + tr("اتصال هوشمند")
        }
    }
}
