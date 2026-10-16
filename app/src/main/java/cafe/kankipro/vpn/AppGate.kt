package cafe.kankipro.vpn

import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/*
 * دروازه‌ی برنامه‌ها («فقط بازی‌ها / برنامه‌های انتخابی اینترنت داشته باشند»)
 * -----------------------------------------------------------------------------
 * همه‌ی برنامه‌ها وارد VPN می‌شوند. این کلاس هر بسته را از کارت شبکه‌ی VPN می‌خواند، صاحب اتصال را
 * با ConnectivityManager.getConnectionOwnerUid (اندروید ۱۰ به بالا) پیدا می‌کند و:
 *   برنامه‌ی مجاز  ← بسته به tun2socks (hev) می‌رود، مثل همیشه
 *   برنامه‌ی دیگر ← بسته دور ریخته می‌شود (آن برنامه اینترنت ندارد)
 * بین این کلاس و hev یک جفت سوکت SEQPACKET است که مرز بسته‌ها را مثل کارت شبکه نگه می‌دارد.
 * سؤال‌های DNS (پورت ۵۳/۸۵۳) همیشه رد می‌شوند، چون از طرف سرویس DNS خود اندروید فرستاده می‌شوند.
 * موتور امنزیا/وایرگارد از این استفاده نمی‌کند؛ فقط Hysteria2، Xray و حالت DNS کانکی.
 */
class AppGate(
    private val svc: VpnService,
    private val tun: ParcelFileDescriptor,
    allowedUids: Set<Int>,
    /** حالت DNS کانکی: سؤال‌های DNS به آدرس ساختگی همین‌جا جواب داده می‌شوند */
    private val dns: KankiDns.Forwarder? = null,
) {
    companion object {
        /** شناختن صاحب هر اتصال از اندروید ۱۰ ممکن است */
        val supported: Boolean get() = Build.VERSION.SDK_INT >= 29

        /** شناسه‌ی کاربری (uid) برنامه‌ها از روی اسم بسته */
        fun uidsOf(svc: VpnService, pkgs: Collection<String>): Set<Int> {
            val pm = svc.packageManager
            return pkgs.mapNotNull { p -> runCatching { pm.getApplicationInfo(p, 0).uid }.getOrNull() }.toSet()
        }
    }

    private val allowed: Set<Int> = allowedUids
    private val pair: Array<ParcelFileDescriptor> = ParcelFileDescriptor.createReliableSocketPair()

    /** سر دیگر جفت سوکت؛ به‌جای کارت شبکه به hev داده می‌شود */
    val innerFd: Int get() = pair[1].fd

    private val cm: ConnectivityManager? = svc.getSystemService(ConnectivityManager::class.java)
    private val tcpOwners = ConcurrentHashMap<Long, Int>()
    private val udpOwners = ConcurrentHashMap<Long, Int>()
    private val tunOut = FileOutputStream(tun.fileDescriptor)
    @Volatile private var running = true

    fun start() {
        Thread({ outbound() }, "kanki-gate-out").start()
        Thread({ inbound() }, "kanki-gate-in").start()
    }

    /** بعد از خاموش شدن hev صدا زده شود */
    fun stop() {
        running = false
        runCatching { pair[0].close() }
        runCatching { pair[1].close() }
    }

    /** برنامه‌ها ← اینترنت */
    private fun outbound() {
        val inp = FileInputStream(tun.fileDescriptor)
        val out = FileOutputStream(pair[0].fileDescriptor)
        val buf = ByteArray(65535)
        while (running) {
            val n = try { inp.read(buf) } catch (e: Exception) { break }
            if (n < 0) break
            if (n == 0) continue
            if (dns != null && dns.handle(buf, n)) continue
            if (!pass(buf, n)) continue
            try { out.write(buf, 0, n) } catch (e: Exception) { if (!running) break }
        }
    }

    /** اینترنت ← برنامه‌ها */
    private fun inbound() {
        val inp = FileInputStream(pair[0].fileDescriptor)
        val buf = ByteArray(65535)
        while (running) {
            val n = try { inp.read(buf) } catch (e: Exception) { break }
            if (n < 0) break
            if (n == 0) continue
            try { synchronized(tunOut) { tunOut.write(buf, 0, n) } } catch (e: Exception) { if (!running) break }
        }
    }

    private fun b(p: ByteArray, i: Int) = p[i].toInt() and 0xff

    /** این بسته از برنامه‌ی مجاز است؟ */
    private fun pass(p: ByteArray, n: Int): Boolean {
        if (n < 20 || (b(p, 0) shr 4) != 4) return false      // IPv6 روی این کارت آدرس ندارد
        val proto = b(p, 9)
        if (proto != 6 && proto != 17) return false
        // تکه‌های بعدی یک بسته‌ی بزرگ پورت ندارند؛ با MTU بزرگ کارت تقریباً پیش نمی‌آید
        if (((b(p, 6) and 0x1f) shl 8 or b(p, 7)) != 0) return false
        val hl = (b(p, 0) and 0xf) * 4
        if (n < hl + 4) return false
        val sport = (b(p, hl) shl 8) or b(p, hl + 1)
        val dport = (b(p, hl + 2) shl 8) or b(p, hl + 3)
        if (dport == 53 || dport == 853) return true
        val dst = (b(p, 16).toLong() shl 24) or (b(p, 17).toLong() shl 16) or (b(p, 18).toLong() shl 8) or b(p, 19).toLong()
        val key = (dst shl 32) or (dport.toLong() shl 16) or sport.toLong()
        val map = if (proto == 6) tcpOwners else udpOwners
        val uid = map[key] ?: owner(proto, p, sport, dport).also { u ->
            if (u >= 0) {
                if (map.size > 4096) map.clear()
                map[key] = u
            }
        }
        return uid in allowed
    }

    /** صاحب اتصال؛ -1 اگر معلوم نشد */
    private fun owner(proto: Int, p: ByteArray, sport: Int, dport: Int): Int {
        val c = cm ?: return -1
        return try {
            val src = InetSocketAddress(InetAddress.getByAddress(p.copyOfRange(12, 16)), sport)
            val dst = InetSocketAddress(InetAddress.getByAddress(p.copyOfRange(16, 20)), dport)
            if (Build.VERSION.SDK_INT >= 29) c.getConnectionOwnerUid(proto, src, dst) else -1
        } catch (e: Exception) {
            -1
        }
    }
}
