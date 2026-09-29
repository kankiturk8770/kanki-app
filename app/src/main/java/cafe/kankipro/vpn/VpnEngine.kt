package cafe.kankipro.vpn

import android.content.Context
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config

/**
 * تنها جایی که با موتور امنزیا کار می‌کنیم.
 * اگر نسخه‌ی کتابخانه عوض شد و خطا داد، فقط همین فایل باید اصلاح شود.
 */
object VpnEngine {

    private class KankiTunnel : Tunnel {
        override fun getName(): String = "kanki"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    private val tunnel = KankiTunnel()

    @Volatile
    private var backend: GoBackend? = null

    private fun backend(ctx: Context): GoBackend =
        backend ?: synchronized(this) {
            backend ?: GoBackend(ctx.applicationContext).also { backend = it }
        }

    /** متن کانفیگ امنزیا یا وایرگارد را می‌گیرد و وصل می‌شود (روی رشته‌ی پس‌زمینه صدا زده شود) */
    fun up(ctx: Context, configText: String) {
        val config = Config.parse(configText.byteInputStream())
        backend(ctx).setState(tunnel, Tunnel.State.UP, config)
    }

    fun down(ctx: Context) {
        runCatching { backend(ctx).setState(tunnel, Tunnel.State.DOWN, null) }
    }

    fun isUp(ctx: Context): Boolean =
        runCatching { backend(ctx).getState(tunnel) == Tunnel.State.UP }.getOrDefault(false)
}
