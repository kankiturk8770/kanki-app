package cafe.kankipro.vpn

import android.content.Context
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * کانفیگ رمزشده (روی TLS): پنلی که هدر «X-Kanki-Seal: kseal1» و «X-Kanki-Device» را ببیند جواب را این‌طور می‌دهد:
 *
 *   kseal1.<base64url(nonce(12) | AES-256-GCM ciphertext+tag)>
 *
 * کلید = HKDF-SHA256(secret = کد اشتراک، salt = شناسه‌ی دستگاه، info = "kanki sealed config v1")، aad = "kseal1".
 * فقط پنل و همین گوشی هر دو را می‌دانند؛ پس کانفیگی که سر راه (CDN، سرور ایران، پراکسی) ذخیره شود خوانده نمی‌شود.
 * همان `windows/service/internal/sub/seal.go` و `devices.rs` پنل. پنل قدیمی متن ساده می‌دهد و همان قبول می‌شود.
 */
object Seal {
    const val VERSION = "kseal1"
    private const val INFO = "kanki sealed config v1"

    /** شناسه‌ی ثابت این گوشی برای پنل (یک بار ساخته و نگه داشته می‌شود؛ ربطی به شناسه‌ی سخت‌افزار ندارد) */
    @Volatile var device: String? = null

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("kanki", Context.MODE_PRIVATE)
        device = p.getString("device_id", null) ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
            .also { p.edit().putString("device_id", it).apply() }
    }

    fun isSealed(body: String) = body.startsWith("$VERSION.")

    /** کد اشتراک از آدرس: …/sub/<کد> یا …?sub=<کد> */
    fun codeOf(url: String): String? {
        val i = url.indexOf("/sub/")
        if (i >= 0) return url.substring(i + 5).substringBefore('?').substringBefore('/').ifEmpty { null }
        return Regex("""[?&]sub=([^&#]+)""").find(url)?.groupValues?.get(1)
    }

    fun open(body: String, code: String, device: String): String {
        val raw = runCatching {
            java.util.Base64.getUrlDecoder().decode(body.substring(VERSION.length + 1).trim())
        }.getOrNull()
        if (raw == null || raw.size < 12 + 16) throw java.io.IOException("sealed config: bad encoding")
        val key = key(code, device)
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
            c.updateAAD(VERSION.toByteArray())
            val plain = runCatching { c.doFinal(raw, 12, raw.size - 12) }
                .getOrElse { throw java.io.IOException("sealed config: cannot open (wrong device or code)") }
            return String(plain, Charsets.UTF_8).also { plain.fill(0) }
        } finally {
            key.fill(0)
        }
    }

    /** طرف پنل؛ فقط برای تست (مرجع: `Seal` ویندوز و `seal` پنل) */
    fun seal(plain: ByteArray, code: String, device: String, nonce: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(code, device), "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(VERSION.toByteArray())
        val out = nonce + c.doFinal(plain)
        return "$VERSION." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(out)
    }

    /** HKDF-SHA256 (RFC 5869)، ۳۲ بایت: یک دور expand کافی است */
    private fun key(code: String, device: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(device.toByteArray(), "HmacSHA256"))
        val prk = mac.doFinal(code.toByteArray())
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        prk.fill(0)
        return mac.doFinal(INFO.toByteArray() + byteArrayOf(1))
    }
}
