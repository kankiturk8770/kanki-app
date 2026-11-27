package cafe.kankipro.vpn

import java.io.IOException
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Requests to the panel (subscription page and configs: they carry the subscription code and tunnel keys).
 *
 *  - HTTPS only: any other URL is refused before a socket is opened.
 *  - The certificate must chain to a system CA (network_security_config.xml: no user CAs) and name the host
 *    (the platform's hostname verifier, set explicitly so no library default can replace it).
 *  - When the build carries pins (KANKI_PANEL_PINS), one certificate of the chain must have one of those public
 *    keys too; this is checked inside the TLS handshake, before a byte of the request is sent.
 *  - Redirects are followed by the caller only on the same https origin (SubscriptionApi), never automatically.
 *
 * 1.45.1 (the reason 1.45.0 fetched nothing): the trust manager given to the TLS context was a plain X509TrustManager
 * calling the system's two-argument checkServerTrusted. Android's network-security-config trust manager refuses that
 * call whenever the config has a <domain-config> ("Domain specific configurations require that hostname aware
 * checkServerTrusted(X509Certificate[], String, String) is used"), and ours has one (the cleartext probes), so every
 * panel handshake failed. Now the wrapper is an X509ExtendedTrustManager that hands the socket (and with it the host)
 * to the system's hostname-aware check, and without pins the platform's own socket factory is used unchanged.
 */
object PanelTls {
    val pins: Set<String> = BuildConfig.PANEL_PINS.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** the system's trust manager (on a phone the network-security-config one: system CAs only) */
    private fun system(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw IOException("no system trust manager")
    }

    /** null = no pins: the platform's own factory (the network-security-config path, untouched) */
    private val factory: SSLSocketFactory? by lazy {
        if (pins.isEmpty()) return@lazy null
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(Pinned(system(), pins)), null)
        ctx.socketFactory
    }

    fun open(url: String): HttpsURLConnection {
        val u = URL(url)
        if (!u.protocol.equals("https", ignoreCase = true) || u.host.isNullOrBlank() || u.userInfo != null) {
            throw IOException("panel requests must be https")
        }
        val c = u.openConnection() as? HttpsURLConnection ?: throw IOException("panel requests must be https")
        factory?.let { c.sslSocketFactory = it }
        c.hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        c.instanceFollowRedirects = false
        return c
    }

    /** base64 SHA-256 of a certificate's public key (SubjectPublicKeyInfo), the form of the pins */
    fun spki(c: X509Certificate): String =
        java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(c.publicKey.encoded))

    /**
     * The system's check (hostname aware: the socket or engine names the host), then, when there are pins, one key of
     * the chain must be pinned. The two-argument form is only reached through a socket that names no host.
     */
    class Pinned(private val sys: X509TrustManager, private val pins: Set<String>) : X509ExtendedTrustManager() {
        private val ext = sys as? X509ExtendedTrustManager

        private fun pinned(chain: Array<out X509Certificate>?) {
            if (pins.isEmpty()) return
            if (chain.isNullOrEmpty() || chain.none { spki(it) in pins }) throw CertificateException("panel certificate is not pinned")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            if (ext != null) ext.checkServerTrusted(chain, authType, socket) else sys.checkServerTrusted(chain, authType)
            pinned(chain)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
            if (ext != null) ext.checkServerTrusted(chain, authType, engine) else sys.checkServerTrusted(chain, authType)
            pinned(chain)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            sys.checkServerTrusted(chain, authType)
            pinned(chain)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
            throw CertificateException("no client certificates")

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
            throw CertificateException("no client certificates")

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("no client certificates")

        override fun getAcceptedIssuers(): Array<X509Certificate> = sys.acceptedIssuers
    }
}
