package cafe.kankipro.vpn

import android.content.Context
import android.content.Intent
import androidx.annotation.RequiresApi

/** اطلاعات IKEv2 که از راهنمای پنل خوانده می‌شود */
data class Ikev2Info(val server: String, val username: String?, val password: String?, val psk: String?, val identity: String?) {
    companion object {
        fun parse(t: String): Ikev2Info? {
            fun find(vararg keys: String): String? {
                for (line in t.lines()) {
                    val l = line.trim().trimStart('-', '*', '•', ' ')
                    val idx = l.indexOfFirst { it == ':' || it == '=' || it == '：' }
                    if (idx <= 0) continue
                    val k = l.substring(0, idx).trim().lowercase()
                    val v = l.substring(idx + 1).trim().trim('`', '"', '\'')
                    if (v.isEmpty() || v.startsWith("//")) continue
                    if (keys.any { k.contains(it) }) return v
                }
                return null
            }
            val server = find("server address", "server", "address", "host", "gateway", "remote", "سرور", "آدرس") ?: return null
            return Ikev2Info(
                server = server.substringBefore(" ").removePrefix("https://").removePrefix("http://").trimEnd('/'),
                username = find("username", "user", "login", "نام کاربری", "یوزر"),
                password = find("password", "pass", "رمز"),
                psk = find("psk", "pre-shared", "preshared", "secret", "کلید مشترک"),
                identity = find("identity", "ipsec identifier", "local id"),
            )
        }
    }
}

/** اتصال IKEv2 با موتور داخلی خود اندروید (اندروید ۱۱ به بالا) */
@RequiresApi(30)
object Ikev2Engine {
    private fun vm(ctx: Context) = ctx.getSystemService(android.net.VpnManager::class.java)

    /** پروفایل را ثبت می‌کند؛ اگر Intent برگرداند، باید کاربر یک بار تأیید کند */
    fun provision(ctx: Context, info: Ikev2Info, caPem: String?): Intent? {
        val identity = info.identity ?: info.username ?: "kanki"
        val b = android.net.Ikev2VpnProfile.Builder(info.server, identity)
        val cert = caPem?.takeIf { it.contains("BEGIN CERTIFICATE") }?.let {
            java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(it.byteInputStream()) as java.security.cert.X509Certificate
        }
        val user = info.username
        val pass = info.password
        val psk = info.psk
        when {
            user != null && pass != null -> b.setAuthUsernamePassword(user, pass, cert)
            psk != null -> b.setAuthPsk(psk.toByteArray())
            else -> throw IllegalStateException("IKEv2 auth not found")
        }
        b.setBypassable(false)
        b.setMaxMtu(1350)
        return vm(ctx).provisionVpnProfile(b.build())
    }

    fun start(ctx: Context) = vm(ctx).startProvisionedVpnProfile()
    fun stop(ctx: Context) { runCatching { vm(ctx).stopProvisionedVpnProfile() } }
}

/** برای حالت گیمینگ: نگه‌داشتن اتصال NAT برای پینگ پایدار */
fun applyKeepalive(cfg: String, sec: Int = 15): String {
    if (cfg.lines().any { it.trim().startsWith("PersistentKeepalive", ignoreCase = true) }) return cfg
    val lines = cfg.lines().toMutableList()
    val i = lines.indexOfFirst { it.trim().equals("[Peer]", ignoreCase = true) }
    if (i >= 0) lines.add(i + 1, "PersistentKeepalive = $sec")
    return lines.joinToString("\n")
}
