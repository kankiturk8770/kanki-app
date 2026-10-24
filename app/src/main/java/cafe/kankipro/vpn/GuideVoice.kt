package cafe.kankipro.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.util.Locale

/**
 * صدای ربات راهنما: هر قدم آموزش یک فایل به زبان برنامه دارد (res/raw/guide_fa_NN و guide_en_NN، صدای
 * ElevenLabs). با عوض شدن قدم، بستن آموزش یا رفتن برنامه به پس‌زمینه قطع می‌شود؛ خاموش / روشن بودنش یادش می‌ماند.
 */
object GuideVoice {
    private var player: MediaPlayer? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("kanki", Context.MODE_PRIVATE)
    fun on(ctx: Context) = prefs(ctx).getBoolean("guide_voice", true)
    fun set(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("guide_voice", v).apply()
        if (!v) stop()
    }

    @SuppressLint("DiscouragedApi")
    fun play(ctx: Context, step: Int, en: Boolean) {
        stop()
        val name = String.format(Locale.US, "guide_%s_%02d", if (en) "en" else "fa", step)
        val id = ctx.resources.getIdentifier(name, "raw", ctx.packageName)
        if (id == 0) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        // پایان فایل خودش صدا را تمام می‌کند؛ پخش‌کننده تا قدم بعد یا stop نگه داشته می‌شود، چون آزاد کردنش
        // درست در لحظه‌ی تمام شدن، روی بعضی گوشی‌ها ته کلمه‌ی آخر را (که هنوز در بافر صداست) می‌برید
        player = runCatching { MediaPlayer.create(ctx, id, attrs, 0)?.apply { start() } }.getOrNull()
    }

    fun stop() {
        val p = player ?: return
        player = null
        runCatching { p.stop() }
        p.release()
    }
}
