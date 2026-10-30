package cafe.kankipro.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** کانفیگ رمزشده: همان بایت‌های پنل و ویندوز (TestSealedConfig در sub_test.go، seals_like_the_windows_app در پنل) */
class SealTest {
    private val plain = "[Interface]\nPrivateKey = x\n"

    @Test
    fun the_same_bytes_as_the_go_seal() {
        val s = Seal.seal(plain.toByteArray(), "kanki-12-abcd1234", "dev-1", ByteArray(12))
        assertEquals("kseal1.AAAAAAAAAAAAAAAAyDC2GuB9agBDt8PiYnaUsR4OSdnZA4KIQLKn8qvTmli3vdi2iylpE9UM_w", s)
        assertEquals(plain, Seal.open(s, "kanki-12-abcd1234", "dev-1"))
    }

    @Test
    fun another_device_cannot_open_it() {
        val s = Seal.seal(plain.toByteArray(), "kanki-12-abcd1234", "dev-1", ByteArray(12))
        assertTrue(runCatching { Seal.open(s, "kanki-12-abcd1234", "dev-2") }.isFailure)
    }

    @Test
    fun finds_the_code_in_both_link_shapes() {
        assertEquals("kanki-12-abcd1234", Seal.codeOf("https://sub.kanki.pro/sub/kanki-12-abcd1234"))
        assertEquals("kanki-12-abcd1234", Seal.codeOf("https://x/api/config/3/wg?sub=kanki-12-abcd1234&t=1"))
        assertEquals(null, Seal.codeOf("https://x/other"))
    }
}
