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
 * موتور امنزیا/وایرگارد از این استفاده نمی‌کند؛ فقط Hysteria2، Xray، حالت DNS کانکی و «فقط بازی» گیمینگ
 * (KANKI MIX و microtun، که هسته‌شان به‌جای کارت شبکه سر دیگر همین جفت سوکت را می‌گیرد).
 *
 * بی‌اثر روی پینگ بازی:
 *   - صاحب هر اتصال فقط بار اول پرسیده می‌شود و در حافظه می‌ماند (بسته‌های بعدی بازی فقط یک جست‌وجوی جدول‌اند)؛
 *     اتصالی که صاحبش معلوم نشد تا ۲۵۰ میلی‌ثانیه دوباره پرسیده نمی‌شود.
 *   - بسته‌ی برنامه‌ی بسته‌شده همان‌جا دور ریخته می‌شود و به هسته نمی‌رسد.
 *   - reject: به TCP برنامه‌ی بسته‌شده (فقط وقتی صاحبش معلوم است) فوری RST و به UDP آن «پورت در دسترس نیست» (ICMP) برمی‌گردد، تا برنامه
 *     بفهمد اینترنت ندارد و پشت سر هم دوباره نفرستد (پردازنده و صف کارت شبکه برای بازی خالی می‌ماند).
 *   - دو رشته‌ی دروازه اولویت بالا دارند.
 */
