package cafe.kankipro.vpn

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** یک پیام چت اضطراری. verified: پیام ادمین با امضای درست (برای عکس بعد از دانلود معلوم می‌شود) */
data class EMsg(
    val id: Long,
    val admin: Boolean,
    val kind: String,   // text | config | image
    val body: String,
    val ts: Long,
    val sig: String,
    val img: Boolean,
    val verified: Boolean,
)

class EmergencyException(val code: Int, msg: String) : IOException(msg)

/**
 * چت اضطراری: وقتی اینترنت بین‌الملل قطع است، اپ با سرور کوچکی که ادمین روی یک سرور داخل ایران گذاشته
 * حرف می‌زند (آدرسش را پنل اصلی در صفحه‌ی اشتراک می‌دهد و این‌جا ذخیره می‌شود).
 *
 * امنیت:
 * - کاربر با هش کد اشتراکش شناخته می‌شود، خود کد هیچ‌وقت فرستاده نمی‌شود.
 * - هر پیام ادمین امضا دارد (ECDSA P-256). کلید عمومی را پنل اصلی وقتی اینترنت وصل بود داده؛
 *   پیامی که امضایش درست نباشد «تأیید نشده» نشان داده می‌شود و کانفیگش قابل کپی نیست.
 */
object Emergency {
    /** پیش‌فرض، تا وقتی برنامه هنوز آدرسی از پنل نگرفته */
    const val DEFAULT = "https://chat.kanki.pro"
    private const val ID_PREFIX = "kanki-emergency-v1:"
    private const val SIGN_PREFIX = "kanki-emergency-v1"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("kanki", Context.MODE_PRIVATE)

    fun base(ctx: Context): String =
        prefs(ctx).getString("emergency_url", null)?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: DEFAULT

    /** کلید عمومی امضای ادمین (SubjectPublicKeyInfo، base64)؛ null یعنی هنوز از پنل نگرفته‌ایم */
    fun key(ctx: Context): String? = prefs(ctx).getString("emergency_key", null)?.takeIf { it.isNotBlank() }

    /** آدرس و کلیدی که پنل در صفحه‌ی اشتراک داده را نگه می‌دارد (مقدار خالی چیزی را پاک نمی‌کند) */
    fun remember(ctx: Context, url: String?, key: String?) {
        val e = prefs(ctx).edit()
        if (!url.isNullOrBlank() && (url.startsWith("https://") || url.startsWith("http://"))) e.putString("emergency_url", url.trimEnd('/'))
        if (!key.isNullOrBlank()) e.putString("emergency_key", key.trim())
        e.apply()
    }

    /** کدی که کاربرِ واردنشده برای چت اضطراری زده است */
    fun savedCode(ctx: Context): String? = prefs(ctx).getString("emergency_code", null)
    fun saveCode(ctx: Context, code: String) = prefs(ctx).edit().putString("emergency_code", code).apply()

    fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** شناسه‌ی کاربر برای سرور اضطراری (پنل همین را حساب می‌کند) */
    fun userId(code: String): String = sha256Hex((ID_PREFIX + code).toByteArray(Charsets.UTF_8))

    /** متنی که ادمین امضا کرده (سرور دقیقاً همین را می‌سازد) */
    fun signedText(id: Long, user: String, ts: Long, kind: String, payload: String) =
        "$SIGN_PREFIX\n$id\n$user\n$ts\n$kind\n$payload"

    fun verify(pub: String?, text: String, sig: String): Boolean {
        if (pub.isNullOrBlank() || sig.isBlank()) return false
        return runCatching {
            val k = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(pub, Base64.DEFAULT)))
            val s = Signature.getInstance("SHA256withECDSA")
            s.initVerify(k)
            s.update(text.toByteArray(Charsets.UTF_8))
            s.verify(Base64.decode(sig, Base64.DEFAULT))
        }.getOrDefault(false)
    }

    private fun conn(ctx: Context, path: String, code: String): HttpURLConnection =
        (URL(base(ctx) + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 25000
            setRequestProperty("User-Agent", "KankiVPN-Android")
            setRequestProperty("x-kanki-id", userId(code))
        }

    private fun read(c: HttpURLConnection): ByteArray {
        try {
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (status !in 200..299) {
                val msg = runCatching { JSONObject(String(bytes, Charsets.UTF_8)).optString("error") }.getOrNull().orEmpty()
                throw EmergencyException(status, msg.ifBlank { "HTTP $status" })
            }
            return bytes
        } finally {
            c.disconnect()
        }
    }

    private fun post(ctx: Context, path: String, code: String, body: ByteArray, type: String): JSONObject {
        val c = conn(ctx, path, code)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", type)
        c.outputStream.use { it.write(body) }
        return JSONObject(String(read(c), Charsets.UTF_8))
    }

    /** پیام‌های بعد از after؛ (نام کاربری، پیام‌ها) */
    fun messages(ctx: Context, code: String, after: Long): Pair<String, List<EMsg>> {
        val o = JSONObject(String(read(conn(ctx, "/api/e/messages?after=$after", code)), Charsets.UTF_8))
        val user = o.optString("user")
        val arr = o.optJSONArray("messages") ?: JSONArray()
        val pub = key(ctx)
        val list = (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            val id = m.optLong("id")
            val admin = m.optBoolean("admin")
            val kind = m.optString("kind")
            val body = m.optString("body")
            val ts = m.optLong("ts")
            val sig = m.optString("sig")
            val img = m.optBoolean("img")
            // متن و کانفیگ همین‌جا بررسی می‌شود؛ عکس بعد از دانلود
            val ok = admin && !img && verify(pub, signedText(id, user, ts, kind, body), sig)
            EMsg(id, admin, kind, body, ts, sig, img, ok)
        }
        return user to list
    }

    fun send(ctx: Context, code: String, text: String) {
        post(ctx, "/api/e/send", code, JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8), "application/json")
    }

    fun sendImage(ctx: Context, code: String, jpeg: ByteArray) {
        post(ctx, "/api/e/image", code, jpeg, "image/jpeg")
    }

    fun image(ctx: Context, code: String, id: Long): ByteArray = read(conn(ctx, "/api/e/image/$id", code))

    /** امضای عکس ادمین: روی هش فایل */
    fun verifyImage(ctx: Context, m: EMsg, user: String, bytes: ByteArray): Boolean =
        m.admin && verify(key(ctx), signedText(m.id, user, m.ts, m.kind, sha256Hex(bytes)), m.sig)
}
