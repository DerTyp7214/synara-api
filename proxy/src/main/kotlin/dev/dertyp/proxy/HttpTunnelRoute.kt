package dev.dertyp.proxy

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.install
import io.ktor.server.http.HttpRequestLifecycle
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.route
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.availableForRead
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

private val logger = LoggerFactory.getLogger("ProxyHttp")

val reservedInstanceIds = setOf("proxy", "instances")

class HttpStreamFailedException(val status: HttpStatusCode) : IOException("HTTP tunnel stream failed")

class HttpStreams(private val outgoing: SendChannel<ProxyMessage>) {
    private val streams = HashMap<UUID, HttpStream>()
    private var closed = false

    @Synchronized
    fun open(id: UUID): HttpStream? {
        if (closed || streams.size >= HttpTunnel.MAX_CONCURRENT_STREAMS) return null
        return HttpStream(id, outgoing, this).also { streams[id] = it }
    }

    @Synchronized
    fun remove(id: UUID) {
        streams.remove(id)
    }

    @Synchronized
    private fun find(id: UUID): HttpStream? = streams[id]

    @Synchronized
    private fun drain(): List<HttpStream> {
        closed = true
        return streams.values.toList().also { streams.clear() }
    }

    fun dispatch(message: ProxyMessage) {
        find(message.clientId)?.receive(message)
    }

    fun close() {
        drain().forEach { it.fail(HttpStatusCode.BadGateway, null) }
    }
}

class HttpStream(
    val id: UUID,
    private val outgoing: SendChannel<ProxyMessage>,
    private val streams: HttpStreams
) {
    val head = CompletableDeferred<ProxyMessage.HttpResponse>()
    val body = Channel<ByteArray>(Channel.UNLIMITED)
    val sendWindow = HttpSendWindow()
    private val receiveWindow = HttpReceiveWindow()
    private val finished = AtomicBoolean(false)

    @Volatile
    var upload: Job? = null

    val isFinished: Boolean get() = finished.get()

    fun receive(message: ProxyMessage) {
        if (finished.get()) return
        when (message) {
            is ProxyMessage.HttpResponse -> when {
                !head.complete(message) -> fail(HttpStatusCode.BadGateway, HttpResetReason.PROTOCOL)
                message.noBody -> end()
            }

            is ProxyMessage.HttpBody -> when {
                !head.isCompleted || !receiveWindow.received(message.data.size) ->
                    fail(HttpStatusCode.BadGateway, HttpResetReason.PROTOCOL)

                else -> {
                    body.trySend(message.data)
                    if (message.last) end()
                }
            }

            is ProxyMessage.HttpWindow ->
                if (!sendWindow.grant(message.increment)) fail(HttpStatusCode.BadGateway, HttpResetReason.PROTOCOL)

            is ProxyMessage.HttpReset -> fail(
                when (message.reason) {
                    HttpResetReason.REFUSED -> HttpStatusCode.ServiceUnavailable
                    HttpResetReason.HEAD_TOO_LARGE -> HttpStatusCode.RequestHeaderFieldTooLarge
                    else -> HttpStatusCode.BadGateway
                },
                null
            )

            else -> {}
        }
    }

    fun fail(status: HttpStatusCode, reset: HttpResetReason?) {
        if (!finished.compareAndSet(false, true)) return
        if (reset != null) outgoing.trySend(ProxyMessage.HttpReset(id, reset))
        val cause = HttpStreamFailedException(status)
        head.completeExceptionally(cause)
        body.close(cause)
        sendWindow.close()
    }

    fun delivered(bytes: Int) {
        val increment = receiveWindow.delivered(bytes)
        if (increment > 0 && !finished.get()) outgoing.trySend(ProxyMessage.HttpWindow(id, increment))
    }

    fun release() {
        if (finished.compareAndSet(false, true)) outgoing.trySend(ProxyMessage.HttpReset(id, HttpResetReason.CANCEL))
        sendWindow.close()
        upload?.cancel()
        body.cancel()
        streams.remove(id)
    }

    private fun end() {
        if (!finished.compareAndSet(false, true)) return
        body.close()
        sendWindow.close()
    }
}

private class TunnelledBody(
    override val status: HttpStatusCode,
    override val headers: Headers,
    override val contentLength: Long?,
    private val stream: HttpStream
) : OutgoingContent.WriteChannelContent() {
    override suspend fun writeTo(channel: ByteWriteChannel) {
        try {
            for (chunk in stream.body) {
                channel.writeFully(chunk)
                channel.flush()
                stream.delivered(chunk.size)
            }
        } finally {
            stream.release()
        }
    }
}

