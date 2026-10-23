package cafe.kankipro.vpn

import java.security.SecureRandom

/*
 * پینگ واقعی KANKI MIX: همان بسته‌ی پینگ هسته (kmix-core، KindPing) با کلید همین کاربر.
 * روی سیم: [۱۲ بایت nonce][ChaCha20-Poly1305( [نوع][طول ۲ بایت][payload] + پدینگ )]
 * سرور فقط وقتی جواب (KindPong) می‌دهد که کلید این کاربر را بشناسد؛ پس کلید پاک‌شده یا سرور خاموش
 * «بدون پینگ» دیده می‌شود. این‌جا فقط ساختن و سنجیدن بسته‌هاست؛ فرستادن در Probe.kt است.
 */
object KmixHello {
    private const val KIND_PING: Byte = 0x03
    private const val KIND_PONG: Byte = 0x04
    private val rnd = SecureRandom()

    class Sent internal constructor(val packet: ByteArray, internal val payload: ByteArray, internal val key: ByteArray)

    fun ping(keyHex: String): Sent {
        val key = hex(keyHex)
        // [۸ بایت نشست][۸ بایت زمان]، مثل هسته
        val payload = ByteArray(16).also { rnd.nextBytes(it) }
        val nonce = ByteArray(12).also { rnd.nextBytes(it) }
        val pad = rnd.nextInt(65)
        val plain = ByteArray(3 + payload.size + pad)
        plain[0] = KIND_PING
        plain[1] = (payload.size ushr 8).toByte()
        plain[2] = payload.size.toByte()
        payload.copyInto(plain, 3)
        return Sent(nonce + KCrypto.seal(key, nonce, plain, ByteArray(0)), payload, key)
    }

    fun isPong(packet: ByteArray, s: Sent): Boolean {
        if (packet.size < 12 + 16 + 3) return false
        val plain = KCrypto.open(s.key, packet.copyOf(12), packet.copyOfRange(12, packet.size), ByteArray(0)) ?: return false
        val n = ((plain[1].toInt() and 0xff) shl 8) or (plain[2].toInt() and 0xff)
        return plain[0] == KIND_PONG && n == s.payload.size && plain.size >= 3 + n &&
            plain.copyOfRange(3, 3 + n).contentEquals(s.payload)
    }

    private fun hex(s: String): ByteArray {
        require(s.length == 64)
        return ByteArray(32) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
