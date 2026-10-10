package cafe.kankipro.vpn

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import org.amnezia.awg.backend.AbstractBackend
import java.util.concurrent.atomic.AtomicBoolean

/**
 * کلاس برنامه: قبل از هر صفحه یا سرویسی اجرا می‌شود.
 * این‌جا به موتور امنزیا می‌گوییم وقتی اندروید خودش VPN را روشن کرد («همیشه‌روشن») چه کند.
 */
class KankiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AbstractBackend.setAlwaysOnCallback { AlwaysOn.start(this) }
    }
}

/**
 * «همیشه‌روشن» اندروید: وقتی خود سیستم سرویس VPN را بالا می‌آورد (مثلاً بعد از روشن شدن گوشی)،
 * با آخرین کانفیگ وصل‌شده دوباره وصل می‌شویم. بدون این، با کیل‌سوییچ روشن اینترنت کاربر کامل قطع می‌ماند.
 */
object AlwaysOn {
    private val busy = AtomicBoolean(false)

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!busy.compareAndSet(false, true)) return
        // اتصال روی رشته‌ی اصلی ممنوع است (Hysteria2 روی رشته‌ی اصلی خطا می‌دهد)
        Thread({
            try {
                val cfg = LastConfig.cfg(app)
                if (cfg != null && !VpnEngine.isUp(app)) {
                    runCatching {
                        VpnEngine.up(app, cfg)
                        KankiNotif.show(app, LastConfig.title(app), tr("متصل"))
                    }
                    runCatching { TileService.requestListeningState(app, ComponentName(app, KankiTileService::class.java)) }
                }
            } finally {
                busy.set(false)
            }
        }, "kanki-always-on").start()
    }
}
