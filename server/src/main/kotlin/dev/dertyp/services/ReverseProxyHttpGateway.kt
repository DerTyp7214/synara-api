package dev.dertyp.services

import dev.dertyp.core.HttpClientFactory
import dev.dertyp.proxy.HttpReceiveWindow
import dev.dertyp.proxy.HttpResetReason
import dev.dertyp.proxy.HttpSendWindow
import dev.dertyp.proxy.HttpTunnel
import dev.dertyp.proxy.ProxyMessage
import dev.dertyp.proxy.headSize
import dev.dertyp.proxy.withoutRequestHopByHop
import dev.dertyp.proxy.withoutResponseHopByHop
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.engine.ConnectorType
import io.ktor.server.engine.EngineConnectorConfig
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.inject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ReverseProxyHttpGateway : Service() {
    private val application by inject<Application>()
    private val httpClientFactory by inject<HttpClientFactory>()

    private val client: HttpClient
        get() = httpClientFactory.shared(HttpClientFactory.REVERSE_PROXY_LOOPBACK, CIO, {
            requestTimeout = 0
            maxConnectionsCount = HttpTunnel.MAX_CONCURRENT_STREAMS
            endpoint.maxConnectionsPerRoute = HttpTunnel.MAX_CONCURRENT_STREAMS
        }) {
            followRedirects = false
            expectSuccess = false
            useDefaultTransformers = false
        }

    fun connect(scope: CoroutineScope, deliver: suspend (ProxyMessage) -> Unit): Connection =
        Connection(scope, deliver)

    inner class Connection internal constructor(
        private val scope: CoroutineScope,
        private val deliver: suspend (ProxyMessage) -> Unit,
    ) {
        private val streams = ConcurrentHashMap<UUID, Stream>()

        fun handle(message: ProxyMessage) {
            when (message) {
                is ProxyMessage.HttpRequest -> open(message)

                is ProxyMessage.HttpBody -> streams[message.clientId]?.let { stream ->
                    if (stream.receiveWindow.received(message.data.size)) {
                        stream.requestBody.trySend(message)
                    } else {
                        abort(stream)
                    }
                }

                is ProxyMessage.HttpWindow -> streams[message.clientId]?.let { stream ->
                    if (!stream.sendWindow.grant(message.increment)) abort(stream)
                }

                is ProxyMessage.HttpReset -> streams.remove(message.clientId)?.cancel()

                else -> {}
            }
        }

        fun close() {
            streams.values.toList().forEach { stream ->
                streams.remove(stream.id, stream)
                stream.cancel()
            }
        }

        private fun open(request: ProxyMessage.HttpRequest) {
            val id = request.clientId
            val existing = streams[id]
            if (existing != null) {
                abort(existing)
                return
            }
            if (streams.size >= HttpTunnel.MAX_CONCURRENT_STREAMS) {
                scope.launch { sendReset(id, HttpResetReason.REFUSED) }
                return
            }
            val stream = Stream(id)
            streams[id] = stream
            stream.job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    serve(stream, request)
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    logger.warn("Tunnelled request ${request.method} ${request.uri} failed: $e")
                    stream.reset(HttpResetReason.INTERNAL)
                } finally {
                    stream.sendWindow.close()
                    stream.requestBody.cancel()
                    streams.remove(id, stream)
                }
            }
            stream.job.start()
        }

        private fun abort(stream: Stream) {
            streams.remove(stream.id, stream)
            scope.launch {
                stream.reset(HttpResetReason.PROTOCOL)
                stream.cancel()
            }
        }

        private suspend fun sendReset(id: UUID, reason: HttpResetReason) {
            try {
                deliver(ProxyMessage.HttpReset(id, reason))
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                logger.debug("Could not send the reset of tunnelled request $id: $e")
            }
        }

        private suspend fun serve(stream: Stream, request: ProxyMessage.HttpRequest) {
            val connector = application.engine.resolvedConnectors().firstOrNull { it.type == ConnectorType.HTTP }
            if (connector == null) {
                logger.error("The server has no HTTP connector, tunnelled HTTP requests cannot be served")
                stream.reset(HttpResetReason.INTERNAL)
                return
            }

            val target = loopbackUrl(connector, request.uri)
            val lengths = request.headers.getAll(HttpHeaders.ContentLength).orEmpty().distinct()
            val length = lengths.singleOrNull()?.toLongOrNull()
            val lengthValid = request.noBody || lengths.isEmpty() || (length != null && length >= 0)
            if (target == null || !lengthValid) {
                stream.refuse(request, HttpStatusCode.BadRequest)
                return
            }

            val requestMethod = HttpMethod.parse(request.method)
            if (!ReverseProxyRouteAllowlist.isAllowed(requestMethod, target.encodedPath)) {
                stream.refuse(request, HttpStatusCode.NotFound)
                return
            }

            client.prepareRequest {
                method = requestMethod
                url.takeFrom(target)
                headers.appendAll(forwardedHeaders(request.headers))
                if (!request.noBody) setBody(RequestBody(stream, length))
            }.execute { response ->
                try {
                    relay(stream, request, response)
                } finally {
                    stream.requestBody.cancel()
                }
            }
        }

        private suspend fun relay(stream: Stream, request: ProxyMessage.HttpRequest, response: HttpResponse) {
            val headers = Headers.build {
                response.headers.withoutResponseHopByHop().forEach { name, values ->
                    appendAll(name, values.map(::valueForTunnel))
                }
            }
            val noBody = request.method == HttpMethod.Head.value ||
                response.status == HttpStatusCode.NoContent ||
                response.status == HttpStatusCode.NotModified ||
                headers[HttpHeaders.ContentLength] == "0"
            val head = ProxyMessage.HttpResponse(stream.id, response.status.value, headers, noBody)
            if (head.headSize() > HttpTunnel.MAX_HEAD_SIZE) {
                stream.reset(HttpResetReason.HEAD_TOO_LARGE)
                return
            }
            stream.send(head, last = noBody)
            if (noBody) return

            val body = response.bodyAsChannel()
            val buffer = ByteArray(HttpTunnel.BODY_CHUNK_SIZE)
            while (true) {
                val read = body.readAvailable(buffer)
                if (read == -1) break
                var offset = 0
                while (offset < read) {
                    val taken = stream.sendWindow.acquire(read - offset)
                    stream.send(ProxyMessage.HttpBody(stream.id, buffer.copyOfRange(offset, offset + taken), false))
                    offset += taken
                }
            }
            stream.send(ProxyMessage.HttpBody(stream.id, ByteArray(0), true), last = true)
        }

        private inner class Stream(val id: UUID) {
            val requestBody = Channel<ProxyMessage.HttpBody>(Channel.UNLIMITED)
            val sendWindow = HttpSendWindow()
            val receiveWindow = HttpReceiveWindow()
            lateinit var job: Job

            private val sendLock = Mutex()

            @Volatile
            private var finished = false

            suspend fun send(message: ProxyMessage, last: Boolean = false) = sendLock.withLock {
                if (finished) return@withLock
                deliver(message)
                if (last) finished = true
            }

            suspend fun reset(reason: HttpResetReason) = sendLock.withLock {
                if (finished) return@withLock
                finished = true
                sendReset(id, reason)
            }

            suspend fun refuse(request: ProxyMessage.HttpRequest, status: HttpStatusCode) {
                val headers = Headers.build {
                    append(HttpHeaders.ContentLength, "0")
                    if (request.headers.contains(HttpHeaders.Origin)) append(HttpHeaders.AccessControlAllowOrigin, "*")
                }
                send(ProxyMessage.HttpResponse(id, status.value, headers, true), last = true)
            }

            fun cancel() {
                finished = true
                job.cancel()
            }
        }

        private inner class RequestBody(
            private val stream: Stream,
            override val contentLength: Long?,
        ) : OutgoingContent.WriteChannelContent() {
            override suspend fun writeTo(channel: ByteWriteChannel) {
                var written = 0L
                while (true) {
                    val chunk = stream.requestBody.receiveCatching().getOrNull() ?: return
                    written += chunk.data.size
                    check(contentLength == null || written <= contentLength) {
                        "The request body exceeds its Content-Length of $contentLength"
                    }
                    channel.writeFully(chunk.data)
                    channel.flush()
                    if (chunk.last) break
                    val increment = stream.receiveWindow.delivered(chunk.data.size)
                    if (increment > 0) stream.send(ProxyMessage.HttpWindow(stream.id, increment))
                }
                check(contentLength == null || written == contentLength) {
                    "The request body ended after $written of $contentLength bytes"
                }
            }
        }
    }

    private fun loopbackUrl(connector: EngineConnectorConfig, uri: String): Url? {
        if (!uri.startsWith('/') || uri.startsWith("//")) return null
        if (uri.any { it !in '!'..'~' || it == '#' }) return null
        val host = when (connector.host) {
            "0.0.0.0", "::", "0:0:0:0:0:0:0:0" -> "127.0.0.1"
            else -> if (':' in connector.host) "[${connector.host}]" else connector.host
        }
        val parsed = try {
            URLBuilder().takeFrom("${URLProtocol.HTTP.name}://$host:${connector.port}$uri").build()
        } catch (_: URLParserException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        val sameTarget = parsed.protocol == URLProtocol.HTTP &&
            parsed.host == host &&
            parsed.port == connector.port &&
            parsed.user == null &&
            parsed.password == null
        val samePath = parsed.encodedPath == uri.substringBefore('?') &&
            URLBuilder().takeFrom(parsed).build() == parsed
        return parsed.takeIf { sameTarget && samePath }
    }

    private fun forwardedHeaders(headers: Headers): Headers = Headers.build {
        headers.withoutRequestHopByHop().forEach { name, values ->
            when {
                name.equals(HttpHeaders.ContentLength, ignoreCase = true) -> {}
                name.equals(HttpHeaders.ContentType, ignoreCase = true) ->
                    append(HttpHeaders.ContentType, valueForLoopback(values.first()))

                name.equals(HttpHeaders.Cookie, ignoreCase = true) ->
                    appendAll(HttpHeaders.Cookie, values.map(::valueForLoopback))

                else -> appendAll(name, values.map(::valueForLoopback))
            }
        }
    }

    private fun valueForTunnel(value: String): String {
        if (value.all { it.code < 0x80 }) return value
        return String(value.replace(REPLACEMENT, '?').toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
    }

    private fun valueForLoopback(value: String): String {
        if (value.all { it.code < 0x80 } || value.any { it.code > 0xFF }) return value
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(value.toByteArray(Charsets.ISO_8859_1))).toString()
        } catch (_: CharacterCodingException) {
            value
        }
    }

    private companion object {
        const val REPLACEMENT = '\uFFFD'
    }
}
