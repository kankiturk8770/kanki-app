package cafe.kankipro.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/*
 * حالت DNS کانکی («رفع تحریم»)
 * ----------------------------
 * VPN نیست: فقط DNS گوشی عوض می‌شود. اسم سایت‌های تحریمی IP سرور کانکی را جواب می‌گیرند و سرور
 * اتصال‌شان را رد می‌کند؛ بقیه‌ی اینترنت مستقیم و با سرعت کامل خود گوشی می‌رود.
 *
 * سرور فقط به IPهای ثبت‌شده جواب می‌دهد، پس اول IP گوشی با آدرس ثبت (register) ثبت می‌شود و هر بار که
 * شبکه عوض شد (یا سرور REFUSED داد) دوباره ثبت می‌شود. اول ریلی داخل ایران امتحان می‌شود، اگر جواب
 * نداد سرور خارج.
 *
 * پشت دکمه‌ی اتصال یک VPN خیلی کوچک بالا می‌آید که فقط یک آدرس DNS ساختگی (10.111.0.53) را می‌گیرد:
 * سؤال‌های DNS به سرور کانکی فرستاده می‌شوند و هیچ ترافیک دیگری از VPN رد نمی‌شود.
 * راه دیگر بدون برنامه: Private DNS اندروید با آدرس اختصاصی کاربر (DoT).
 */
object KankiDns {
    const val FAKE = "10.111.0.53"
    private const val TUN_IP = "10.111.0.2"

    fun isDns(text: String) = text.lines().any { it.trim().equals("[KankiDNS]", ignoreCase = true) }

    /** ترتیب امتحان: ریلی‌ها (داخل ایران) اول، بعد خروجی‌ها */
    fun ordered(info: DnsInfo, preferId: String? = null): List<DnsServer> =
        info.servers.sortedWith(compareBy({ if (it.id == preferId) 0 else 1 }, { if (it.role == "relay") 0 else 1 }))

    /** متنی که موتور اتصال می‌گیرد: هر خط Server = <ip> <آدرس ثبت HTTP> */
    fun wrap(list: List<DnsServer>): String = buildString {
        append("[Interface]\n[KankiDNS]\n")
        list.filter { it.ip.isNotBlank() }.forEach { append("Server = ").append(it.ip).append(' ').append(it.registerHttp.ifBlank { "-" }).append('\n') }
    }

    /**
     * IP فعلی گوشی را روی یک سرور ثبت می‌کند؛ زمان پاسخ (میلی‌ثانیه) یا null.
     * protect: وقتی VPN بالاست، سوکت باید بیرون از VPN برود.
     */
    fun register(registerHttp: String, protect: ((Socket) -> Unit)? = null): Long? {
        if (!registerHttp.startsWith("http://")) return null
        val t0 = System.currentTimeMillis()
        return runCatching {
            val (st, _) = PlainHttp.request("GET", registerHttp, mapOf("User-Agent" to "KankiVPN-Android"), null, prepare = protect, timeoutMs = 6000)
            if (st in 200..299) System.currentTimeMillis() - t0 else null
        }.getOrNull()
    }

    /** آدرس Private DNS (DoT) سرور انتخابی */
    fun dotOf(info: DnsInfo?, id: String?): String? =
        info?.let { ordered(it, id).firstOrNull { s -> s.dot.isNotBlank() }?.dot }

