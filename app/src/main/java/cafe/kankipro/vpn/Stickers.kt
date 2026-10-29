package cafe.kankipro.vpn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import java.io.ByteArrayOutputStream

/*
 * استیکرهای کانکی در چت اضطراری: لوگوی کانکی با یک جمله‌ی کوتاه، روی کارت تیره با حاشیه‌ی رنگی.
 * همین‌جا کشیده می‌شوند و مثل یک عکس فرستاده می‌شوند، پس پنل ادمین هم بی‌هیچ تغییری نشانشان می‌دهد.
 */
object Stickers {
    data class Sticker(val key: String, val text: String, val color: Int)

    /** اندازه‌ی استیکر فرستاده‌شده (پیکسل، مربع)؛ چت هر عکس مربعی تا ۵۱۲ (استیکرهای قدیمی) را استیکر کوچک نشان می‌دهد */
    const val SIZE = 320

    val all = listOf(
        Sticker("ok", "وصل شد، دمت گرم! 🚀", 0xFF3DD68C.toInt()),
        Sticker("fail", "وصل نمی‌شه 😕", 0xFFFF5A5A.toInt()),
        Sticker("thanks", "مرسی پشتیبانی 💛", 0xFFE8B84A.toInt()),
        Sticker("help", "کمک لازم دارم 🆘", 0xFFFF9F43.toInt()),
        Sticker("slow", "سرعتم کمه 🐢", 0xFF4FC3F7.toInt()),
        Sticker("game", "پینگ بازی بالاست 🎮", 0xFFB388FF.toInt()),
    )

    private fun font(ctx: Context): Typeface = runCatching {
        val id = ctx.resources.getIdentifier("vazir_black", "font", ctx.packageName)
        if (id != 0) ResourcesCompat.getFont(ctx, id) else null
    }.getOrNull() ?: Typeface.DEFAULT_BOLD

    /** تصویر استیکر (size×size؛ طرح روی ۵۱۲ کشیده و به اندازه برده می‌شود) */
    fun bitmap(ctx: Context, s: Sticker, size: Int = 512): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val f = size / 512f
        c.drawColor(0xFF0B0A10.toInt())
        val card = RectF(16 * f, 16 * f, size - 16 * f, size - 16 * f)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, size.toFloat(), 0xFF1B1824.toInt(), 0xFF0E0D14.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(card, 64 * f, 64 * f, bg)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 10 * f; color = s.color }
        c.drawRoundRect(card, 64 * f, 64 * f, ring)
        // لوگو
        ContextCompat.getDrawable(ctx, R.drawable.ic_launcher)?.toBitmap((230 * f).toInt(), (230 * f).toInt())?.let {
            c.drawBitmap(it, (size - it.width) / 2f, 62 * f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        // جمله (اگر جا نشد کوچک‌تر)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font(ctx); color = s.color; textAlign = Paint.Align.CENTER; textSize = 50 * f }
        while (p.measureText(s.text) > size - 70 * f && p.textSize > 24 * f) p.textSize -= 2 * f
        c.drawText(s.text, size / 2f, 410 * f, p)
        val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; color = 0x99FFFFFF.toInt(); textAlign = Paint.Align.CENTER; textSize = 24 * f }
        c.drawText("KANKI VPN", size / 2f, 462 * f, brand)
        return b
    }

    /** همان استیکر به شکل JPEG برای فرستادن */
    fun jpeg(ctx: Context, s: Sticker): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap(ctx, s, SIZE).compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }
}
