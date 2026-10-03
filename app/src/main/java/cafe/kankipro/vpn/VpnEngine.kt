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

    /** برای پارامترهای اضافه‌ی موتور (مثل TunnelActionHandler) یک پیاده‌سازی خالی می‌سازد */
    private fun stub(type: Class<*>): Any? = when {
        type.isInterface -> java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, m, a ->
            when (m.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === a?.getOrNull(0)
                "toString" -> "KankiStub"
                else -> when (m.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        }
        type == java.lang.Boolean.TYPE -> false
        type == java.lang.Integer.TYPE -> 0
        type == java.lang.Long.TYPE -> 0L
        else -> null
    }

    private fun create(ctx: Context): Backend {
        // سازنده‌ای که Context اول دارد و بیشترین پارامتر را؛ پارامترهای دیگر با پیاده‌سازی خالی پر می‌شوند
        val c = GoBackend::class.java.constructors
            .filter { it.parameterTypes.firstOrNull() == Context::class.java }
            .maxBy { it.parameterTypes.size }
        val args = c.parameterTypes.mapIndexed { i, t -> if (i == 0) ctx else stub(t) }.toTypedArray()
        return c.newInstance(*args) as Backend
    }

    private fun backend(ctx: Context): Backend =
        backend ?: synchronized(this) {
            backend ?: create(ctx.applicationContext).also { backend = it }
        }

    fun up(ctx: Context, configText: String, resetTimer: Boolean = true) {
        if (HyEngine.isHy(configText)) {
            // Hysteria2 موتور جدا دارد؛ اول اتصال وایرگارد/امنزیا (اگر بالاست) پایین می‌آید
            runCatching { if (awgUp(ctx)) backend(ctx).setState(tunnel, Tunnel.State.DOWN, null) }
            HyEngine.up(ctx, configText)
        } else {
            HyEngine.down(ctx)
            val config = Config.parse(configText.byteInputStream())
            backend(ctx).setState(tunnel, Tunnel.State.UP, config)
        }
        if (resetTimer || connectedAt == 0L) connectedAt = System.currentTimeMillis()
    }

    /** برای اتصال‌هایی که با موتور دیگر (IKEv2) برقرار می‌شوند */
    fun markConnected() { connectedAt = System.currentTimeMillis() }

    fun down(ctx: Context) {
        runCatching { HyEngine.down(ctx) }
        runCatching { backend(ctx).setState(tunnel, Tunnel.State.DOWN, null) }
        connectedAt = 0L
    }

    private fun awgUp(ctx: Context): Boolean =
        runCatching { backend(ctx).getState(tunnel) == Tunnel.State.UP }.getOrDefault(false)

    fun isUp(ctx: Context): Boolean = HyEngine.isUp() || awgUp(ctx)

    /** کل دریافت و ارسال این نشست به بایت */
    fun stats(ctx: Context): Pair<Long, Long>? = if (HyEngine.isUp()) HyEngine.stats() else runCatching {
        val s: Any = backend(ctx).getStatistics(tunnel)
        val rx = s.javaClass.getMethod("totalRx").invoke(s) as Long
        val tx = s.javaClass.getMethod("totalTx").invoke(s) as Long
        rx to tx
    }.getOrNull()
}
