package cafe.kankipro.vpn

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Everything the app keeps (subscription code, the subscription page, configs with private keys, settings) is
 * stored encrypted (1.45). An AES-256 key that never leaves the Android Keystore wraps a random data key; the data
 * key seals every value with AES-GCM, and the names are replaced by an HMAC of them (a name like
 * "subhtml_<code>" would carry the code), so the file on disk shows neither names nor values.
 *
 * Small and without a library (no Tink, which EncryptedSharedPreferences would add to the APK): one Keystore
 * operation when the app starts, everything else in memory. Values written by older versions (the plain "kanki"
 * file) are moved in once and the plain file is emptied.
 */
object SecurePrefs {
    private const val FILE = "kanki_secure"
    private const val KEYS = "kanki_keys"
    private const val OLD = "kanki"
    private const val ALIAS = "kanki_prefs_v1"

    /** wraps and unwraps the data key; the Keystore one on a phone (tests give a software one) */
    interface KeyWrap {
        fun wrap(plain: ByteArray): ByteArray
        fun unwrap(sealed: ByteArray): ByteArray
    }

    @Volatile var keyWrap: () -> KeyWrap = { KeystoreWrap() }

    @Volatile private var inst: Prefs? = null

    fun get(ctx: Context): SharedPreferences = inst ?: synchronized(this) {
        inst ?: Prefs(ctx.applicationContext).also { inst = it }
    }

    /** for the tests: the next get builds a new one */
    fun reset() = synchronized(this) { inst = null }

    class KeystoreWrap : KeyWrap {
        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            g.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return g.generateKey()
        }

        override fun wrap(plain: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            return c.iv + c.doFinal(plain)
        }

