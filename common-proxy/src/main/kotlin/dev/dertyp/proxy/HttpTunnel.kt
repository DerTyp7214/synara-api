package dev.dertyp.proxy

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

object HttpTunnel {
    const val BODY_CHUNK_SIZE = 65536
    const val INITIAL_WINDOW = 262144
    const val WINDOW_GRANT_THRESHOLD = 65536
    const val MAX_HEAD_SIZE = 65536
    const val MAX_CONCURRENT_STREAMS = 128
    const val CAPABILITY_PARAMETER = "http"
    const val CAPABILITY_VALUE = "1"
}

private val hopByHopHeaders = setOf(
    HttpHeaders.Connection,
    "Keep-Alive",
    "Proxy-Connection",
    HttpHeaders.ProxyAuthenticate,
    HttpHeaders.ProxyAuthorization,
    HttpHeaders.TE,
    HttpHeaders.Trailer,
    HttpHeaders.TransferEncoding,
    HttpHeaders.Upgrade
).map { it.lowercase() }.toSet()

private val requestOnlyHeaders = setOf(
    HttpHeaders.Expect,
    HttpHeaders.HTTP2Settings,
    HttpHeaders.Host
).map { it.lowercase() }.toSet()

fun Headers.withoutRequestHopByHop(): Headers = without(hopByHopHeaders + requestOnlyHeaders)

fun Headers.withoutResponseHopByHop(): Headers = without(hopByHopHeaders)

private fun Headers.without(names: Set<String>): Headers {
    val dropped = names + getAll(HttpHeaders.Connection).orEmpty()
        .flatMap { it.split(',') }
        .map { it.trim().lowercase() }
    return Headers.build {
        this@without.forEach { name, values ->
            if (name.lowercase() !in dropped) appendAll(name, values)
        }
    }
}

fun ProxyMessage.HttpRequest.hasEncodableMethod(): Boolean = ProxyMessage.isMethodEncodable(method)

fun ProxyMessage.HttpRequest.headSize(): Int = ProxyMessage.requestHeadSize(method, uri, headers)

fun ProxyMessage.HttpResponse.headSize(): Int = ProxyMessage.responseHeadSize(headers)

class HttpWindowClosedException : IllegalStateException("HTTP tunnel send window is closed")

class HttpSendWindow {
    private val credit = MutableStateFlow(HttpTunnel.INITIAL_WINDOW.toLong())

    suspend fun acquire(max: Int): Int {
        require(max > 0) { "Requested credit $max is not positive" }
        while (true) {
            val current = credit.first { it != 0L }
            if (current == CLOSED) throw HttpWindowClosedException()
            val taken = minOf(max.toLong(), current)
            if (credit.compareAndSet(current, current - taken)) return taken.toInt()
        }
    }

    fun grant(increment: Long): Boolean {
        if (increment <= 0) return false
        while (true) {
            val current = credit.value
            if (current == CLOSED) return true
            if (increment > Int.MAX_VALUE - current) return false
            if (credit.compareAndSet(current, current + increment)) return true
        }
    }

    fun close() {
        credit.value = CLOSED
    }

    private companion object {
        const val CLOSED = -1L
    }
}

class HttpReceiveWindow {
    private var remaining = HttpTunnel.INITIAL_WINDOW.toLong()
    private var ungranted = 0L

    @Synchronized
    fun received(bytes: Int): Boolean {
        remaining -= bytes
        return remaining >= 0
    }

    @Synchronized
    fun delivered(bytes: Int): Long {
        ungranted += bytes
        if (ungranted < HttpTunnel.WINDOW_GRANT_THRESHOLD) return 0
        val increment = ungranted
        remaining += increment
        ungranted = 0
        return increment
    }
}
