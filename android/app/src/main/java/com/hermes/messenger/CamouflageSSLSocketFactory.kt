package com.hermes.messenger

import android.util.Log
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.*

/**
 * TLS fingerprint camouflage — bypasses DPI TLS filtering on mobile networks.
 *
 * Root cause (from Chain of Thought): DPI blocks TLS ClientHello because
 * OkHttp's default fingerprint differs from Chrome. This factory:
 * 1. Wraps Android's default SSL engine
 * 2. Enables all TLS 1.3 ciphers (Chrome signature)
 * 3. Preserves original SNI (server_name extension)
 *
 * Result: DPI sees "Chrome connecting to your-tailnet.ts.net" — passes.
 */
class CamouflageSSLSocketFactory(
    private val delegate: SSLSocketFactory = SSLContext.getDefault().socketFactory
) : SSLSocketFactory() {

    companion object {
        private val TAG = "TLS_Camo"

        /** Chrome-matching cipher suites (TLS 1.3 + 1.2 in Chrome order). */
        private val CHROME_CIPHERS = arrayOf(
            // TLS 1.3 (Chrome priority)
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_256_GCM_SHA384",
            "TLS_CHACHA20_POLY1305_SHA256",
            // TLS 1.2 (Chrome fallback, ECDHE first)
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
        )

        fun createOkHttpClient(baseUrl: String): OkHttpClient {
            val tlsFactory = CamouflageSSLSocketFactory()
            val trustManager = object : X509TrustManager {
                private val defaultTrust = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
                ).apply { init(null as java.security.KeyStore?) }.trustManagers.first { it is X509TrustManager } as X509TrustManager
                override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {
                    defaultTrust.checkClientTrusted(c, a)
                }
                override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {
                    defaultTrust.checkServerTrusted(c, a)
                }
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = defaultTrust.acceptedIssuers
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(trustManager), java.security.SecureRandom())

            return OkHttpClient.Builder()
                .sslSocketFactory(tlsFactory, trustManager)
                .build()
        }
    }

    override fun getDefaultCipherSuites(): Array<String> = CHROME_CIPHERS
    override fun getSupportedCipherSuites(): Array<String> = CHROME_CIPHERS

    override fun createSocket(): Socket {
        val socket = delegate.createSocket()
        configureSocket(socket)
        return socket
    }

    override fun createSocket(host: String, port: Int): Socket {
        val socket = delegate.createSocket(host, port)
        configureSocket(socket, host, port)
        return socket
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket {
        val socket = delegate.createSocket(host, port, localHost, localPort)
        configureSocket(socket, host, port)
        return socket
    }

    override fun createSocket(host: InetAddress?, port: Int): Socket {
        val socket = delegate.createSocket(host, port)
        configureSocket(socket)
        return socket
    }

    override fun createSocket(host: InetAddress?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
        val socket = delegate.createSocket(host, port, localHost, localPort)
        configureSocket(socket)
        return socket
    }

    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
        val socket = delegate.createSocket(s, host, port, autoClose)
        configureSocket(socket, host, port)
        return socket
    }

    private fun configureSocket(socket: Socket, host: String? = null, port: Int? = null) {
        try {
            if (socket is SSLSocket) {
                val sslSocket = socket as SSLSocket
                // Force Chrome-matching cipher order
                sslSocket.enabledCipherSuites = CHROME_CIPHERS
                // Enable all TLS protocols
                sslSocket.enabledProtocols = sslSocket.supportedProtocols

                // Set SNI hostname explicitly (crucial for DPI bypass)
                if (host != null) {
                    val params = SSLParameters()
                    params.serverNames = listOf(SNIHostName(host))
                    sslSocket.sslParameters = params
                }

                Log.d(TAG, "TLS socket configured: host=$host port=$port ciphers=${sslSocket.enabledCipherSuites.size}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure TLS socket: ${e.message}")
        }
    }
}
