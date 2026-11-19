package cafe.kankipro.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/*
 * «اینترنت من» (۱.۳۵): بررسی اینترنت خود گوشی، بیرون از VPN.
 * وقتی VPN کانکی وصل است، همه‌ی سوکت‌ها و درخواست‌ها به شبکه‌ی واقعی (وای‌فای / دیتا) بسته می‌شوند
 * (Probe.underlying؛ اندروید این اجازه را فقط به صاحب VPN می‌دهد). پس عددها مال اینترنت خود کاربر است، نه سرور VPN.
 * فقط خواندن و سنجیدن است: چیزی روی گوشی عوض نمی‌شود و هیچ داده‌ای جز همین درخواست‌های سنجش فرستاده نمی‌شود.
 */
object NetCheck {
    /** اطلاعات شبکه‌ی گوشی (بی هیچ دسترسی تازه؛ فقط ACCESS_NETWORK_STATE که برنامه از قبل دارد) */
    data class Info(
        /** wifi، cell، eth یا other */
        val transport: String,
        val operator: String?,
        val validated: Boolean,
        val metered: Boolean,
        val privateDns: Boolean,
        val dnsServers: List<String>,
        val mtu: Int?,
        val ipv6: Boolean,
        val downKbps: Int?,
        val upKbps: Int?,
        val signal: Int?,
    )

    /** زمان‌بندی یک اتصال HTTPS از صفر: DNS، TCP، TLS و اولین بایت جواب (میلی‌ثانیه) */
    data class Timing(val dns: Long, val tcp: Long, val tls: Long, val ttfb: Long, val ip: String, val tlsVersion: String?)

    /** یک مقصد پینگ (TCP به پورت ۴۴۳: زمان دست‌دادن TCP همان رفت‌وبرگشت است) */
    data class Target(val name: String, val host: String, val domestic: Boolean)
    data class TargetResult(val target: Target, val ms: Long?, val jitter: Long?)

    /** یک سایت: باز است یا کجا گیر می‌کند */
    data class Site(val name: String, val host: String)
    /** ok باز، dns آدرس نادرست، tcp وصل نشد، tls دست‌دادن رمز شکست (یا گواهی نامعتبر)، cert گواهی نادرست */
    data class SiteResult(val site: Site, val status: String, val ms: Long?)

    val TARGETS = listOf(
        Target("Cloudflare", "1.1.1.1", false),
        Target("Google", "www.google.com", false),
        Target("GitHub", "github.com", false),
        Target("Aparat", "www.aparat.com", true),
        Target("Digikala", "www.digikala.com", true),
    )

    val SITES = listOf(
        Site("Google", "www.google.com"),
        Site("YouTube", "www.youtube.com"),
        Site("Instagram", "www.instagram.com"),
        Site("Telegram", "web.telegram.org"),
        Site("WhatsApp", "web.whatsapp.com"),
        Site("X", "x.com"),
        Site("GitHub", "github.com"),
        Site("Wikipedia", "www.wikipedia.org"),
        Site("Aparat", "www.aparat.com"),
        Site("Digikala", "www.digikala.com"),
    )

    /** شبکه‌ای که بررسی روی آن انجام می‌شود: بیرون از VPN اگر VPN بالاست، وگرنه null (همان مسیر عادی) */
    fun network(ctx: Context): Network? = Probe.underlying(ctx)

