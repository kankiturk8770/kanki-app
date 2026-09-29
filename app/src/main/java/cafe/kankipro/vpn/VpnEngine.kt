package cafe.kankipro.vpn

import android.content.Context
import org.amnezia.awg.backend.Backend
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config

/**
 * تنها جایی که با موتور امنزیا کار می‌کنیم.
 * برای سازگاری با نسخه‌های مختلف کتابخانه، ساختن موتور و خواندن آمار با بازتاب (reflection) انجام می‌شود.
 */
object VpnEngine {

    private class KankiTunnel : Tunnel {
        override fun getName(): String = "kanki"
        override fun onStateChange(newState: Tunnel.State) {}
        override fun isIpv4ResolutionPreferred(): Boolean = true
        override fun isMetered(): Boolean = false
    }

    private val tunnel = KankiTunnel()

    @Volatile
    private var backend: Backend? = null

    /** زمان شروع اتصال فعلی (میلی‌ثانیه) */
    @Volatile
    var connectedAt: Long = 0L
        private set

    private fun create(ctx: Context): Backend {
        val cls = GoBackend::class.java
        // حالت معمول: GoBackend(Context)
        runCatching { return cls.getConstructor(Context::class.java).newInstance(ctx) as Backend }
        // نسخه‌های جدیدتر ممکن است پارامتر اضافه داشته باشند
        val c = cls.constructors.first { it.parameterTypes.firstOrNull() == Context::class.java }
        val args = c.parameterTypes.mapIndexed { i, _ -> if (i == 0) ctx else null }.toTypedArray()
        return c.newInstance(*args) as Backend
    }

    private fun backend(ctx: Context): Backend =
        backend ?: synchronized(this) {
            backend ?: create(ctx.applicationContext).also { backend = it }
        }

    fun up(ctx: Context, configText: String) {
        val config = Config.parse(configText.byteInputStream())
        backend(ctx).setState(tunnel, Tunnel.State.UP, config)
        connectedAt = System.currentTimeMillis()
    }

    fun down(ctx: Context) {
        runCatching { backend(ctx).setState(tunnel, Tunnel.State.DOWN, null) }
        connectedAt = 0L
    }

    fun isUp(ctx: Context): Boolean =
        runCatching { backend(ctx).getState(tunnel) == Tunnel.State.UP }.getOrDefault(false)

    /** کل دریافت و ارسال این نشست به بایت */
    fun stats(ctx: Context): Pair<Long, Long>? = runCatching {
        val s: Any = backend(ctx).getStatistics(tunnel)
        val rx = s.javaClass.getMethod("totalRx").invoke(s) as Long
        val tx = s.javaClass.getMethod("totalTx").invoke(s) as Long
        rx to tx
    }.getOrNull()
}
