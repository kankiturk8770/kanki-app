package cafe.kankipro.vpn

import java.io.IOException
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Requests to the panel (subscription page and configs: they carry the subscription code and tunnel keys).
 *
 *  - HTTPS only: any other URL is refused before a socket is opened.
 *  - The certificate must chain to a system CA (network_security_config.xml: no user CAs) and name the host
 *    (the platform's hostname verifier, set explicitly so no library default can replace it).
 *  - When the build carries pins (KANKI_PANEL_PINS), one certificate of the chain must have one of those public
 *    keys too; this is checked inside the TLS handshake, before a byte of the request is sent.
 *  - Redirects are not followed (a redirect could take the code to another host).
 */
object PanelTls {
    val pins: Set<String> = BuildConfig.PANEL_PINS.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private val factory: SSLSocketFactory by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val sys = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(Pinned(sys, pins)), null)
        ctx.socketFactory
    }

    fun open(url: String): HttpsURLConnection {
        val u = URL(url)
        if (!u.protocol.equals("https", ignoreCase = true) || u.host.isNullOrBlank() || u.userInfo != null) {
            throw IOException("panel requests must be https")
        }
        val c = u.openConnection() as? HttpsURLConnection ?: throw IOException("panel requests must be https")
        c.sslSocketFactory = factory
        c.hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        c.instanceFollowRedirects = false
        return c
    }

    /** base64 SHA-256 of a certificate's public key (SubjectPublicKeyInfo), the form of the pins */
    fun spki(c: X509Certificate): String =
        java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(c.publicKey.encoded))

    /** the system's check, then (when there are pins) one key of the chain must be pinned */
    class Pinned(private val sys: X509TrustManager, private val pins: Set<String>) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = sys.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            sys.checkServerTrusted(chain, authType)
            if (pins.isEmpty()) return
            if (chain.isNullOrEmpty() || chain.none { spki(it) in pins }) throw CertificateException("panel certificate is not pinned")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = sys.acceptedIssuers
    }
}
