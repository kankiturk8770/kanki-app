package cafe.kankipro.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4

/** تست سرعت ۱.۳۵: امتیاز کیفیت (همان عددهای صفحه‌ی Cloudflare)، صدک، تاریخچه، جمع‌بندی «اینترنت من» */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SpeedTest {
    @Test fun aimMatchesCloudflareScreenshots() {
        // عکس اول صاحب اپ: ۲۵۸ms، لرزش ۱۰.۴، پکت‌لاس ۰، دانلود ۲۰.۵، زیر بار ۲۷۱/۲۶۱ ← پخش ۳ ستاره، بازی ۲، تماس ۳
        val a = Aim.scores(258.0, 10.4, 0.0, 20.5, 271.0, 261.0)
        assertEquals(2, a["streaming"])
        assertEquals(1, a["gaming"])
        assertEquals(2, a["rtc"])
        // عکس دوم: ۲۹۵ms، لرزش ۴۶.۳، پکت‌لاس ۳.۹٪، دانلود ۲۱.۸، زیر بار ۳۶۵/۳۲۳ ← هر سه ۱ ستاره
        val b = Aim.scores(295.0, 46.3, 3.9, 21.8, 365.0, 323.0)
        assertEquals(0, b["streaming"])
        assertEquals(0, b["gaming"])
        assertEquals(0, b["rtc"])
        // اینترنت خیلی خوب: همه عالی
        val c = Aim.scores(8.0, 2.0, 0.0, 300.0, 12.0, 14.0)
        assertEquals(4, c["streaming"]); assertEquals(4, c["gaming"]); assertEquals(4, c["rtc"])
        // بی پینگ زیر بار: هیچ امتیازی (مثل Cloudflare)
        assertTrue(Aim.scores(20.0, 2.0, 0.0, 50.0, null, null).isEmpty())
    }

    @Test fun bloatGrades() {
        assertEquals("A+", Aim.bloatGrade(2.0))
        assertEquals("A", Aim.bloatGrade(13.0))
        assertEquals("C", Aim.bloatGrade(70.0))
        assertEquals("F", Aim.bloatGrade(900.0))
    }

    @Test fun percentileAndServerTime() {
        assertEquals(2.5, SpeedEngine.percentile(listOf(1.0, 2.0, 3.0, 4.0), .5), 1e-9)
        assertEquals(3.7, SpeedEngine.percentile(listOf(4.0, 1.0, 3.0, 2.0), .9), 1e-9)
        assertEquals(1.25, SpeedEngine.serverTime("cfRequestDuration;dur=1.25"), 1e-9)
        assertEquals(0.0, SpeedEngine.serverTime(null), 1e-9)
        assertEquals(0.0, SpeedEngine.serverTime("dur=abc"), 1e-9)
        assertEquals(29.0, SpeedEngine.serverTime("cfSpeedEdge;dur=3, cfSpeedWorker;dur=26"), 1e-9)
        assertEquals(0.0, SpeedEngine.serverTime("cfL4;desc=\"?proto=TCP&rtt=9600\""), 1e-9)
        assertEquals(48, SpeedEngine.downsample((1..500).map { it.toDouble() }).size)
        assertEquals(1.0, SpeedEngine.jitter(listOf(10.0, 11.0, 10.0)), 1e-9)
    }

    @Test fun udpLoss() {
        assertEquals(25.0, UdpSeries.lossPct(listOf(1L, null, 3L, 4L))!!, 1e-9)
        assertNull(UdpSeries.lossPct(emptyList()))
    }

    @Test fun historyRoundTrip() {
        val r = SpeedRecord(123L, "direct", null, 10.5, 2.0, 41.0, null, 0.0, 90.0, null, "Cloudflare")
        assertEquals(r, SpeedRecord.fromJson(r.toJson()))
        // داده‌ی خراب: نادیده
        assertNull(SpeedRecord.fromJson(org.json.JSONObject("{\"k\":\"vpn\"}")))
        val bad = SpeedRecord.fromJson(org.json.JSONObject("{\"t\":1,\"k\":\"x\",\"d\":-5}"))!!
        assertEquals("vpn", bad.kind); assertNull(bad.down)
    }

    @Test fun bogusAddresses() {
        fun ip(s: String) = java.net.InetAddress.getByName(s)
        assertTrue(NetCheck.bogus(ip("10.10.34.35")))
        assertTrue(NetCheck.bogus(ip("127.0.0.1")))
        assertTrue(NetCheck.bogus(ip("0.0.0.0")))
        assertTrue(NetCheck.bogus(ip("192.168.1.1")))
        assertTrue(!NetCheck.bogus(ip("142.250.1.1")))
    }

    @Test fun verdict() {
        val t = NetCheck.TARGETS
        val onlyLocal = NetUi(done = true, targets = t.map { NetCheck.TargetResult(it, if (it.domestic) 10 else null, 1) })
        assertTrue(netVerdict(onlyLocal).any { it.first == 2 && it.second.contains("داخلی") })
        val udpBlocked = NetUi(done = true, udp = listOf(UdpRow("a", null, null, 100.0)))
        assertTrue(netVerdict(udpBlocked).any { it.second.contains("UDP") })
        val fine = NetUi(done = true, targets = t.map { NetCheck.TargetResult(it, 20, 1) })
        assertEquals(listOf(0 to "اینترنت گوشی سالم است"), netVerdict(fine))
    }
}

/** با شبکه‌ی واقعی (فقط KANKI_LIVE=1): meta، پینگ و یک دانلود/آپلود کوتاه با Cloudflare */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SpeedLiveTest {
    @Test fun live() {
        if (System.getenv("KANKI_LIVE") != "1") return
        val m = SpeedEngine.meta()
        println("META ip=${m?.ip} isp=${m?.isp} asn=${m?.asn} city=${m?.city} cc=${m?.country} colo=${m?.colo}")
        val s = SpeedEngine.CLOUDFLARE
        val l = SpeedEngine.latency(s, 8)
        println("LAT ping=${l?.ping} jitter=${l?.jitter} n=${l?.samples?.size}")
        val d = SpeedEngine.download(s, 4.0, 4) {}
        println("DOWN ${d.mbps} peak=${d.peak} bytes=${d.bytes} pts=${d.points.size} loaded=${d.loaded}")
        val u = SpeedEngine.upload(s, 4.0, 3) {}
        println("UP ${u.mbps} peak=${u.peak} bytes=${u.bytes} loaded=${u.loaded}")
        val ok = SpeedEngine.ooklaServers(5)
        println("OOKLA ${ok.map { it.base }}")
        println("TIMING ${NetCheck.timing(null)}")
        println("SITE ${NetCheck.site(null, NetCheck.Site("Google", "www.google.com"))}")
        println("PING ${NetCheck.tcpPing(null, NetCheck.TARGETS[1], 3)}")
    }
}
