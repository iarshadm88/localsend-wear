package org.localsend.wear

import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

object Net {
    /**
     * LocalSend peers use self-signed certificates and identify each other by fingerprint, so
     * chain validation is skipped. NOTE: this v1 does not pin the peer's fingerprint after the
     * handshake, so it protects against passive eavesdropping only, not an active MITM.
     */
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun client(identity: Identity, connectTimeoutMs: Long = 5_000, readTimeoutMs: Long = 0): OkHttpClient {
        val ssl = SSLContext.getInstance("TLS").apply { init(identity.keyManagers(), arrayOf(trustAll), SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS) // 0 = wait for the remote user's decision
            .retryOnConnectionFailure(false)
            .build()
    }

    /** The device's local IPv4 addresses (site-local only), e.g. the Wi-Fi address. */
    fun localIpv4(): List<String> =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
}
