package cafe.kankipro.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/*
 * پیام‌های چت اضطراری روی خود گوشی و اعلان‌شان، بدون سرویس پوش گوگل (Firebase در ایران قابل اعتماد نیست)
 * -----------------------------------------------------------------------------
 * - پیام‌ها تا ۲ روز در حافظه‌ی خود برنامه می‌مانند تا با باز کردن چت فوراً دیده شوند، حتی وقتی سرور در
 *   دسترس نیست؛ پیام قدیمی‌تر از ۲ روز فقط از گوشی پاک می‌شود (روی سرور می‌ماند).
 * - هر ۱۵ دقیقه (کمترین فاصله‌ای که اندروید برای کار پس‌زمینه می‌دهد) برنامه خودش از سرور اضطراری می‌پرسد
 *   پیام تازه‌ای از ادمین هست یا نه؛ اگر بود و چت باز نیست، اعلان «پیام تازه از پشتیبانی» نشان می‌دهد.
 *   لمس اعلان، چت اضطراری را باز می‌کند.
 */
object EmergencyStore {
    /** پیام‌ها تا این مدت روی گوشی می‌مانند */
    private const val KEEP_SEC = 2 * 24 * 3600L

    /** چت روی صفحه باز است (اعلان لازم نیست) */
    @Volatile var chatOpen = false

    private fun file(ctx: Context, code: String) = File(ctx.filesDir, "emergency-" + Emergency.userId(code).take(16) + ".json")

    data class Saved(val user: String, val msgs: List<EMsg>, val readUpto: Long)

    fun load(ctx: Context, code: String): Saved = runCatching {
        val o = JSONObject(file(ctx, code).readText())
        val min = System.currentTimeMillis() / 1000 - KEEP_SEC
        val a = o.optJSONArray("m") ?: JSONArray()
        val list = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { m ->
            EMsg(m.optLong("id"), m.optBoolean("admin"), m.optString("kind"), m.optString("body"), m.optLong("ts"),
                m.optString("sig"), m.optBoolean("img"), m.optBoolean("ok"))
        }.filter { it.ts >= min }
        Saved(o.optString("user"), list, o.optLong("read"))
    }.getOrDefault(Saved("", emptyList(), 0))

    /** پیام‌های تازه را به حافظه اضافه می‌کند و قدیمی‌تر از ۲ روز را دور می‌ریزد */
    @Synchronized
    fun save(ctx: Context, code: String, user: String, fresh: List<EMsg>, readUpto: Long) {
        val cur = load(ctx, code)
        val min = System.currentTimeMillis() / 1000 - KEEP_SEC
        val all = (cur.msgs + fresh).distinctBy { it.id }.filter { it.ts >= min }.sortedBy { it.id }
        val a = JSONArray()
        all.forEach { m ->
            a.put(JSONObject().put("id", m.id).put("admin", m.admin).put("kind", m.kind).put("body", m.body).put("ts", m.ts)
                .put("sig", m.sig).put("img", m.img).put("ok", m.verified))
        }
        runCatching {
            file(ctx, code).writeText(JSONObject().put("user", user.ifBlank { cur.user }).put("read", maxOf(readUpto, cur.readUpto)).put("m", a).toString())
        }
    }

    /** آخرین شماره‌ی پیامی که روی گوشی هست (پیام‌های بعد از آن از سرور گرفته می‌شود) */
    fun lastId(ctx: Context, code: String): Long = load(ctx, code).msgs.maxOfOrNull { it.id } ?: 0L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("kanki", Context.MODE_PRIVATE)
    /** آخرین پیام ادمین که برایش اعلان آمده یا کاربر دیده */
    fun notified(ctx: Context) = prefs(ctx).getLong("emer_notified", 0L)
    fun setNotified(ctx: Context, id: Long) { if (id > notified(ctx)) prefs(ctx).edit().putLong("emer_notified", id).apply() }

    // ------------------------------------------------------------ اعلان
    private const val CHANNEL = "kanki_support"
    private const val NOTIF_ID = 4410
    const val EXTRA_OPEN = "open_emergency"

    private fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("پیام‌های پشتیبانی"), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = tr("پیام‌های ادمین در چت اضطراری")
                })
            }
        }
    }

    fun notify(ctx: Context, msgs: List<EMsg>) {
        if (msgs.isEmpty()) return
        channel(ctx)
        val open = Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(ctx, 44, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val last = msgs.last()
        val text = when {
            last.img -> tr("یک عکس فرستاد")
            last.kind == "config" -> tr("یک کانفیگ تازه فرستاد")
            else -> last.body.take(200)
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(if (msgs.size > 1) trf("%d پیام تازه از پشتیبانی کانکی", msgs.size) else tr("پیام تازه از پشتیبانی کانکی"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) }
    }

    fun clearNotification(ctx: Context) { runCatching { NotificationManagerCompat.from(ctx).cancel(NOTIF_ID) } }

    // ------------------------------------------------------------ بررسی پس‌زمینه
    /** با شروع برنامه یک بار صدا زده می‌شود؛ تکراری ثبت نمی‌شود */
    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<EmergencyWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        runCatching { WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("kanki-emergency", ExistingPeriodicWorkPolicy.KEEP, req) }
    }

    /** پیام‌های تازه را از سرور می‌گیرد و ذخیره می‌کند؛ پیام‌های تازه‌ی ادمین (برای اعلان) برمی‌گردد */
    fun check(ctx: Context): List<EMsg> {
        val code = AppState.from(ctx).code ?: Emergency.savedCode(ctx) ?: return emptyList()
        if (Emergency.base(ctx) == null) return emptyList()
        val page = Emergency.page(ctx, code, lastId(ctx, code))
        save(ctx, code, page.user, page.msgs, page.readUpto)
        return page.msgs.filter { it.admin && it.id > notified(ctx) }
    }
}

class EmergencyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            val fresh = EmergencyStore.check(applicationContext)
            if (fresh.isNotEmpty() && !EmergencyStore.chatOpen) {
                EmergencyStore.notify(applicationContext, fresh)
                EmergencyStore.setNotified(applicationContext, fresh.maxOf { it.id })
            }
        }
        // خطای شبکه یعنی سرور در دسترس نبود؛ دفعه‌ی بعد دوباره (کار دوره‌ای خودش تکرار می‌شود)
        Result.success()
    }
}