private class TunnelledHead(
    override val status: HttpStatusCode,
    override val headers: Headers,
    override val contentLength: Long?
) : OutgoingContent.NoContent()

private suspend fun RoutingCall.respondProxyStatus(status: HttpStatusCode) {
    if (request.headers.contains(HttpHeaders.Origin)) response.header(HttpHeaders.AccessControlAllowOrigin, "*")
    respond(status)
}

private suspend fun uploadBody(input: ByteReadChannel, stream: HttpStream, outgoing: SendChannel<ProxyMessage>) {
    val buffer = ByteArray(HttpTunnel.BODY_CHUNK_SIZE)
    while (true) {
        var filled = input.readAvailable(buffer)
        if (filled == -1) break
        while (filled < buffer.size && input.availableForRead > 0) {
            val read = input.readAvailable(buffer, filled, buffer.size - filled)
            if (read <= 0) break
            filled += read
        }
        var offset = 0
        while (offset < filled) {
            val taken = stream.sendWindow.acquire(filled - offset)
            if (stream.isFinished) return
            outgoing.trySend(ProxyMessage.HttpBody(stream.id, buffer.copyOfRange(offset, offset + taken), false))
            offset += taken
        }
    }
    if (!stream.isFinished) outgoing.trySend(ProxyMessage.HttpBody(stream.id, ByteArray(0), true))
}

fun Route.httpTunnel(tunnels: Map<String, ServerTunnel>) {
    route("/{id}/{...}") {
        install(HttpRequestLifecycle) {
            cancelCallOnClose = true
        }

        handle {
            val id = call.parameters["id"]
            if (id == null || id in reservedInstanceIds) {
                call.respondProxyStatus(HttpStatusCode.NotFound)
                return@handle
            }
            val tunnel = tunnels[id] ?: run {
                call.respondProxyStatus(HttpStatusCode.ServiceUnavailable)
                return@handle
            }
            if (!tunnel.http) {
                call.respondProxyStatus(HttpStatusCode.NotImplemented)
                return@handle
            }

            val requestHeaders = call.request.headers
            val declaredLength = requestHeaders[HttpHeaders.ContentLength]?.trim()
            val noBody = declaredLength == "0" ||
                (declaredLength == null && !requestHeaders.contains(HttpHeaders.TransferEncoding))
            val streamId = UUID.randomUUID()
            val request = ProxyMessage.HttpRequest(
                streamId,
                call.request.httpMethod.value,
                call.request.uri.removePrefix("/$id").let { if (it.startsWith("/")) it else "/$it" },
                requestHeaders.withoutRequestHopByHop(),
                noBody
            )
            if (!request.hasEncodableMethod()) {
                call.respondProxyStatus(HttpStatusCode.BadRequest)
                return@handle
            }
            if (request.headSize() > HttpTunnel.MAX_HEAD_SIZE) {
                call.respondProxyStatus(HttpStatusCode.RequestHeaderFieldTooLarge)
                return@handle
            }
            val stream = tunnel.httpStreams.open(streamId) ?: run {
                logger.warn("[{}] Rejecting HTTP request: no free stream", id)
                call.respondProxyStatus(HttpStatusCode.ServiceUnavailable)
                return@handle
            }

            var bodyHandedOver = false
            try {
                tunnel.sendChannel.trySend(request)
                if (!noBody) {
                    stream.upload = call.launch {
                        try {
                            uploadBody(call.receiveChannel(), stream, tunnel.sendChannel)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: HttpWindowClosedException) {
                        } catch (e: Exception) {
                            logger.debug("[{}] Request body of stream {} failed", id, streamId, e)
                            stream.fail(HttpStatusCode.BadRequest, HttpResetReason.CANCEL)
                        }
                    }
                }

                val head = try {
                    stream.head.await()
                } catch (e: HttpStreamFailedException) {
                    call.respondProxyStatus(e.status)
                    return@handle
                }
                val headers = head.headers.withoutResponseHopByHop()
                val contentLength = headers[HttpHeaders.ContentLength]?.trim()?.toLongOrNull()
                val forwarded = Headers.build {
                    headers.forEach { name, values ->
                        if (!name.equals(HttpHeaders.ContentLength, ignoreCase = true)) appendAll(name, values)
                    }
                }
                val status = HttpStatusCode.fromValue(head.status)
                if (head.noBody) {
                    call.respond(TunnelledHead(status, forwarded, contentLength))
                } else {
                    call.respond(TunnelledBody(status, forwarded, contentLength, stream))
                    bodyHandedOver = true
                }
            } finally {
                if (!bodyHandedOver) stream.release()
            }
        }
    }
}
