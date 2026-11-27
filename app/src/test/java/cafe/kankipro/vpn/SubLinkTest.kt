package cafe.kankipro.vpn

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/** 1.45.1: every shape of a subscription the user may paste, the build's hosts, TLS, redirects, seal and storage */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SubLinkTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val code = "kanki-12-4821930012345678901"
    private val digits = "4821930012345678901"
    private val hash = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun panel_hosts_are_cut_to_their_origin() {
        assertEquals("https://p.example:2053", PanelLink.origin("https://p.example:2053/admin"))
        assertEquals("https://p.example:2053", PanelLink.origin(" https://p.example:2053/sub/ "))
        assertEquals("https://p.example:2053", PanelLink.origin("https://p.example:2053/sub/me"))
        assertEquals("https://p.example:2053", PanelLink.origin("https://P.Example:2053/panel/"))
        assertEquals("https://p.example", PanelLink.origin("p.example/"))
        assertEquals("https://p.example", PanelLink.origin("http://p.example:443"))
        assertEquals("https://p.example:8443", PanelLink.origin("‏https://p.example:8443‎"))
        for (bad in listOf("https://u@p.example", "ftp://p.example", "https://p.example:99999", "https://p_ex ample", "https://", "", "https://p.example:x"))
            assertNull(bad, PanelLink.origin(bad))
        assertEquals(
            listOf("https://a.example:2053", "https://b.example:2053", "https://c.example", "https://d.example"),
            PanelLink.hosts("https://a.example:2053/admin, https://b.example:2053/sub/\nhttps://a.example:2053/ ;c.example http://d.example https://u@e.example"),
        )
    }

    @Test
    fun every_input_shape_is_read() {
        assertEquals(PanelLink.Input.Code(null, code), PanelLink.parse(code))
        assertEquals(PanelLink.Input.Code(null, code), PanelLink.parse("  کد فعال‌سازی:\n$code‏ "))
        assertEquals(PanelLink.Input.Bare(null, digits), PanelLink.parse(digits))
        assertEquals(PanelLink.Input.Bare(null, digits), PanelLink.parse("۴۸۲۱۹۳۰۰۱۲۳۴۵۶۷۸۹۰۱"))
        assertEquals(PanelLink.Input.Code("https://p.example:2053", code), PanelLink.parse("https://p.example:2053/sub/$code"))
        assertEquals(PanelLink.Input.Code("https://p.example:2053", code), PanelLink.parse("https://p.example:2053/sub/sub/$code/"))
        assertEquals(PanelLink.Input.Code("https://p.example:2053", code), PanelLink.parse("p.example:2053/sub/$code/json"))
        assertEquals(PanelLink.Input.Bare("https://r.example:2053", digits), PanelLink.parse("https://r.example:2053/sub/$digits"))
        assertEquals(PanelLink.Input.Hash("https://p.example", hash), PanelLink.parse("https://p.example/sub/h/$hash/v2"))
        assertEquals(PanelLink.Input.Code("https://p.example", code), PanelLink.parse("https://p.example/sub/me?sub=$code"))
        assertEquals(PanelLink.Input.Code("https://p.example", code), PanelLink.parse("https://p.example/api/config/12/wireguard?node=de&sub=$code"))
        // a hash link with a short or non-hex hash, or /sub/me with nothing, is not a subscription
        assertNull(PanelLink.parse("https://p.example/sub/h/abc"))
        assertNull(PanelLink.parse("https://p.example/sub/me"))
        assertNull(PanelLink.parse("hello"))
        assertNull(PanelLink.parse("12345"))
        // a user part never moves the code to another host
        assertNull(PanelLink.parse("https://p.example@evil.example/sub/x"))
        assertEquals(PanelLink.Input.Code(null, code), PanelLink.parse("https://p.example@evil.example/sub/$code"))
    }

    @Test
    fun a_bare_code_uses_the_builds_hosts() {
        val was = SubscriptionApi.CONFIGURED
        try {
            SubscriptionApi.CONFIGURED = emptyList()
            assertNull(SubscriptionApi.DEFAULT_HOST)
            SubscriptionApi.CONFIGURED = SubscriptionApi.parseHosts("https://a.example:2053/admin,https://b.example:2053/sub/")
            assertEquals("https://a.example:2053", SubscriptionApi.DEFAULT_HOST)
            assertEquals(listOf("https://a.example:2053", "https://b.example:2053"), SubscriptionApi.hosts("https://a.example:2053"))
            // a panel not of this build is asked alone
            assertEquals(listOf("https://x.example"), SubscriptionApi.hosts("https://x.example"))
        } finally {
            SubscriptionApi.CONFIGURED = was
        }
    }

    @Test
    fun the_code_is_found_in_its_own_page() {
        val page = """<a href="/api/config/12/wireguard?sub=$code&amp;node=local">x</a>"""
        assertEquals(code, PanelLink.codeInPage(page))
        assertEquals(code, PanelLink.codeInPage(page, digits))
        assertNull(PanelLink.codeInPage(page, "9999999999"))
        assertTrue(SubscriptionApi.looksLikePage(page))
        assertFalse(SubscriptionApi.looksLikePage("<html><body>502 Bad Gateway</body></html>"))
    }

    /** the system trust manager of a phone whose network security config has a domain-config (ours does) */
    private class DomainConfigTrust : X509ExtendedTrustManager() {
        var hostAware = 0
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("Domain specific configurations require that hostname aware checkServerTrusted(X509Certificate[], String, String) is used")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) { hostAware++ }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) { hostAware++ }
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("client")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException("client")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw CertificateException("client")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun the_panel_check_is_hostname_aware() {
        // 1.45.0 called the two-argument check, which this trust manager (Android's, with a domain-config) refuses
        val sys = DomainConfigTrust()
        val t = PanelTls.Pinned(sys, emptySet())
        t.checkServerTrusted(emptyArray(), "ECDHE_ECDSA", null as Socket?)
        t.checkServerTrusted(emptyArray(), "ECDHE_ECDSA", null as SSLEngine?)
        assertEquals(2, sys.hostAware)
        // pins: a chain without a pinned key is refused after the system's check
        val pinned = PanelTls.Pinned(sys, setOf("A".repeat(43) + "="))
        try {
            pinned.checkServerTrusted(emptyArray(), "ECDHE_ECDSA", null as Socket?)
            fail("an unpinned chain passed")
        } catch (e: CertificateException) {
            assertEquals(PanelError.Kind.PIN, PanelError.of(javax.net.ssl.SSLHandshakeException("x").apply { initCause(e) }).kind)
        }
        // no client certificate is ever accepted
        assertTrue(runCatching { t.checkClientTrusted(emptyArray(), "RSA", null as Socket?) }.isFailure)
    }

    @Test
    fun only_same_origin_https_redirects_are_followed() {
        val from = "https://p.example:2053/sub/me"
        assertEquals("https://p.example:2053/sub/me/", SubscriptionApi.sameOrigin(from, "/sub/me/"))
        assertEquals("https://P.example:2053/sub/me", SubscriptionApi.sameOrigin(from, "https://P.example:2053/sub/me"))
        assertNull(SubscriptionApi.sameOrigin(from, "http://p.example:2053/sub/me"))
        assertNull(SubscriptionApi.sameOrigin(from, "https://evil.example:2053/sub/me"))
        assertNull(SubscriptionApi.sameOrigin(from, "https://p.example/sub/me"))
        assertNull(SubscriptionApi.sameOrigin(from, "//evil.example/sub/me"))
        assertNull(SubscriptionApi.sameOrigin(from, "https://u@p.example:2053/x"))
        assertNull(SubscriptionApi.sameOrigin(from, null))
    }

    @Test
    fun failures_have_their_own_reason() {
        fun k(t: Throwable) = PanelError.of(t).kind
        assertEquals(PanelError.Kind.INVALID_CODE, k(SubscriptionApi.HttpStatus(404)))
        assertEquals(PanelError.Kind.INVALID_CODE, k(SubscriptionApi.HttpStatus(401)))
        assertEquals(PanelError.Kind.RELAY, k(SubscriptionApi.HttpStatus(502)))
        assertEquals(PanelError.Kind.DNS, k(java.net.UnknownHostException("x")))
        assertEquals(PanelError.Kind.NETWORK, k(java.net.SocketTimeoutException("x")))
        assertEquals(PanelError.Kind.TLS, k(javax.net.ssl.SSLHandshakeException("x")))
        // the real panel's «unknown code» wins over a relay that is down
        val best = PanelError.better(PanelError.of(SubscriptionApi.HttpStatus(502)), PanelError.of(SubscriptionApi.HttpStatus(404)))
        assertEquals(PanelError.Kind.INVALID_CODE, best.kind)
        for (kind in PanelError.Kind.values()) assertTrue(PanelError(kind).persian.isNotBlank())
        // nothing to race: the build knows no host
        try {
            SubscriptionApi.raceWith(emptyList()) { it }
            fail("raced nothing")
        } catch (e: PanelError) {
            assertEquals(PanelError.Kind.NO_HOSTS, e.kind)
        }
    }

    @Test
    fun the_panels_seal_opens_byte_for_byte() {
        // seal() of the panel's devices.rs (the_same_bytes_as_the_go_seal) with the same inputs
        assertEquals("hello kanki", Seal.open("kseal1.BwcHBwcHBwcHBwcHvnHPO9eY_DY09_ePqTKyeYjxT3wdvUGtrDWp", "kanki-12-4821930012", "dev-xyz-123"))
        val round = Seal.seal("{\"a\":1}".toByteArray(), code, "dev-abcdef", ByteArray(12) { 5 })
        assertTrue(Seal.isSealed(round))
        assertEquals("{\"a\":1}", Seal.open(round, code, "dev-abcdef"))
        assertTrue(runCatching { Seal.open(round, "kanki-12-other", "dev-abcdef") }.isFailure)
        assertFalse(Seal.isSealed("<html>plain page</html>"))
    }

    /** a Keystore that fails the way an upgrade over an older APK makes it fail */
    private class BrokenWrap(private val fail: () -> Exception) : SecurePrefs.KeyWrap {
        private val soft = SoftKeyWrap(4)
        var resets = 0
        var broken = true
        override fun wrap(plain: ByteArray) = soft.wrap(plain)
        override fun unwrap(sealed: ByteArray): ByteArray = if (broken) throw fail() else soft.unwrap(sealed)
        override fun reset() { resets++; broken = false }
    }

    @Test
    fun a_broken_keystore_starts_clean_instead_of_blocking_login() {
        for (fail in listOf<() -> Exception>(
            { javax.crypto.AEADBadTagException("tag mismatch") },
            { java.security.KeyStoreException("key gone") },
            { java.security.ProviderException("keystore2") },
        )) {
            useSoftKeys(4)
            SecurePrefs.get(ctx).edit().putString("code", code).putString("subhtml_$code", "<html>old</html>").commit()
            val w = BrokenWrap(fail)
            SecurePrefs.keyWrap = { w }
            SecurePrefs.reset()
            val p = SecurePrefs.get(ctx)
            assertNull(p.getString("code", null))
            assertEquals(1, w.resets)
            p.edit().putString("code", "kanki-1-new0").commit()
            // the next start opens what was written after the recovery
            SecurePrefs.reset()
            assertEquals("kanki-1-new0", SecurePrefs.get(ctx).getString("code", null))
        }
        // a damaged key blob (not base64) is recovered the same way
        useSoftKeys(4)
        ctx.getSharedPreferences("kanki_keys", Context.MODE_PRIVATE).edit().putString("dk", "%%%not base64%%%").commit()
        SecurePrefs.reset()
        val p = SecurePrefs.get(ctx)
        p.edit().putString("code", code).commit()
        assertEquals(code, p.getString("code", null))
        useSoftKeys()
    }
}