    fun info(ctx: Context, net: Network?): Info? = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val n = net ?: cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(n)
        val lp = cm.getLinkProperties(n)
        val transport = when {
            caps == null -> "other"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
            else -> "other"
        }
        val op = if (transport == "cell") runCatching {
            ctx.getSystemService(android.telephony.TelephonyManager::class.java)?.networkOperatorName?.trim()?.takeIf { it.isNotEmpty() }?.take(40)
        }.getOrNull() else null
        Info(
            transport = transport,
            operator = op,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
            privateDns = if (android.os.Build.VERSION.SDK_INT >= 28) lp?.isPrivateDnsActive == true else false,
            dnsServers = lp?.dnsServers?.mapNotNull { it.hostAddress }?.take(4) ?: emptyList(),
            mtu = if (android.os.Build.VERSION.SDK_INT >= 29) lp?.mtu?.takeIf { it > 0 } else null,
            ipv6 = lp?.linkAddresses?.any { it.address is Inet6Address && !it.address.isLinkLocalAddress } == true,
            downKbps = caps?.linkDownstreamBandwidthKbps?.takeIf { it > 0 },
            upKbps = caps?.linkUpstreamBandwidthKbps?.takeIf { it > 0 },
            signal = if (android.os.Build.VERSION.SDK_INT >= 29) caps?.signalStrength?.takeIf { it != Int.MIN_VALUE && it != 0 } else null,
        )
    }.getOrNull()

    /** آدرس با DNS همان شبکه (زمان را هم برمی‌گرداند) */
    fun resolve(net: Network?, host: String): Pair<List<InetAddress>, Long>? = runCatching {
        val t0 = System.nanoTime()
        val list = (if (net != null) runCatching { net.getAllByName(host).toList() }.getOrNull() else null) ?: InetAddress.getAllByName(host).toList()
        list to ((System.nanoTime() - t0) / 1_000_000L)
    }.getOrNull()

    /**
     * آدرسی که DNS نباید بدهد: IP خصوصی، 0.0.0.0، loopback یا 10.10.34.x (آدرس صفحه‌ی بسته شدن سایت‌ها در ایران).
     * اگر DNS برای یک سایت جهانی چنین چیزی داد، DNS دست‌کاری شده است.
     */
    fun bogus(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress) return true
        if (a is Inet4Address) {
            val b = a.address.map { it.toInt() and 0xff }
            if (b[0] == 10 || b[0] == 0 || (b[0] == 100 && b[1] in 64..127)) return true
        }
        return false
    }

    private fun socket(net: Network?): Socket = (net?.let { runCatching { it.socketFactory.createSocket() }.getOrNull() }) ?: Socket()

    /** TLS با بررسی کامل گواهی و نام (مثل مرورگر): گواهی جعلی یا نام نادرست = خطا */
    private fun tls(plain: Socket, host: String, timeoutMs: Int): SSLSocket {
        val f = SSLSocketFactory.getDefault() as SSLSocketFactory
        val s = f.createSocket(plain, host, 443, true) as SSLSocket
        s.soTimeout = timeoutMs
        s.sslParameters = s.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        s.startHandshake()
        return s
    }

    /** زمان‌بندی کامل یک درخواست HTTPS (DNS، TCP، TLS، اولین بایت) */
    fun timing(net: Network?, host: String = "speed.cloudflare.com", path: String = "/__down?bytes=0"): Timing? = runCatching {
        val (addrs, dns) = resolve(net, host) ?: return null
        val a = addrs.firstOrNull { it is Inet4Address } ?: addrs.first()
        socket(net).use { raw ->
            val t1 = System.nanoTime()
            raw.connect(InetSocketAddress(a, 443), 5000)
            val tcp = (System.nanoTime() - t1) / 1_000_000L
            val t2 = System.nanoTime()
            val s = tls(raw, host, 6000)
            val tlsMs = (System.nanoTime() - t2) / 1_000_000L
            s.use {
                val t3 = System.nanoTime()
                it.outputStream.write("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: KankiVPN\r\nConnection: close\r\n\r\n".toByteArray())
                it.outputStream.flush()
                if (it.inputStream.read() < 0) return null
                val ttfb = (System.nanoTime() - t3) / 1_000_000L
                Timing(dns, tcp, tlsMs, ttfb, a.hostAddress ?: "", it.session?.protocol)
            }
        }
    }.getOrNull()

    /** پینگ TCP به پورت ۴۴۳ (n بار، میانه و جیتر)؛ اتصال TCP بلافاصله بسته می‌شود */
    fun tcpPing(net: Network?, t: Target, n: Int = 5): TargetResult {
        val addr = resolve(net, t.host)?.first?.let { l -> l.firstOrNull { it is Inet4Address } ?: l.firstOrNull() }
            ?: return TargetResult(t, null, null)
        val r = ArrayList<Long>()
        repeat(n) {
            runCatching {
                socket(net).use { s ->
                    val t0 = System.nanoTime()
                    s.connect(InetSocketAddress(addr, 443), 2500)
                    r.add(((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1))
                }
            }
        }
        if (r.isEmpty()) return TargetResult(t, null, null)
        val med = r.sorted()[r.size / 2]
        val jit = if (r.size < 2) 0L else r.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average().toLong()
        return TargetResult(t, med, jit)
    }

    /** یک سایت را مثل مرورگر باز می‌کند (DNS، TCP، TLS با نام سایت) و می‌گوید کجا گیر کرد */
    fun site(net: Network?, s: Site): SiteResult {
        val t0 = System.nanoTime()
        val addrs = resolve(net, s.host)?.first ?: return SiteResult(s, "dns", null)
        if (addrs.isEmpty() || addrs.all { bogus(it) }) return SiteResult(s, "dns", null)
        val a = addrs.firstOrNull { it is Inet4Address && !bogus(it) } ?: addrs.first { !bogus(it) }
        val raw = socket(net)
        try {
            try { raw.connect(InetSocketAddress(a, 443), 4000) } catch (e: Exception) { return SiteResult(s, "tcp", null) }
            try {
                tls(raw, s.host, 5000).close()
            } catch (e: javax.net.ssl.SSLPeerUnverifiedException) {
                return SiteResult(s, "cert", null)
            } catch (e: javax.net.ssl.SSLHandshakeException) {
                // گواهی نامعتبر (کسی وسط راه جواب داده) یا دست‌دادن بسته شد
                val cert = generateSequence(e as Throwable) { it.cause }.any { it is java.security.cert.CertificateException }
                return SiteResult(s, if (cert) "cert" else "tls", null)
            } catch (e: Exception) {
                return SiteResult(s, "tls", null)
            }
            return SiteResult(s, "ok", (System.nanoTime() - t0) / 1_000_000L)
        } finally { runCatching { raw.close() } }
    }

    /** چند کار هم‌زمان (هر کدام یک رشته)، با سقف زمان */
    fun <A, B> parallel(items: List<A>, timeoutMs: Long, f: (A) -> B, fallback: (A) -> B): List<B> {
        val out = arrayOfNulls<Any?>(items.size)
        val threads = items.mapIndexed { i, a -> Thread { out[i] = runCatching { f(a) }.getOrNull() }.apply { isDaemon = true; start() } }
        val end = System.currentTimeMillis() + timeoutMs
        threads.forEach { it.join((end - System.currentTimeMillis()).coerceAtLeast(1)) }
        @Suppress("UNCHECKED_CAST")
        return items.indices.map { i -> (out[i] as B?) ?: fallback(items[i]) }
    }
}
