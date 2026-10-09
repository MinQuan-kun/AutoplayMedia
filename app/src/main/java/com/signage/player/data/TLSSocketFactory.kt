package com.signage.player.data

import android.os.Build
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * ==============================================================================
 * KÍCH HOẠT TLS 1.2 CHO ANDROID ĐỜI CŨ (Android 4.3 Jelly Bean, Android 4.4 KitKat)
 * ==============================================================================
 * Mặc định trên Android 4.3 & 4.4, hệ điều hành chỉ bật TLS 1.0 (đã bị khai tử).
 * Socket Factory này kích hoạt ép buộc TLS 1.2 & 1.1 để thiết bị tải được video từ:
 * Google Drive, Dropbox, Cloudflare, AWS S3, HTTPS Server,... mà không bị lỗi SSLHandshake.
 */
class TLSSocketFactory : SSLSocketFactory() {

    private val delegate: SSLSocketFactory

    init {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, null, null)
        delegate = sslContext.socketFactory
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        return enableTLSOnSocket(delegate.createSocket(s, host, port, autoClose))
    }

    override fun createSocket(host: String, port: Int): Socket {
        return enableTLSOnSocket(delegate.createSocket(host, port))
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        return enableTLSOnSocket(delegate.createSocket(host, port, localHost, localPort))
    }

    override fun createSocket(host: InetAddress, port: Int): Socket {
        return enableTLSOnSocket(delegate.createSocket(host, port))
    }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
        return enableTLSOnSocket(delegate.createSocket(address, port, localAddress, localPort))
    }

    private fun enableTLSOnSocket(socket: Socket): Socket {
        if (socket is SSLSocket) {
            socket.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.1", "TLSv1")
        }
        return socket
    }

    companion object {
        fun enableTls12OnPreLollipop(builder: OkHttpClient.Builder): OkHttpClient.Builder {
            if (Build.VERSION.SDK_INT in 16..21) {
                try {
                    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    trustManagerFactory.init(null as KeyStore?)
                    val trustManagers = trustManagerFactory.trustManagers
                    val x509TrustManager = trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager

                    val tlsSocketFactory = TLSSocketFactory()
                    if (x509TrustManager != null) {
                        builder.sslSocketFactory(tlsSocketFactory, x509TrustManager)
                    }

                    val cs = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(TlsVersion.TLS_1_2)
                        .build()
                    builder.connectionSpecs(listOf(cs, ConnectionSpec.CLEARTEXT))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return builder
        }
    }
}