    /** صفحه‌ی تنظیمات شبکه (اندروید راه مستقیمی به Private DNS نمی‌دهد) */
    fun openPrivateDnsSettings(ctx: Context) {
        val intents = listOf(
            Intent("android.settings.PRIVATE_DNS_SETTINGS"),
            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
            Intent(android.provider.Settings.ACTION_SETTINGS),
        )
        for (i in intents) {
            if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    // ------------------------------------------------------------------ the DNS-only VPN

    class Target(val ip: InetAddress, val register: String)

    /** شمارنده‌ها برای نمایش (دریافت، ارسال) */
    val rx = AtomicLong()
    val tx = AtomicLong()

    /** نام/آی‌پی سروری که الان به آن وصل است */
    @Volatile var activeIp: String? = null

    /**
     * کارت شبکه‌ی VPN را با یک مسیر (فقط DNS ساختگی) بالا می‌آورد و سؤال‌ها را جواب می‌دهد تا وقتی
     * کارت بسته شود. روی رشته‌ی پس‌زمینه صدا زده شود.
     */
    fun buildTun(svc: VpnService, excluded: List<String>): ParcelFileDescriptor {
        val b = svc.Builder()
            .setSession("Kanki · DNS")
            .setMtu(1500)
            .addAddress(TUN_IP, 32)
            .addRoute(FAKE, 32)
            .addDnsServer(FAKE)
        excluded.forEach { pkg -> runCatching { b.addDisallowedApplication(pkg) } }
        if (android.os.Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        return b.establish() ?: error("VPN permission is missing")
    }

    class Forwarder(private val svc: VpnService, private val tun: ParcelFileDescriptor, private val targets: List<Target>) {
        private val pool = Executors.newFixedThreadPool(16)
        private val cur = AtomicInteger(0)
        private val fails = AtomicInteger(0)
        @Volatile private var running = true
        private val out = FileOutputStream(tun.fileDescriptor)
        private val fake = InetAddress.getByName(FAKE).address

        fun stop() {
            running = false
            pool.shutdownNow()
        }

        /** سرور فعلی را دوباره ثبت می‌کند؛ اگر نشد، سرور بعدی */
        fun reregister() {
            for (k in targets.indices) {
                val i = (cur.get() + k) % targets.size
                if (register(targets[i].register) { svc.protect(it) } != null || targets[i].register == "-") {
                    cur.set(i)
                    activeIp = targets[i].ip.hostAddress
                    fails.set(0)
                    return
                }
            }
        }

        fun run() {
            val inp = FileInputStream(tun.fileDescriptor)
            val buf = ByteArray(32767)
            while (running) {
                val n = try { inp.read(buf) } catch (e: Exception) { break }
                if (n <= 28) continue
                val pkt = buf.copyOf(n)
                val v = (pkt[0].toInt() shr 4) and 0xf
                if (v != 4 || pkt[9].toInt() != 17) continue
                val ihl = (pkt[0].toInt() and 0xf) * 4
                if (n < ihl + 8 || !(pkt[16] == fake[0] && pkt[17] == fake[1] && pkt[18] == fake[2] && pkt[19] == fake[3])) continue
                val dport = ((pkt[ihl + 2].toInt() and 0xff) shl 8) or (pkt[ihl + 3].toInt() and 0xff)
                if (dport != 53) continue
                val sport = ((pkt[ihl].toInt() and 0xff) shl 8) or (pkt[ihl + 1].toInt() and 0xff)
                val total = ((pkt[2].toInt() and 0xff) shl 8) or (pkt[3].toInt() and 0xff)
                val end = minOf(total, n)
                if (end <= ihl + 8) continue
                val query = pkt.copyOfRange(ihl + 8, end)
                val src = pkt.copyOfRange(12, 16)
                tx.addAndGet(query.size.toLong())
                runCatching { pool.execute { answer(query, src, sport) } }
            }
        }

        private fun ask(t: Target, q: ByteArray): ByteArray? = runCatching {
            DatagramSocket().use { s ->
                svc.protect(s)
                s.soTimeout = 2500
                s.send(DatagramPacket(q, q.size, t.ip, 53))
                val rb = ByteArray(4096)
                val p = DatagramPacket(rb, rb.size)
                var res: ByteArray? = null
                // only the answer to this question (same id)
                while (res == null) {
                    s.receive(p)
                    if (p.length >= 12 && rb[0] == q[0] && rb[1] == q[1]) res = rb.copyOf(p.length)
                }
                res
            }
        }.getOrNull()

        private fun answer(q: ByteArray, src: ByteArray, sport: Int) {
            var r = ask(targets[cur.get()], q)
            // REFUSED (5): the server does not know this address yet (new network): register and ask again
            if (r != null && (r[3].toInt() and 0xf) == 5) {
                reregister()
                r = ask(targets[cur.get()], q)
            }
            if (r == null) {
                if (fails.incrementAndGet() >= 3) reregister()
                r = targets.indices.filter { it != cur.get() }.firstNotNullOfOrNull { ask(targets[it], q) } ?: return
            } else fails.set(0)
            rx.addAndGet(r.size.toLong())
            val pkt = udpPacket(fake, src, 53, sport, r)
            synchronized(out) { runCatching { out.write(pkt) } }
        }

        private fun udpPacket(from: ByteArray, to: ByteArray, sp: Int, dp: Int, data: ByteArray): ByteArray {
            val len = 28 + data.size
            val p = ByteArray(len)
            p[0] = 0x45; p[2] = (len shr 8).toByte(); p[3] = len.toByte()
            p[6] = 0x40; p[8] = 64; p[9] = 17
            System.arraycopy(from, 0, p, 12, 4); System.arraycopy(to, 0, p, 16, 4)
            var sum = 0
            for (i in 0 until 20 step 2) sum += ((p[i].toInt() and 0xff) shl 8) or (p[i + 1].toInt() and 0xff)
            while (sum shr 16 != 0) sum = (sum and 0xffff) + (sum shr 16)
            val ck = sum.inv() and 0xffff
            p[10] = (ck shr 8).toByte(); p[11] = ck.toByte()
            p[20] = (sp shr 8).toByte(); p[21] = sp.toByte(); p[22] = (dp shr 8).toByte(); p[23] = dp.toByte()
            val ul = 8 + data.size
            p[24] = (ul shr 8).toByte(); p[25] = ul.toByte()
            // UDP checksum 0 = none (allowed on IPv4)
            System.arraycopy(data, 0, p, 28, data.size)
            return p
        }
    }
}
