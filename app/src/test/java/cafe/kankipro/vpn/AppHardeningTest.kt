package cafe.kankipro.vpn

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 1.45: the code never in a URL, no panel address in the code, encrypted storage, Xray fragment, KANKI MIX links */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppHardeningTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val code = "kanki-12-abcd1234"

    @Test
    fun the_code_leaves_the_config_url() {
        val (u, c) = SubscriptionApi.splitCode("https://p.example:2053/api/config/12/kankimix?sub=$code&node=n1~2%40tunnel")
        assertEquals("https://p.example:2053/api/config/12/kankimix?node=n1~2%40tunnel", u)
        assertEquals(code, c)
        val (u2, c2) = SubscriptionApi.splitCode("https://p.example/api/config/12/wireguard?node=de&sub=$code")
        assertEquals("https://p.example/api/config/12/wireguard?node=de", u2)
        assertEquals(code, c2)
        assertEquals("https://p.example/api/config/12/wireguard" to code, SubscriptionApi.splitCode("https://p.example/api/config/12/wireguard?sub=$code"))
        // nothing to take: the same URL (an empty sub= is not a code)
        assertEquals("https://p.example/x?node=a" to null, SubscriptionApi.splitCode("https://p.example/x?node=a"))
        assertEquals("https://p.example/x?sub=&node=a" to null, SubscriptionApi.splitCode("https://p.example/x?sub=&node=a"))
    }

    @Test
    fun a_bare_code_needs_a_known_panel() {
        val was = SubscriptionApi.CONFIGURED
        try {
            SubscriptionApi.CONFIGURED = emptyList()
            assertEquals(null to code, SubscriptionApi.normalize(code))
            SubscriptionApi.CONFIGURED = SubscriptionApi.parseHosts("https://a.example:2053/, http://plain.example, https://u@b.example, https://c.example/path,https://d.example")
            assertEquals(listOf("https://a.example:2053", "https://d.example"), SubscriptionApi.CONFIGURED)
            assertEquals("https://a.example:2053" to code, SubscriptionApi.normalize(code))
            // a full link names its own panel; a user part never moves the code to another host
            assertEquals("https://x.example:8443" to code, SubscriptionApi.normalize("https://x.example:8443/sub/$code"))
            assertEquals(null, SubscriptionApi.normalize("https://x.example@evil.example/sub/nope"))
        } finally {
            SubscriptionApi.CONFIGURED = was
        }
    }

    @Test
    fun panel_requests_are_https_only() {
        for (bad in listOf("http://p.example/sub/me", "https://user@p.example/sub/me", "ftp://p.example/x")) {
            try {
                PanelTls.open(bad)
                fail("opened $bad")
            } catch (e: java.io.IOException) {
                assertTrue(e.message.orEmpty().contains("https") || e is java.net.MalformedURLException)
            }
        }
        val c = PanelTls.open("https://p.example/sub/me")
        assertFalse(c.instanceFollowRedirects)
    }

    @Test
    fun stored_settings_are_sealed_and_names_hidden() {
        useSoftKeys(3)
        val plain = ctx.getSharedPreferences("kanki", Context.MODE_PRIVATE)
        plain.edit().clear().putString("code", code).putString("subhtml_$code", "<html>secret page</html>").putBoolean("sound", false).putLong("last_sync", 42L).commit()
        val p = SecurePrefs.get(ctx)
        // moved in from the plain file of before, which is emptied
        assertEquals(code, p.getString("code", null))
        assertEquals("<html>secret page</html>", p.getString("subhtml_$code", null))
        assertEquals(false, p.getBoolean("sound", true))
        assertEquals(42L, p.getLong("last_sync", 0L))
        assertTrue(plain.all.isEmpty())
        p.edit().putStringSet("games", mutableSetOf("com.a", "com.b")).putInt("n", 5).putFloat("f", 1.5f).remove("sound").commit()
        assertEquals(setOf("com.a", "com.b"), p.getStringSet("games", null))
        assertFalse(p.contains("sound"))
        assertTrue("subhtml_$code" in p.all.keys)
        // on disk: neither a name nor a value can be read
        val disk = ctx.getSharedPreferences("kanki_secure", Context.MODE_PRIVATE).all
        assertTrue(disk.isNotEmpty())
        for ((k, v) in disk) {
            assertTrue(k.startsWith("k_"))
            for (secret in listOf(code, "secret page", "com.a", "subhtml", "last_sync")) {
                assertFalse("$secret in $k", k.contains(secret))
                assertFalse("$secret in value", v.toString().contains(secret))
            }
        }
        // opened again (a new start): the same values
        SecurePrefs.reset()
        assertEquals(code, SecurePrefs.get(ctx).getString("code", null))
        assertEquals(5, SecurePrefs.get(ctx).getInt("n", 0))
        // another key (the Keystore key was lost): nothing old is readable, the app starts clean instead of crashing
        useSoftKeys(9)
        val fresh = SecurePrefs.get(ctx)
        assertNull(fresh.getString("code", null))
        fresh.edit().putString("code", "kanki-1-new0").commit()
        assertEquals("kanki-1-new0", fresh.getString("code", null))
        useSoftKeys()
    }

    @Test
    fun xray_fragment_of_the_panel_link() {
        val link = "vless://11111111-2222-3333-4444-555555555555@x.example:443?security=tls&type=tcp&sni=x.example&fragment=tlshello%2C100-200%2C10-20#a"
        assertEquals(Triple("tlshello", "100-200", "10-20"), XrayCfg.fragment(link))
        val cfg = JSONObject(XrayCfg.config(link, "203.0.113.9", 10808, SocksAuth("u", "p")))
        val outs = cfg.getJSONArray("outbounds")
        val proxy = outs.getJSONObject(0)
        assertEquals("fragment", proxy.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))
        val frag = (0 until outs.length()).map { outs.getJSONObject(it) }.first { it.optString("tag") == "fragment" }
        assertEquals("freedom", frag.getString("protocol"))
        assertEquals("100-200", frag.getJSONObject("settings").getJSONObject("fragment").getString("length"))
        // malformed fragments are ignored, never passed to Xray
        for (bad in listOf("tlshello", "tlshello,100-200", "evil,1-2,1-2", "tlshello,200-100,1-2", "tlshello,1-2,1-2,9", "tlshello,123456-1,1-2")) {
            val l = link.replace("tlshello%2C100-200%2C10-20", bad.replace(",", "%2C"))
            assertNull(bad, XrayCfg.fragment(l))
            val c = JSONObject(XrayCfg.config(l, "203.0.113.9", 10808, SocksAuth("u", "p")))
            assertFalse(c.toString().contains("dialerProxy"))
        }
        // vmess links carry none
        assertNull(XrayCfg.fragment("vmess://e30="))
    }

    @Test
    fun kanki_mix_links_one_address_and_extra_tunnel_entries() {
        val k = "a".repeat(64)
        val u = KmixUri.parse("kanki://$k@203.0.113.5:4000?fec=10-0&ports=4001-4003&gaming=1&alt=198.51.100.7%3A4100#x")
        assertEquals(0, u.fecParity)
        assertEquals(10, u.fecData)
        assertEquals("4000,4001-4003", u.portSpec())
        // an old link without fec keeps the parity of before
        assertEquals(3, KmixUri.parse("kanki://$k@203.0.113.5:4000#x").fecParity)
        for ((id, parent) in listOf("n2" to "n2", "n2@tunnel" to "n2", "n2~3@tunnel" to "n2", "n2~4@tunnel" to "n2", "a~b@tunnel" to "a~b", "n2~3" to "n2~3")) {
            assertEquals(id, parent, nodeParent(id))
        }
    }
}