        override fun unwrap(sealed: ByteArray): ByteArray {
            if (sealed.size < 12 + 16) throw GeneralSecurityException("short key blob")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
            return c.doFinal(sealed, 12, sealed.size - 12)
        }
    }

    /** a value in memory: its type tag and text ("s" string, "b" boolean, "i" int, "l" long, "f" float, "S" set) */
    private data class V(val t: Char, val v: Any)

    class Prefs internal constructor(ctx: Context) : SharedPreferences {
        private val disk: SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        private val data: SecretKey
        private val mac: SecretKey
        private val mem = ConcurrentHashMap<String, V>()
        private val rnd = SecureRandom()
        private val listeners = java.util.Collections.newSetFromMap(ConcurrentHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Boolean>())

        init {
            val kp = ctx.getSharedPreferences(KEYS, Context.MODE_PRIVATE)
            val w = keyWrap()
            val blob = kp.getString("dk", null)?.let { Base64.getDecoder().decode(it) }
            val raw = blob?.let {
                try {
                    w.unwrap(it)
                } catch (e: GeneralSecurityException) {
                    // the Keystore key is gone (the phone's lock screen was reset, a restore): what was sealed with it
                    // can never be read again, so the app starts clean (the user logs in once more)
                    android.util.Log.w("Kanki", "stored settings could not be opened (${e.javaClass.simpleName}); starting clean")
                    null
                }
            } ?: run {
                // a new data key: whatever was sealed with an older one (none on a first start) cannot be read
                disk.edit().clear().commit()
                ByteArray(64).also { rnd.nextBytes(it) }.also { kp.edit().putString("dk", Base64.getEncoder().encodeToString(w.wrap(it))).commit() }
            }
            data = SecretKeySpec(raw, 0, 32, "AES")
            mac = SecretKeySpec(raw, 32, 32, "HmacSHA256")
            for ((k, v) in disk.all) {
                if (!k.startsWith("k_") || v !is String) continue
                val (name, value) = open(v) ?: continue
                mem[name] = value
            }
            migrate(ctx)
        }

        /** the plain file of the versions before: moved in, then emptied */
        private fun migrate(ctx: Context) {
            val old = ctx.getSharedPreferences(OLD, Context.MODE_PRIVATE)
            val all = old.all
            if (all.isEmpty()) return
            val e = edit()
            for ((k, v) in all) {
                if (mem.containsKey(k)) continue
                when (v) {
                    is String -> e.putString(k, v)
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putInt(k, v)
                    is Long -> e.putLong(k, v)
                    is Float -> e.putFloat(k, v)
                    is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
                }
            }
            if (e.commit()) old.edit().clear().commit()
        }

        private fun nameOf(k: String): String {
            val m = Mac.getInstance("HmacSHA256")
            m.init(mac)
            return "k_" + Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal(k.toByteArray(Charsets.UTF_8)))
        }

        private fun seal(k: String, v: V): String {
            val text = when (v.t) {
                'S' -> (v.v as Set<*>).joinToString("\u0000")
                else -> v.v.toString()
            }
            val kb = k.toByteArray(Charsets.UTF_8)
            val plain = byteArrayOf(v.t.code.toByte()) + java.nio.ByteBuffer.allocate(4).putInt(kb.size).array() + kb + text.toByteArray(Charsets.UTF_8)
            val iv = ByteArray(12).also { rnd.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, data, GCMParameterSpec(128, iv))
            return Base64.getEncoder().encodeToString(iv + c.doFinal(plain))
        }

        /** (name, value) of a sealed entry; null when it does not open (another key, damaged) */
        private fun open(s: String): Pair<String, V>? = runCatching {
            val b = Base64.getDecoder().decode(s)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, data, GCMParameterSpec(128, b, 0, 12))
            val p = c.doFinal(b, 12, b.size - 12)
            val t = p[0].toInt().toChar()
            val n = java.nio.ByteBuffer.wrap(p, 1, 4).int
            if (n < 0 || 5 + n > p.size) return@runCatching null
            val name = String(p, 5, n, Charsets.UTF_8)
            val text = String(p, 5 + n, p.size - 5 - n, Charsets.UTF_8)
            val v: Any = when (t) {
                's' -> text
                'b' -> text.toBooleanStrict()
                'i' -> text.toInt()
                'l' -> text.toLong()
                'f' -> text.toFloat()
                'S' -> if (text.isEmpty()) emptySet() else text.split('\u0000').toSet()
                else -> return@runCatching null
            }
            name to V(t, v)
        }.getOrNull()

        override fun getAll(): MutableMap<String, *> = mem.mapValuesTo(HashMap()) { it.value.v }
        override fun getString(key: String, defValue: String?): String? = (mem[key]?.takeIf { it.t == 's' }?.v as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (mem[key]?.takeIf { it.t == 'S' }?.v as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = mem[key]?.takeIf { it.t == 'i' }?.v as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = mem[key]?.takeIf { it.t == 'l' }?.v as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = mem[key]?.takeIf { it.t == 'f' }?.v as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = mem[key]?.takeIf { it.t == 'b' }?.v as? Boolean ?: defValue
        override fun contains(key: String): Boolean = mem.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(l) }
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(l) }

        override fun edit(): SharedPreferences.Editor = Ed()

        private inner class Ed : SharedPreferences.Editor {
            private val put = LinkedHashMap<String, V?>()
            private var clear = false
            override fun putString(key: String, value: String?) = apply { put[key] = value?.let { V('s', it) } }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { put[key] = values?.let { V('S', it.toSet()) } }
            override fun putInt(key: String, value: Int) = apply { put[key] = V('i', value) }
            override fun putLong(key: String, value: Long) = apply { put[key] = V('l', value) }
            override fun putFloat(key: String, value: Float) = apply { put[key] = V('f', value) }
            override fun putBoolean(key: String, value: Boolean) = apply { put[key] = V('b', value) }
            override fun remove(key: String) = apply { put[key] = null }
            override fun clear() = apply { clear = true }

            /** memory first (readers see it at once), then the file */
            private fun stage(): SharedPreferences.Editor {
                val d = disk.edit()
                if (clear) {
                    mem.clear()
                    d.clear()
                }
                for ((k, v) in put) {
                    if (v == null) {
                        mem.remove(k)
                        d.remove(nameOf(k))
                    } else {
                        mem[k] = v
                        d.putString(nameOf(k), seal(k, v))
                    }
                }
                return d
            }

            private fun tell() {
                if (listeners.isEmpty()) return
                for (k in put.keys) listeners.forEach { it.onSharedPreferenceChanged(this@Prefs, k) }
            }

            override fun commit(): Boolean = synchronized(this@Prefs) { stage().commit() }.also { tell() }
            override fun apply() {
                synchronized(this@Prefs) { stage().apply() }
                tell()
            }
        }
    }
}