class AppGate(
    private val svc: VpnService,
    private val tun: ParcelFileDescriptor,
    allowedUids: Set<Int>,
    /** حالت DNS کانکی: سؤال‌های DNS به آدرس ساختگی همین‌جا جواب داده می‌شوند */
    private val dns: KankiDns.Forwarder? = null,
    /** به بسته‌های برنامه‌های بسته‌شده جواب «اینترنت نیست» داده شود (RST / ICMP) */
    private val reject: Boolean = false,
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

    /** سر دیگر جفت سوکت؛ به‌جای کارت شبکه به hev (یا هسته‌ی KANKI MIX / microtun) داده می‌شود */
    val innerFd: Int get() = pair[1].fd
    val inner: ParcelFileDescriptor get() = pair[1]

    private val cm: ConnectivityManager? = svc.getSystemService(ConnectivityManager::class.java)
    private val tcpOwners = ConcurrentHashMap<Long, Int>()
    private val udpOwners = ConcurrentHashMap<Long, Int>()
    /** اتصال‌هایی که صاحبشان معلوم نشد ← زمانی که دوباره پرسیده شوند */
    private val unknown = ConcurrentHashMap<Long, Long>()
    private val tunOut = FileOutputStream(tun.fileDescriptor)
    @Volatile private var running = true

    fun start() {
        Thread({ urgent(); outbound() }, "kanki-gate-out").start()
        Thread({ urgent(); inbound() }, "kanki-gate-in").start()
    }

    private fun urgent() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY) }
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
            if (!pass(buf, n)) {
                // فقط برنامه‌ای که صاحبش معلوم است و مجاز نیست جواب «اینترنت نیست» می‌گیرد؛ اتصالی که صاحبش
                // (لحظه‌ای) معلوم نشد بی‌صدا دور ریخته می‌شود، تا بازی با یک جواب اشتباه قطع نشود
                if (reject && refusable) runCatching { refuse(buf, n) }
                continue
            }
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

    /** آخرین بسته‌ی ردشده مال برنامه‌ای بود که صاحبش معلوم است (فقط روی رشته‌ی خروجی خوانده و نوشته می‌شود) */
    private var refusable = false

    /** این بسته از برنامه‌ی مجاز است؟ */
    private fun pass(p: ByteArray, n: Int): Boolean {
        refusable = false
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
        val uid = map[key] ?: run {
            val now = System.currentTimeMillis()
            val retry = unknown[key]
            if (retry != null && now < retry) return false
            owner(proto, p, sport, dport).also { u ->
                if (u >= 0) {
                    if (map.size > 4096) map.clear()
                    map[key] = u
                    unknown.remove(key)
                } else {
                    if (unknown.size > 4096) unknown.clear()
                    unknown[key] = now + 250
                }
            }
        }
        // سرویس‌های خود اندروید (uid زیر ۱۰۰۰۰: شبکه، زمان، دانلود سیستم) همیشه رد می‌شوند
        val ok = uid in allowed || (uid in 0 until android.os.Process.FIRST_APPLICATION_UID)
        refusable = !ok && uid >= 0
        return ok
    }

    /** جمع متمم یک (چک‌سام IP، TCP و ICMP) */
    private fun sum(p: ByteArray, from: Int, len: Int, start: Long = 0): Long {
        var s = start
        var i = from
        while (i + 1 < from + len) { s += (b(p, i) shl 8 or b(p, i + 1)).toLong(); i += 2 }
        if (i < from + len) s += (b(p, i) shl 8).toLong()
        return s
    }

    private fun fold(s0: Long): Int {
        var s = s0
        while (s shr 16 != 0L) s = (s and 0xffff) + (s shr 16)
        return (s.inv() and 0xffff).toInt()
    }

    private fun put16(p: ByteArray, i: Int, v: Int) { p[i] = (v shr 8).toByte(); p[i + 1] = v.toByte() }
    private fun put32(p: ByteArray, i: Int, v: Long) { put16(p, i, (v shr 16).toInt() and 0xffff); put16(p, i + 2, (v and 0xffff).toInt()) }
    private fun get32(p: ByteArray, i: Int): Long = (b(p, i).toLong() shl 24) or (b(p, i + 1).toLong() shl 16) or (b(p, i + 2).toLong() shl 8) or b(p, i + 3).toLong()

    /** سرآیند IPv4 بسته‌ی جواب: مبدأ و مقصد جابه‌جا */
    private fun ipHeader(out: ByteArray, orig: ByteArray, proto: Int) {
        out[0] = 0x45
        put16(out, 2, out.size)
        out[6] = 0x40          // تکه نشود
        out[8] = 64
        out[9] = proto.toByte()
        System.arraycopy(orig, 16, out, 12, 4)
        System.arraycopy(orig, 12, out, 16, 4)
        put16(out, 10, fold(sum(out, 0, 20)))
    }

    /** جواب «اینترنت نیست» به بسته‌ی برنامه‌ی بسته‌شده: RST برای TCP، ICMP «پورت در دسترس نیست» برای UDP */
    private fun refuse(p: ByteArray, n: Int) {
        if (n < 20 || (b(p, 0) shr 4) != 4) return
        val hl = (b(p, 0) and 0xf) * 4
        val out: ByteArray = when (b(p, 9)) {
            6 -> {
                if (n < hl + 20) return
                val flags = b(p, hl + 13)
                if (flags and 0x04 != 0) return                     // به RST جواب داده نمی‌شود
                val dataOff = (b(p, hl + 12) shr 4) * 4
                val total = (b(p, 2) shl 8) or b(p, 3)
                val segLen = (total - hl - dataOff).coerceAtLeast(0) + (if (flags and 0x02 != 0) 1 else 0) + (if (flags and 0x01 != 0) 1 else 0)
                val o = ByteArray(40)
                ipHeader(o, p, 6)
                System.arraycopy(p, hl + 2, o, 20, 2)               // پورت مبدأ = پورت مقصد بسته
                System.arraycopy(p, hl, o, 22, 2)
                if (flags and 0x10 != 0) {
                    put32(o, 24, get32(p, hl + 8))                  // seq = ack بسته
                    o[33] = 0x04                                     // RST
                } else {
                    put32(o, 28, (get32(p, hl + 4) + segLen) and 0xffffffffL)
                    o[33] = 0x14                                     // RST + ACK
                }
                o[32] = 0x50
                val pseudo = sum(o, 12, 8) + 6 + 20
                put16(o, 36, fold(sum(o, 20, 20, pseudo)))
                o
            }
            17 -> {
                val copy = minOf(n, hl + 8)
                val o = ByteArray(28 + copy)
                ipHeader(o, p, 1)
                o[20] = 3; o[21] = 3                                 // مقصد در دسترس نیست / پورت
                System.arraycopy(p, 0, o, 28, copy)
                put16(o, 22, fold(sum(o, 20, 8 + copy)))
                o
            }
            else -> return
        }
        synchronized(tunOut) { tunOut.write(out) }
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
