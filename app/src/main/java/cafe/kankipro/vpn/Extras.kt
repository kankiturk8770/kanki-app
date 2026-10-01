package cafe.kankipro.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap

// ------------------------------------------------------------------ آخرین کانفیگ وصل‌شده (برای دکمه‌ی منوی کشویی)

object LastConfig {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("kanki", Context.MODE_PRIVATE)
    fun save(ctx: Context, cfg: String, title: String) {
        prefs(ctx).edit().putString("lastcfg", cfg).putString("lasttitle", title).apply()
    }
    fun cfg(ctx: Context) = prefs(ctx).getString("lastcfg", null)
    fun title(ctx: Context) = prefs(ctx).getString("lasttitle", "Kanki VPN") ?: "Kanki VPN"

    /** با خروج از حساب پاک می‌شود (کانفیگ شامل کلید خصوصی است) */
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove("lastcfg").remove("lasttitle").apply()
    }
}

// ------------------------------------------------------------------ نوتیفیکیشن ثابت

object KankiNotif {
    private const val CH = "kanki_status"
    const val ID = 7301

    private fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH, "Kanki VPN", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false); description = "وضعیت اتصال"
                }
            )
        }
    }

    fun show(ctx: Context, title: String, text: String) {
        channel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            ctx, 2, Intent(ctx, KankiActionReceiver::class.java).setAction(KankiActionReceiver.STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, tr("قطع اتصال"), stop)
            .addAction(0, tr("تغییر سرور"), open)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ID, n) }
    }

    fun hide(ctx: Context) { runCatching { NotificationManagerCompat.from(ctx).cancel(ID) } }
}

/** دکمه‌ی «قطع اتصال» داخل نوتیفیکیشن */
class KankiActionReceiver : BroadcastReceiver() {
    companion object { const val STOP = "cafe.kankipro.vpn.STOP" }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == STOP) {
            val r = goAsync()
            Thread {
                VpnEngine.down(ctx)
                KankiNotif.hide(ctx)
                TileService.requestListeningState(ctx, android.content.ComponentName(ctx, KankiTileService::class.java))
                r.finish()
            }.start()
        }
    }
}

// ------------------------------------------------------------------ دکمه‌ی منوی کشویی اندروید

class KankiTileService : TileService() {
    override fun onStartListening() { refresh() }

    override fun onClick() {
        val ctx = applicationContext
        val up = VpnEngine.isUp(ctx)
        val cfg = LastConfig.cfg(ctx)
        // اگر هنوز اجازه‌ی VPN داده نشده یا کانفیگی نیست، خود اپ باز می‌شود
        if (!up && (cfg == null || VpnService.prepare(ctx) != null)) {
            val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(ctx, 3, i, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION") startActivityAndCollapse(i)
            }
            return
        }
        qsTile?.apply { state = Tile.STATE_UNAVAILABLE; updateTile() }
        Thread {
            runCatching {
                if (up) { VpnEngine.down(ctx); KankiNotif.hide(ctx) }
                else { VpnEngine.up(ctx, cfg!!); KankiNotif.show(ctx, LastConfig.title(ctx), tr("متصل")) }
            }
            refresh()
        }.start()
    }

    private fun refresh() {
        val up = VpnEngine.isUp(applicationContext)
        qsTile?.apply {
            state = if (up) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Kanki VPN"
            if (android.os.Build.VERSION.SDK_INT >= 29) subtitle = if (up) tr("متصل") else tr("قطع")
            updateTile()
        }
    }
}

// ------------------------------------------------------------------ فهرست برنامه‌های نصب‌شده (تونل‌زنی انتخابی)

data class AppItem(val pkg: String, val label: String, val icon: ImageBitmap?)

object AppsRepo {
    @Volatile
    private var cache: List<AppItem>? = null

    fun load(ctx: Context): List<AppItem> {
        cache?.let { return it }
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(main, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != ctx.packageName }
            .map { ai ->
                val icon = runCatching { pm.getApplicationIcon(ai).toBitmap(72, 72).asImageBitmap() }.getOrNull()
                AppItem(ai.packageName, pm.getApplicationLabel(ai).toString(), icon)
            }
            .sortedBy { it.label.lowercase() }
        cache = list
        return list
    }
}
