package cafe.kankipro.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread

/** 1.45.2: Kanki DNS registers over HTTPS first; plain HTTP only when the server gives no HTTPS address or it fails */
class DnsRegisterTest {
    private fun srv(register: String, http: String, ip: String = "192.0.2.7") =
        DnsServer("d1", "DE", "", "direct", ip, "dns.example", "", "", register, http)

    @Test
    fun the_engine_gets_https_first_and_http_after_it() {
        val txt = KankiDns.wrap(listOf(srv("https://dns.example/r/tok", "http://192.0.2.7/kdns/r/tok"), srv("", "", "192.0.2.8")))
        assertTrue(txt, txt.contains("Server = 192.0.2.7 https://dns.example/r/tok http://192.0.2.7/kdns/r/tok\n"))
        assertTrue(txt, txt.contains("Server = 192.0.2.8 - -\n"))
        // a plain «http» in the https field is never taken for one
        assertTrue(KankiDns.wrap(listOf(srv("http://dns.example/r/tok", ""))).contains("Server = 192.0.2.7 - -"))
    }

    @Test
    fun a_server_without_https_still_registers_over_http() {
        // a tiny HTTP server on this machine stands for the DNS server's plain register address
        val ss = ServerSocket(0)
        val seen = StringBuilder()
        val t = thread {
            ss.accept().use { c ->
                val r = c.getInputStream().bufferedReader()
                seen.append(r.readLine())
                while (r.readLine()?.isNotEmpty() == true) { }
                c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
            }
        }
        val ms = KankiDns.register(srv("", "http://127.0.0.1:${ss.localPort}/kdns/r/tok"))
        t.join(5000)
        ss.close()
        assertTrue(ms != null)
        assertEquals("GET /kdns/r/tok HTTP/1.1", seen.toString())
        // nothing to register with at all
        assertNull(KankiDns.register(srv("", "")))
    }

    @Test(expected = IOException::class)
    fun only_http_and_https_are_spoken() {
        PlainHttp.request("GET", "ftp://127.0.0.1/x", emptyMap(), null, timeoutMs = 500)
    }
}
