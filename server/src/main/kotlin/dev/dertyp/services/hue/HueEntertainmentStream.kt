package dev.dertyp.services.hue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsPSKIdentity
import org.bouncycastle.tls.UDPTransport
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

interface HueEntertainmentStream {
    suspend fun start()

    fun send(frame: ByteArray)

    fun close()
}

class HueDtlsStream(
    private val ip: String,
    private val applicationKey: String,
    private val clientKey: String,
    private val port: Int = PORT,
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
) : HueEntertainmentStream {
    private val lock = Any()
    private var socket: DatagramSocket? = null
    private var dtls: DTLSTransport? = null
    private var closed = false

    override suspend fun start() {
        val identity = BasicTlsPSKIdentity(applicationKey.toByteArray(Charsets.US_ASCII), pskBytes(clientKey))
        withContext(Dispatchers.IO) {
            val udp = openSocket()
            val timedOut = AtomicBoolean(false)
            val watchdog = launch {
                delay(handshakeTimeoutMs)
                timedOut.set(true)
                udp.close()
            }
            try {
                val transport = withTimeout(handshakeTimeoutMs + TIMEOUT_GRACE_MS) {
                    runInterruptible { DTLSClientProtocol().connect(PskClient(identity), UDPTransport(udp, MTU)) }
                }
                val keep = synchronized(lock) {
                    if (closed) {
                        false
                    } else {
                        dtls = transport
                        true
                    }
                }
                if (!keep) closeQuietly(transport)
            } catch (_: TimeoutCancellationException) {
                close()
                throw HueBridgeException(failure("timed out after $handshakeTimeoutMs ms"))
            } catch (e: IOException) {
                close()
                throw HueBridgeException(failure(if (timedOut.get()) "timed out after $handshakeTimeoutMs ms" else describe(e)))
            } finally {
                watchdog.cancel()
            }
        }
    }

    override fun send(frame: ByteArray) = synchronized(lock) {
        val transport = dtls
        check(transport != null && !closed) { "Entertainment stream for $ip is not started" }
        transport.send(frame, 0, frame.size)
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            dtls?.let { closeQuietly(it) }
            dtls = null
            socket?.close()
            socket = null
        }
    }

    private fun openSocket(): DatagramSocket {
        val udp = try {
            val address = InetSocketAddress(ip, port)
            DatagramSocket().apply {
                soTimeout = SOCKET_TIMEOUT_MS
                connect(address)
            }
        } catch (e: IOException) {
            throw HueBridgeException(failure(describe(e)))
        }
        synchronized(lock) {
            if (closed) {
                udp.close()
                throw HueBridgeException(failure("the stream was already closed"))
            }
            socket = udp
        }
        return udp
    }

    private fun failure(reason: String) = "Entertainment handshake with $ip failed: $reason"

    private fun describe(error: IOException) = error.message ?: error::class.simpleName.orEmpty()

    private fun closeQuietly(transport: DTLSTransport) {
        runCatching { transport.close() }
    }

    private class PskClient(identity: TlsPSKIdentity) : PSKTlsClient(BcTlsCrypto(SecureRandom()), identity) {
        override fun getSupportedCipherSuites(): IntArray = intArrayOf(CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256)

        override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.DTLSv12.only()
    }

    companion object {
        const val PORT = 2100
        const val HANDSHAKE_TIMEOUT_MS = 8_000L
        const val MTU = 1_500
        private const val SOCKET_TIMEOUT_MS = 1_000
        private const val TIMEOUT_GRACE_MS = 500L
        private const val CLIENT_KEY_LENGTH = 32
        private const val INVALID_KEY = "Bridge client key is invalid, re-pair the bridge"

        fun pskBytes(clientKey: String): ByteArray {
            if (clientKey.length != CLIENT_KEY_LENGTH) throw HueBridgeException(INVALID_KEY)
            return ByteArray(CLIENT_KEY_LENGTH / 2) { index ->
                val high = clientKey[index * 2].digitToIntOrNull(16) ?: throw HueBridgeException(INVALID_KEY)
                val low = clientKey[index * 2 + 1].digitToIntOrNull(16) ?: throw HueBridgeException(INVALID_KEY)
                ((high shl 4) or low).toByte()
            }
        }
    }
}
