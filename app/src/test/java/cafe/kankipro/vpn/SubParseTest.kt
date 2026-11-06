package cafe.kankipro.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * صفحه‌ی اشتراک واقعی پنل ۳.۳۲ (یک سرور آلمان با تانل ایران و سرور خود پنل، کاربر با همه‌ی پروتکل‌ها) باید
 * همه‌ی کانفیگ‌هایش را بدهد: هیچ پروتکل، سرور یا نسخه‌ی تانلی جا نماند.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SubParseTest {
    private val base = "https://panel.example.com"
    private val html = javaClass.classLoader!!.getResource("sub-panel-3.32.html")!!.readText()

    @Test
    fun every_config_of_the_panel_page_is_read() {
        val a = SubscriptionApi.parse(html, base)
        val got = a.configs.map { it.node to it.protocol }.toSet()
        for (want in listOf(
            "de" to "amneziawg", "de@tunnel" to "amneziawg", "de@tunnel" to "wireguard",
            "de" to "hysteria2", "de@tunnel" to "hysteria2",
            "local" to "kankimix", "de" to "kankimix", "de@tunnel" to "kankimix",
        )) assertTrue("missing $want in $got", want in got)
        // microtun از داده‌ی صفحه: هر سه
        assertEquals(setOf("local", "de", "de@tunnel"), a.mtun.map { it.node }.toSet())
        assertTrue(a.mtun.all { MtunCfg.supported(it.url) })
        // راه هر کانفیگ و AmneziaWG مستقیم/تانل
        assertEquals("tunnel", a.viaProto["de@tunnel"]?.get("wireguard"))
        assertEquals("direct", a.awg["de"]?.via)
        assertEquals("tunnel", a.awg["de@tunnel"]?.via)
        // لینک کانفیگ‌ها فقط از همان پنلی که وارد شده
        assertTrue(a.configs.all { it.url.startsWith("$base/") })
        assertEquals("Active", a.status)
        // حجم و انقضا از داده‌ی صفحه (پنل ۳ دیگر data-traffic-* ندارد)
        assertEquals(10.0, a.limitGb!!, 0.0001)
        assertEquals(2.5, a.usedGb!!, 0.0001)
        assertEquals(1796550432000L, a.expiresAt)
        // دکمه‌های QR و «لینک» هر کانفیگ جدا شمرده نمی‌شوند: فقط پروتکل‌های واقعی، هر کدام یک بار
        assertTrue(a.configs.all { "/" !in it.protocol })
        assertEquals(a.configs.size, a.configs.map { it.node to it.protocol }.toSet().size)
    }

    @Test
    fun hysteria_and_kmix_links_point_at_the_text_not_the_qr() {
        val a = SubscriptionApi.parse(html, base)
        val hy = a.configs.filter { it.protocol == "hysteria2" }
        assertTrue(hy.isNotEmpty() && hy.all { "/hysteria2?" in it.url })
        val km = a.configs.filter { it.protocol == "kankimix" }
        assertTrue(km.isNotEmpty() && km.all { "/kankimix?" in it.url })
    }

    @Test
    fun a_custom_mtu_only_lowers_the_panels() {
        val wg = "[Interface]\nPrivateKey = x\nMTU = 1320\n\n[Peer]\nEndpoint = a:1\n"
        // 1380 typed on the phone would make 1440-byte packets: the panel's 1320 stays
        assertTrue("MTU = 1320" in applyMtu(wg, "1380"))
        // lower is taken
        assertTrue("MTU = 1280" in applyMtu(wg, "1280"))
        // empty = the panel's
        assertEquals(wg, applyMtu(wg, ""))
        // KANKI MIX and microtun are never touched
        val km = "[Interface]\n[KankiMix]\nEndpoint = a:1\nURI = kanki://k@a:1\n"
        assertEquals(km, applyMtu(km, "1280"))
    }
}
