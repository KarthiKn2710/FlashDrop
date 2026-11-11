package com.flashdrop.connectivity.tls

import com.flashdrop.core.security.DeviceIdentity
import com.flashdrop.core.security.Fingerprints
import com.flashdrop.protocol.FdtpLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.SocketException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Mutually authenticated TLS 1.3 over TCP, shared by every socket-based transport (LAN,
 * Wi-Fi Direct). Both ends present their Keystore identity; FDTP pairing authenticates the keys.
 */
@Singleton
class TlsEndpoint @Inject constructor(private val identity: DeviceIdentity) {

    suspend fun connect(host: String, port: Int): FdtpLink = withContext(Dispatchers.IO) {
        val socket = identity.sslContext().socketFactory.createSocket() as SSLSocket
        try {
            socket.enabledProtocols = TLS_PROTOCOLS
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.startHandshake()
            socket.soTimeout = READ_TIMEOUT_MS
            TlsLink(socket, identity.fingerprint)
        } catch (e: Exception) {
            closeQuietly(socket)
            throw if (e is IOException) e else IOException(e.message ?: "Connection failed", e)
        }
    }

    /** Opens a listening socket on an ephemeral port, on all interfaces. */
    fun openServer(): SSLServerSocket {
        val server = identity.sslContext().serverSocketFactory.createServerSocket(0) as SSLServerSocket
        server.enabledProtocols = TLS_PROTOCOLS
        server.needClientAuth = true
        return server
    }

    /**
     * Accepts connections until [server] is closed. [onLink] returns false when the link could
     * not be handed over, in which case it is closed here.
     */
    suspend fun acceptLoop(server: SSLServerSocket, onLink: (FdtpLink) -> Boolean) = coroutineScope {
        withContext(Dispatchers.IO) {
            try {
                while (true) {
                    val socket = server.accept() as SSLSocket
                    // Handshake off the accept loop so one slow client cannot block the others.
                    launch(Dispatchers.IO) {
                        try {
                            socket.tcpNoDelay = true
                            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                            socket.startHandshake()
                            socket.soTimeout = READ_TIMEOUT_MS
                            val link = TlsLink(socket, identity.fingerprint)
                            if (!onLink(link)) link.close()
                        } catch (e: Exception) {
                            closeQuietly(socket)
                        }
                    }
                }
            } catch (e: SocketException) {
                // server socket closed: stop accepting
            }
        }
    }

    private fun closeQuietly(socket: SSLSocket) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private class TlsLink(private val socket: SSLSocket, override val localFingerprint: ByteArray) : FdtpLink {
        override val peerFingerprint: ByteArray = Fingerprints.of(socket.session.peerCertificates[0])
        override val input: InputStream = BufferedInputStream(socket.inputStream, 64 * 1024)
        override val output: OutputStream = socket.outputStream

        init {
            if (socket.session.protocol != "TLSv1.3") {
                socket.close()
                throw IOException("Insecure connection refused (${socket.session.protocol})")
            }
        }

        override fun close() {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
        val TLS_PROTOCOLS = arrayOf("TLSv1.3")
    }
}
