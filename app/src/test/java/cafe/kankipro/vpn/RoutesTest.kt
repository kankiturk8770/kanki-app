package cafe.kankipro.vpn

import java.math.BigInteger
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** مسیرهای VPN همه‌ی آدرس‌ها را می‌پوشانند به‌جز خود سرور (IPv4 و IPv6) */
class RoutesTest {
    private fun inRoute(a: ByteArray, net: String, len: Int): Boolean {
        val n = InetAddress.getByName(net).address
        val bits = a.size * 8
        val x = BigInteger(1, a).shiftRight(bits - len)
        return n.size == a.size && x == BigInteger(1, n).shiftRight(bits - len)
    }

    private fun check(server: String, others: List<String>) {
        val s = InetAddress.getByName(server).address
        val r = routesExcluding(s)
        assertEquals(s.size * 8, r.size)
        assertFalse(r.any { (n, l) -> inRoute(s, n, l) })
        for (o in others) {
            val a = InetAddress.getByName(o).address
            assertEquals(o, 1, r.count { (n, l) -> inRoute(a, n, l) })
        }
    }

    @Test fun v4() = check("203.0.113.9", listOf("203.0.113.8", "1.1.1.1", "0.0.0.0", "255.255.255.255"))
    @Test fun v6() = check("2001:db8::9", listOf("2001:db8::8", "2606:4700::1111", "::", "ffff::1"))

    /** KANKI MIX با دو مسیر (۱.۴۴): خود سرور و ورودی تانل ایرانش هر دو بیرون از VPN، بقیه یک بار داخل */
    @Test fun twoServers() {
        val a = InetAddress.getByName("203.0.113.9").address
        val b = InetAddress.getByName("5.6.7.8").address
        val r = routesExcludingAll(listOf(a, b))
        assertFalse(r.any { (n, l) -> inRoute(a, n, l) || inRoute(b, n, l) })
        for (o in listOf("203.0.113.8", "5.6.7.9", "1.1.1.1", "0.0.0.0", "255.255.255.255")) {
            val x = InetAddress.getByName(o).address
            assertEquals(o, 1, r.count { (n, l) -> inRoute(x, n, l) })
        }
    }

    @Test fun wireguard_keeps_ipv6_inside_the_tunnel() {
        val c = stripIpv6("[Interface]\nAddress = 10.0.0.2/32, fd00::2/128\nDNS = 1.1.1.1, 2606:4700::1111\n[Peer]\nAllowedIPs = 0.0.0.0/0, ::/0\n")
        assertEquals(true, c.contains("AllowedIPs = 0.0.0.0/0, ::/0"))
        assertEquals(false, c.contains("fd00::2") || c.contains("2606:4700"))
        // a split tunnel gets no IPv6 route
        assertEquals(true, stripIpv6("[Peer]\nAllowedIPs = 10.0.0.0/8\n").contains("AllowedIPs = 10.0.0.0/8\n"))
    }
}
