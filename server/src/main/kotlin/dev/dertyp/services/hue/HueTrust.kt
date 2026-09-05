package dev.dertyp.services.hue

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

object HueTrust {
    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02x".format(it) }

    class PinnedTrustManager(
        private val expectedFingerprint: String?,
        private val onFirstUse: (String) -> Unit,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("Client certificates are not accepted")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("Empty certificate chain")
            val actual = fingerprint(leaf)
            if (expectedFingerprint == null) {
                onFirstUse(actual)
                return
            }
            if (!expectedFingerprint.equals(actual, ignoreCase = true)) {
                throw CertificateException("Bridge certificate changed, re-pair the bridge")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun sslContext(trustManager: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }

    fun hostnameVerifier(bridgeId: String?): HostnameVerifier = HostnameVerifier { _, session ->
        if (bridgeId == null) return@HostnameVerifier true
        val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
            ?: return@HostnameVerifier false
        val subject = leaf.subjectX500Principal.name
        subject.split(",").any { part ->
            val (key, value) = part.trim().split("=", limit = 2).takeIf { it.size == 2 } ?: return@any false
            key.equals("CN", ignoreCase = true) && value.equals(bridgeId, ignoreCase = true)
        }
    }
}
