package cafe.kankipro.vpn

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Robolectric has no Android Keystore: the tests wrap the data key with a software AES key instead */
class SoftKeyWrap(seed: Byte = 7) : SecurePrefs.KeyWrap {
    private val key = SecretKeySpec(ByteArray(32) { seed }, "AES")
    override fun wrap(plain: ByteArray): ByteArray {
        val iv = ByteArray(12) { 1 }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }
    override fun unwrap(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
        return c.doFinal(sealed, 12, sealed.size - 12)
    }
}

fun useSoftKeys(seed: Byte = 7) {
    SecurePrefs.keyWrap = { SoftKeyWrap(seed) }
    SecurePrefs.reset()
}
