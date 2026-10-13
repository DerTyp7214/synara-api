package dev.dertyp.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readRemaining
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Timeout(120)
class ProxyHttpTunnelTest {

    private suspend inline fun <reified T : ProxyMessage> DefaultClientWebSocketSession.next(): T =
        assertIs<T>(withTimeout(20.seconds) { ProxyMessage.fromFrame(incoming.receive()) })

    private suspend fun DefaultClientWebSocketSession.messagesUntilPong(): List<ProxyMessage> {
        send(ProxyMessage.Ping.toFrame())
        val messages = mutableListOf<ProxyMessage>()
        while (true) {
            val message = next<ProxyMessage>()
            if (message is ProxyMessage.Pong) return messages
            messages += message
        }
    }

    private fun tunnelTest(
        query: String = "id=s1&http=1",
        block: suspend DefaultClientWebSocketSession.(HttpClient) -> Unit
    ) = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }
        client.webSocket("/proxy/server?$query") {
            next<ProxyMessage.AssignedId>()
            block(client)
        }
    }

    private fun pattern(size: Int): ByteArray = ByteArray(size) { (it * 31 + it / 251).toByte() }

    @Test
    fun `unknown instance gives 503`() = testApplication {
        application {
            module()
        }
        val plain = client.get("/missing/api/songs")
        assertEquals(HttpStatusCode.ServiceUnavailable, plain.status)
        assertNull(plain.headers[HttpHeaders.AccessControlAllowOrigin])

        val browser = client.get("/missing/api/songs") { header(HttpHeaders.Origin, "https://app.example") }
        assertEquals(HttpStatusCode.ServiceUnavailable, browser.status)
        assertEquals("*", browser.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `tunnel without the capability gives 501`() = tunnelTest(query = "id=s1") { client ->
        val plain = client.get("/s1/api/songs")
        assertEquals(HttpStatusCode.NotImplemented, plain.status)
        assertNull(plain.headers[HttpHeaders.AccessControlAllowOrigin])

        val browser = client.get("/s1/api/songs") { header(HttpHeaders.Origin, "https://app.example") }
        assertEquals(HttpStatusCode.NotImplemented, browser.status)
        assertEquals("*", browser.headers[HttpHeaders.AccessControlAllowOrigin])

        assertEquals(emptyList(), messagesUntilPong())
    }

    @Test
    fun `reserved ids give 404`() = tunnelTest { client ->
        for (path in listOf("/proxy/api/songs", "/instances/api/songs", "/proxy/server", "/proxy")) {
            val plain = client.get(path)
            assertEquals(HttpStatusCode.NotFound, plain.status, path)
            assertNull(plain.headers[HttpHeaders.AccessControlAllowOrigin], path)

            val browser = client.post(path) { header(HttpHeaders.Origin, "https://app.example") }
            assertEquals(HttpStatusCode.NotFound, browser.status, path)
            assertEquals("*", browser.headers[HttpHeaders.AccessControlAllowOrigin], path)
        }
        assertEquals(emptyList(), messagesUntilPong())
    }

    @Test
    fun `registration with a reserved id is rejected`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }
        for (id in listOf("proxy", "instances")) {
            client.webSocket("/proxy/server?id=$id&http=1") {
                val reason = withTimeout(20.seconds) { closeReason.await() }
                assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, reason?.code)
                assertEquals("Requested ID is reserved", reason?.message)
            }
            assertEquals(HttpStatusCode.NotFound, client.get("/$id/api/songs").status)
        }
        assertEquals("[]", client.get("/instances").bodyAsText())
    }

    @Test
    fun `get is forwarded and answered`() = tunnelTest { client ->
        val response = async {
            client.get("/s1/api/songs/search?q=a%20b&limit=5") {
                header(HttpHeaders.Authorization, "Bearer token")
                header(HttpHeaders.Cookie, "session=abc")
                header(HttpHeaders.Connection, "keep-alive, X-Hop")
                header("X-Hop", "1")
                header("Keep-Alive", "timeout=5")
                header(HttpHeaders.TE, "trailers")
                header(HttpHeaders.ProxyAuthorization, "Basic abc")
                header(HttpHeaders.Host, "proxy.example")
                header(HttpHeaders.XForwardedFor, "10.0.0.1")
            }
        }

        val request = next<ProxyMessage.HttpRequest>()
        assertEquals("GET", request.method)
        assertEquals("/api/songs/search?q=a%20b&limit=5", request.uri)
        assertTrue(request.noBody)
        assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
        assertEquals("session=abc", request.headers[HttpHeaders.Cookie])
        assertEquals(listOf("10.0.0.1"), request.headers.getAll(HttpHeaders.XForwardedFor))
        for (name in listOf(
            HttpHeaders.Connection, "X-Hop", "Keep-Alive", HttpHeaders.TE, HttpHeaders.ProxyAuthorization,
            HttpHeaders.Host, HttpHeaders.TransferEncoding, HttpHeaders.Upgrade,
            HttpHeaders.XForwardedHost, HttpHeaders.XForwardedProto, HttpHeaders.Forwarded
        )) {
            assertFalse(request.headers.contains(name), name)
        }

        val body = "[{\"id\":1}]".toByteArray()
        send(
            ProxyMessage.HttpResponse(
                request.clientId,
                201,
                Headers.build {
                    append(HttpHeaders.ContentType, "application/json")
                    append("X-Custom", "value")
                    append("X-Multi", "a")
                    append("X-Multi", "b")
                    append(HttpHeaders.SetCookie, "one=1")
                    append(HttpHeaders.SetCookie, "two=2")
                    append(HttpHeaders.AccessControlAllowOrigin, "https://app.example")
                    append(HttpHeaders.Connection, "X-Server-Hop")
                    append("X-Server-Hop", "1")
                },
                false
            ).toFrame()
        )
        send(ProxyMessage.HttpBody(request.clientId, body, true).toFrame())

        val result = withTimeout(20.seconds) { response.await() }
        assertEquals(HttpStatusCode.Created, result.status)
        assertEquals("value", result.headers["X-Custom"])
        assertEquals(listOf("a", "b"), result.headers.getAll("X-Multi"))
        assertEquals(listOf("one=1", "two=2"), result.headers.getAll(HttpHeaders.SetCookie))
        assertEquals(listOf("https://app.example"), result.headers.getAll(HttpHeaders.AccessControlAllowOrigin))
        assertFalse(result.headers.contains("X-Server-Hop"))
        assertContentEquals(body, result.bodyAsBytes())
        assertEquals(emptyList(), messagesUntilPong())
    }

    @Test
    fun `request for the bare id forwards the root path`() = tunnelTest { client ->
        for ((path, expected) in listOf("/s1" to "/", "/s1?x=1" to "/?x=1", "/s1/" to "/")) {
            val response = async { client.get(path) }
            val request = next<ProxyMessage.HttpRequest>()
            assertEquals(expected, request.uri)
            send(ProxyMessage.HttpResponse(request.clientId, 204, Headers.Empty, true).toFrame())
            assertEquals(HttpStatusCode.NoContent, withTimeout(20.seconds) { response.await() }.status)
        }
    }

    @Test
    fun `content length is kept and a response without it is chunked`() = tunnelTest { client ->
        val sized = async { client.get("/s1/sized") }
        val sizedRequest = next<ProxyMessage.HttpRequest>()
        send(
            ProxyMessage.HttpResponse(
                sizedRequest.clientId,
                200,
                headersOf(
                    HttpHeaders.ContentType to listOf("audio/flac"),
                    HttpHeaders.ContentLength to listOf("5")
                ),
                false
            ).toFrame()
        )
        send(ProxyMessage.HttpBody(sizedRequest.clientId, "hello".toByteArray(), true).toFrame())
        val sizedResponse = withTimeout(20.seconds) { sized.await() }
        assertEquals(listOf("5"), sizedResponse.headers.getAll(HttpHeaders.ContentLength))
        assertNull(sizedResponse.headers[HttpHeaders.TransferEncoding])
        assertEquals(listOf("audio/flac"), sizedResponse.headers.getAll(HttpHeaders.ContentType))
        assertEquals("hello", sizedResponse.bodyAsText())

        val streamedHeaders = CompletableDeferred<Headers>()
        val events = Channel<String>(Channel.UNLIMITED)
        val streamed = async {
            client.prepareGet("/s1/streamed").execute { response ->
                streamedHeaders.complete(response.headers)
                val channel = response.bodyAsChannel()
                events.send(String(channel.readByteArray(9)))
                events.send(String(channel.readByteArray(9)))
                channel.readRemaining().exhausted()
            }
        }
        val streamedRequest = next<ProxyMessage.HttpRequest>()
        send(
            ProxyMessage.HttpResponse(
                streamedRequest.clientId,
                200,
                headersOf(HttpHeaders.ContentType, "text/event-stream"),
                false
            ).toFrame()
        )
        send(ProxyMessage.HttpBody(streamedRequest.clientId, "data: 1\n\n".toByteArray(), false).toFrame())
        val streamedResponseHeaders = withTimeout(20.seconds) { streamedHeaders.await() }
        assertNull(streamedResponseHeaders[HttpHeaders.ContentLength])
        assertEquals(listOf("chunked"), streamedResponseHeaders.getAll(HttpHeaders.TransferEncoding))
        assertEquals(listOf("text/event-stream"), streamedResponseHeaders.getAll(HttpHeaders.ContentType))
        assertEquals("data: 1\n\n", withTimeout(20.seconds) { events.receive() })
        send(ProxyMessage.HttpBody(streamedRequest.clientId, "data: 2\n\n".toByteArray(), true).toFrame())
        assertEquals("data: 2\n\n", withTimeout(20.seconds) { events.receive() })
        assertTrue(withTimeout(20.seconds) { streamed.await() })
    }

    @Test
    fun `head and 204 are answered without a body`() = tunnelTest { client ->
        val head = async { client.head("/s1/api/songs/1/stream") }
        val headRequest = next<ProxyMessage.HttpRequest>()
        assertEquals("HEAD", headRequest.method)
        assertTrue(headRequest.noBody)
        send(
            ProxyMessage.HttpResponse(
                headRequest.clientId,
                200,
                headersOf(
                    HttpHeaders.ContentType to listOf("audio/flac"),
                    HttpHeaders.ContentLength to listOf("123456"),
                    HttpHeaders.AcceptRanges to listOf("bytes")
                ),
                true
            ).toFrame()
        )
        val headResponse = withTimeout(20.seconds) { head.await() }
        assertEquals(HttpStatusCode.OK, headResponse.status)
        assertEquals(listOf("123456"), headResponse.headers.getAll(HttpHeaders.ContentLength))
        assertNull(headResponse.headers[HttpHeaders.TransferEncoding])
        assertEquals(listOf("audio/flac"), headResponse.headers.getAll(HttpHeaders.ContentType))
        assertEquals("bytes", headResponse.headers[HttpHeaders.AcceptRanges])
        assertEquals(0, headResponse.bodyAsBytes().size)

        val deleted = async { client.get("/s1/api/none") }
        val deletedRequest = next<ProxyMessage.HttpRequest>()
        send(ProxyMessage.HttpResponse(deletedRequest.clientId, 204, headersOf("X-Custom", "v"), true).toFrame())
        val deletedResponse = withTimeout(20.seconds) { deleted.await() }
        assertEquals(HttpStatusCode.NoContent, deletedResponse.status)
        assertEquals("v", deletedResponse.headers["X-Custom"])
        assertNull(deletedResponse.headers[HttpHeaders.TransferEncoding])
        assertEquals(0, deletedResponse.bodyAsBytes().size)
        assertEquals(emptyList(), messagesUntilPong())
    }

    @Test
    fun `range request and partial content pass through`() = tunnelTest { client ->
        val response = async {
            client.get("/s1/api/songs/1/stream") { header(HttpHeaders.Range, "bytes=100-199") }
        }
        val request = next<ProxyMessage.HttpRequest>()
        assertEquals("bytes=100-199", request.headers[HttpHeaders.Range])
        val part = pattern(100)
        send(
            ProxyMessage.HttpResponse(
                request.clientId,
                206,
                headersOf(
                    HttpHeaders.ContentType to listOf("audio/flac"),
                    HttpHeaders.ContentLength to listOf("100"),
                    HttpHeaders.ContentRange to listOf("bytes 100-199/5000"),
                    HttpHeaders.AcceptRanges to listOf("bytes")
                ),
                false
            ).toFrame()
        )
        send(ProxyMessage.HttpBody(request.clientId, part, true).toFrame())
        val result = withTimeout(20.seconds) { response.await() }
        assertEquals(HttpStatusCode.PartialContent, result.status)
        assertEquals("bytes 100-199/5000", result.headers[HttpHeaders.ContentRange])
        assertEquals("100", result.headers[HttpHeaders.ContentLength])
        assertContentEquals(part, result.bodyAsBytes())
    }

    @Test
    fun `upload is chunked and bound by the send window`() = tunnelTest { client ->
        val payload = pattern(600_000)
        val response = async {
            client.post("/s1/api/upload") { setBody(payload) }
        }
        val request = next<ProxyMessage.HttpRequest>()
        assertEquals("POST", request.method)
        assertFalse(request.noBody)
        assertEquals("600000", request.headers[HttpHeaders.ContentLength])

        val received = ByteArrayOutputStream()
        while (received.size() < HttpTunnel.INITIAL_WINDOW) {
            val chunk = next<ProxyMessage.HttpBody>()
            assertEquals(request.clientId, chunk.clientId)
            assertTrue(chunk.data.size <= HttpTunnel.BODY_CHUNK_SIZE)
            assertFalse(chunk.last)
            received.write(chunk.data)
        }
        assertEquals(HttpTunnel.INITIAL_WINDOW, received.size())
        assertEquals(emptyList(), messagesUntilPong())

        send(ProxyMessage.HttpWindow(request.clientId, 1000).toFrame())
        val small = next<ProxyMessage.HttpBody>()
        assertEquals(1000, small.data.size)
        assertFalse(small.last)
        received.write(small.data)
        assertEquals(emptyList(), messagesUntilPong())

        var last = false
        send(ProxyMessage.HttpWindow(request.clientId, HttpTunnel.BODY_CHUNK_SIZE.toLong()).toFrame())
        while (!last) {
            val chunk = next<ProxyMessage.HttpBody>()
            assertTrue(chunk.data.size <= HttpTunnel.BODY_CHUNK_SIZE)
            received.write(chunk.data)
            last = chunk.last
            if (!last && chunk.data.isNotEmpty()) {
                send(ProxyMessage.HttpWindow(request.clientId, chunk.data.size.toLong()).toFrame())
            }
        }
        assertContentEquals(payload, received.toByteArray())

        send(ProxyMessage.HttpResponse(request.clientId, 200, Headers.Empty, true).toFrame())
        assertEquals(HttpStatusCode.OK, withTimeout(20.seconds) { response.await() }.status)
        assertEquals(emptyList(), messagesUntilPong())
    }

    @Test
    fun `window is granted for delivered response bytes`() = tunnelTest { client ->
        val first = pattern(1000)
        val second = pattern(HttpTunnel.BODY_CHUNK_SIZE)
        val consumed = Channel<ByteArray>(Channel.UNLIMITED)
        val response = async {
            client.prepareGet("/s1/api/songs/1/stream").execute { response ->
                val channel = response.bodyAsChannel()
                consumed.send(channel.readByteArray(first.size))
                consumed.send(channel.readByteArray(second.size))
                channel.readRemaining().exhausted()
            }
        }
        val request = next<ProxyMessage.HttpRequest>()
        send(ProxyMessage.HttpResponse(request.clientId, 200, Headers.Empty, false).toFrame())

        send(ProxyMessage.HttpBody(request.clientId, first, false).toFrame())
        assertContentEquals(first, withTimeout(20.seconds) { consumed.receive() })
        assertEquals(emptyList(), messagesUntilPong())

        send(ProxyMessage.HttpBody(request.clientId, second, false).toFrame())
        assertContentEquals(second, withTimeout(20.seconds) { consumed.receive() })
        val window = next<ProxyMessage.HttpWindow>()
        assertEquals(request.clientId, window.clientId)
        assertEquals(1000L + HttpTunnel.BODY_CHUNK_SIZE, window.increment)

        send(ProxyMessage.HttpBody(request.clientId, ByteArray(0), true).toFrame())
        assertTrue(withTimeout(20.seconds) { response.await() })
        assertEquals(emptyList(), messagesUntilPong())
    }

    @Test
    fun `reset before the head gives an error status`() = tunnelTest { client ->
        val expected = mapOf(
            HttpResetReason.CANCEL to HttpStatusCode.BadGateway,
            HttpResetReason.INTERNAL to HttpStatusCode.BadGateway,
            HttpResetReason.PROTOCOL to HttpStatusCode.BadGateway,
            HttpResetReason.REFUSED to HttpStatusCode.ServiceUnavailable,
            HttpResetReason.HEAD_TOO_LARGE to HttpStatusCode.RequestHeaderFieldTooLarge
        )
        for ((reason, status) in expected) {
            val response = async {
                client.get("/s1/api/songs") { header(HttpHeaders.Origin, "https://app.example") }
            }
            val request = next<ProxyMessage.HttpRequest>()
            send(ProxyMessage.HttpReset(request.clientId, reason).toFrame())
            val result = withTimeout(20.seconds) { response.await() }
            assertEquals(status, result.status, reason.name)
            assertEquals("*", result.headers[HttpHeaders.AccessControlAllowOrigin], reason.name)
            assertEquals(emptyList(), messagesUntilPong(), reason.name)
        }
    }

    @Test
    fun `method that cannot be encoded gives 400 and is not forwarded`() = tunnelTest { client ->
        val response = client.request("/s1/api/songs") {
            method = HttpMethod("A".repeat(256))
            header(HttpHeaders.Origin, "https://app.example")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals(emptyList(), messagesUntilPong())

        val following = async { client.get("/s1/api/songs") }
        val request = next<ProxyMessage.HttpRequest>()
        send(ProxyMessage.HttpResponse(request.clientId, 204, Headers.Empty, true).toFrame())
        assertEquals(HttpStatusCode.NoContent, following.await().status)
    }

    @Test
    fun `oversized request head gives 431 and is not forwarded`() = tunnelTest { client ->
        val response = client.get("/s1/api/songs") {
            header("X-Large", "a".repeat(HttpTunnel.MAX_HEAD_SIZE))
            header(HttpHeaders.Origin, "https://app.example")
        }
        assertEquals(HttpStatusCode.RequestHeaderFieldTooLarge, response.status)
        assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals(emptyList(), messagesUntilPong())
    }

    @Test
    fun `tunnel closing before the head gives 502`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }
        coroutineScope {
            lateinit var response: Deferred<HttpResponse>
            client.webSocket("/proxy/server?id=s1&http=1") {
                next<ProxyMessage.AssignedId>()
                response = this@coroutineScope.async {
                    client.get("/s1/api/songs") { header(HttpHeaders.Origin, "https://app.example") }
                }
                next<ProxyMessage.HttpRequest>()
            }
            val result = withTimeout(20.seconds) { response.await() }
            assertEquals(HttpStatusCode.BadGateway, result.status)
            assertEquals("*", result.headers[HttpHeaders.AccessControlAllowOrigin])
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/s1/api/songs").status)
    }

    @Test
    fun `tunnel closing during the body truncates the response`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }
        coroutineScope {
            lateinit var body: Deferred<Result<ByteArray>>
            client.webSocket("/proxy/server?id=s1&http=1") {
                next<ProxyMessage.AssignedId>()
                val headSeen = CompletableDeferred<Unit>()
                body = this@coroutineScope.async {
                    runCatching {
                        client.prepareGet("/s1/api/songs/1/stream").execute { response ->
                            assertEquals("100000", response.headers[HttpHeaders.ContentLength])
                            headSeen.complete(Unit)
                            response.bodyAsBytes()
                        }
                    }
                }
                val request = next<ProxyMessage.HttpRequest>()
                send(
                    ProxyMessage.HttpResponse(
                        request.clientId,
                        200,
                        headersOf(HttpHeaders.ContentLength, "100000"),
                        false
                    ).toFrame()
                )
                send(ProxyMessage.HttpBody(request.clientId, pattern(1000), false).toFrame())
                withTimeout(20.seconds) { headSeen.await() }
            }
            val result = withTimeout(20.seconds) { body.await() }
            assertTrue(result.isFailure || result.getOrThrow().size < 100000, result.toString())
        }
    }

    @Test
    fun `reset during the body truncates the response`() = tunnelTest { client ->
        val headSeen = CompletableDeferred<Unit>()
        val body = async {
            runCatching {
                client.prepareGet("/s1/api/songs/1/stream").execute { response ->
                    headSeen.complete(Unit)
                    response.bodyAsBytes()
                }
            }
        }
        val request = next<ProxyMessage.HttpRequest>()
        send(
            ProxyMessage.HttpResponse(request.clientId, 200, headersOf(HttpHeaders.ContentLength, "100000"), false)
                .toFrame()
        )
        send(ProxyMessage.HttpBody(request.clientId, pattern(1000), false).toFrame())
        withTimeout(20.seconds) { headSeen.await() }
        send(ProxyMessage.HttpReset(request.clientId, HttpResetReason.INTERNAL).toFrame())
        val result = withTimeout(20.seconds) { body.await() }
        assertTrue(result.isFailure || result.getOrThrow().size < 100000, result.toString())
        assertEquals(emptyList(), messagesUntilPong())
    }

    @Test
    fun `early response stops the upload`() = tunnelTest { client ->
        val response = async {
            client.post("/s1/api/upload") { setBody(pattern(1_000_000)) }
        }
        val request = next<ProxyMessage.HttpRequest>()
        assertFalse(request.noBody)
        val first = next<ProxyMessage.HttpBody>()
        assertFalse(first.last)

        send(
            ProxyMessage.HttpResponse(
                request.clientId,
                401,
                headersOf(HttpHeaders.WWWAuthenticate, "Bearer"),
                true
            ).toFrame()
        )
        val result = withTimeout(20.seconds) { response.await() }
        assertEquals(HttpStatusCode.Unauthorized, result.status)
        assertEquals("Bearer", result.headers[HttpHeaders.WWWAuthenticate])

        val trailing = messagesUntilPong()
        var uploaded = first.data.size
        for (message in trailing) {
            val chunk = assertIs<ProxyMessage.HttpBody>(message)
            assertFalse(chunk.last)
            uploaded += chunk.data.size
        }
        assertTrue(uploaded <= HttpTunnel.INITIAL_WINDOW, "uploaded $uploaded")

        send(ProxyMessage.HttpWindow(request.clientId, 500_000).toFrame())
        assertEquals(emptyList(), messagesUntilPong())
    }

    @Test
    fun `concurrent streams are kept apart`() = tunnelTest { client ->
        val first = async { client.get("/s1/first") }
        val firstRequest = next<ProxyMessage.HttpRequest>()
        val second = async { client.get("/s1/second") }
        val secondRequest = next<ProxyMessage.HttpRequest>()
        assertEquals("/first", firstRequest.uri)
        assertEquals("/second", secondRequest.uri)
        assertTrue(firstRequest.clientId != secondRequest.clientId)

        send(ProxyMessage.HttpResponse(secondRequest.clientId, 200, headersOf("X-Stream", "second"), false).toFrame())
        send(ProxyMessage.HttpResponse(firstRequest.clientId, 202, headersOf("X-Stream", "first"), false).toFrame())
        send(ProxyMessage.HttpBody(firstRequest.clientId, "first-1 ".toByteArray(), false).toFrame())
        send(ProxyMessage.HttpBody(secondRequest.clientId, "second-1 ".toByteArray(), false).toFrame())
        send(ProxyMessage.HttpBody(secondRequest.clientId, "second-2".toByteArray(), true).toFrame())
        send(ProxyMessage.HttpBody(firstRequest.clientId, "first-2".toByteArray(), true).toFrame())

        val firstResponse = withTimeout(20.seconds) { first.await() }
        val secondResponse = withTimeout(20.seconds) { second.await() }
        assertEquals(HttpStatusCode.Accepted, firstResponse.status)
        assertEquals("first", firstResponse.headers["X-Stream"])
        assertEquals("first-1 first-2", firstResponse.bodyAsText())
        assertEquals(HttpStatusCode.OK, secondResponse.status)
        assertEquals("second", secondResponse.headers["X-Stream"])
        assertEquals("second-1 second-2", secondResponse.bodyAsText())
    }

    @Test
    fun `stream limit gives 503`() = tunnelTest { client ->
        val pending = (1..HttpTunnel.MAX_CONCURRENT_STREAMS).map {
            val response = async { client.get("/s1/pending/$it") }
            response to next<ProxyMessage.HttpRequest>()
        }
        val rejected = client.get("/s1/one-too-many") { header(HttpHeaders.Origin, "https://app.example") }
        assertEquals(HttpStatusCode.ServiceUnavailable, rejected.status)
        assertEquals("*", rejected.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals(emptyList(), messagesUntilPong())

        val (firstResponse, firstRequest) = pending.first()
        send(ProxyMessage.HttpResponse(firstRequest.clientId, 204, Headers.Empty, true).toFrame())
        assertEquals(HttpStatusCode.NoContent, withTimeout(20.seconds) { firstResponse.await() }.status)

        val next = async { client.get("/s1/fits-again") }
        val nextRequest = next<ProxyMessage.HttpRequest>()
        assertEquals("/fits-again", nextRequest.uri)
        send(ProxyMessage.HttpResponse(nextRequest.clientId, 204, Headers.Empty, true).toFrame())
        assertEquals(HttpStatusCode.NoContent, withTimeout(20.seconds) { next.await() }.status)

        for ((response, request) in pending.drop(1)) {
            send(ProxyMessage.HttpReset(request.clientId, HttpResetReason.REFUSED).toFrame())
            assertEquals(HttpStatusCode.ServiceUnavailable, withTimeout(20.seconds) { response.await() }.status)
        }
    }

    @Test
    fun `body before the head is a protocol violation`() = tunnelTest { client ->
        val response = async { client.get("/s1/api/songs") }
        val request = next<ProxyMessage.HttpRequest>()
        send(ProxyMessage.HttpBody(request.clientId, "early".toByteArray(), false).toFrame())
        val reset = next<ProxyMessage.HttpReset>()
        assertEquals(request.clientId, reset.clientId)
        assertEquals(HttpResetReason.PROTOCOL, reset.reason)
        assertEquals(HttpStatusCode.BadGateway, withTimeout(20.seconds) { response.await() }.status)
        assertEquals(emptyList(), messagesUntilPong())
    }

    @Test
    fun `second head is a protocol violation`() = tunnelTest { client ->
        val headSeen = CompletableDeferred<Unit>()
        val body = async {
            runCatching {
                client.prepareGet("/s1/api/songs").execute { response ->
                    headSeen.complete(Unit)
                    response.bodyAsBytes()
                }
            }
        }
        val request = next<ProxyMessage.HttpRequest>()
        val head = ProxyMessage.HttpResponse(request.clientId, 200, headersOf(HttpHeaders.ContentLength, "100"), false)
        send(head.toFrame())
        withTimeout(20.seconds) { headSeen.await() }
        send(head.toFrame())
        val reset = next<ProxyMessage.HttpReset>()
        assertEquals(request.clientId, reset.clientId)
        assertEquals(HttpResetReason.PROTOCOL, reset.reason)
        val result = withTimeout(20.seconds) { body.await() }
        assertTrue(result.isFailure || result.getOrThrow().size < 100, result.toString())
        assertEquals(emptyList(), messagesUntilPong())
    }

    @Test
    fun `invalid window increment is a protocol violation`() = tunnelTest { client ->
        val response = async { client.get("/s1/api/songs") }
        val request = next<ProxyMessage.HttpRequest>()
        send(ProxyMessage.HttpWindow(request.clientId, 0).toFrame())
        val reset = next<ProxyMessage.HttpReset>()
        assertEquals(request.clientId, reset.clientId)
        assertEquals(HttpResetReason.PROTOCOL, reset.reason)
        assertEquals(HttpStatusCode.BadGateway, withTimeout(20.seconds) { response.await() }.status)
    }

    @Test
    fun `messages for unknown streams and requests from the server are ignored`() = tunnelTest { client ->
        val unknown = UUID.randomUUID()
        send(ProxyMessage.HttpResponse(unknown, 200, Headers.Empty, false).toFrame())
        send(ProxyMessage.HttpBody(unknown, "x".toByteArray(), true).toFrame())
        send(ProxyMessage.HttpWindow(unknown, 0).toFrame())
        send(ProxyMessage.HttpReset(unknown, HttpResetReason.CANCEL).toFrame())
        send(ProxyMessage.HttpRequest(unknown, "GET", "/", Headers.Empty, true).toFrame())
        assertEquals(emptyList(), messagesUntilPong())

        val response = async { client.get("/s1/still-works") }
        val request = next<ProxyMessage.HttpRequest>()
        send(ProxyMessage.HttpResponse(request.clientId, 204, Headers.Empty, true).toFrame())
        assertEquals(HttpStatusCode.NoContent, withTimeout(20.seconds) { response.await() }.status)
    }

    @Test
    fun `websockets keep their route beside the http route`() = tunnelTest { client ->
        val exchange = launch {
            client.webSocket("/s1/rpc/hello?x=1") {
                send("from client")
                assertEquals("from server", (incoming.receive() as Frame.Text).readText())
            }
        }
        val newClient = next<ProxyMessage.NewClient>()
        assertEquals("/rpc/hello?x=1", newClient.uri)
        val frame = next<ProxyMessage.ClientFrame>()
        assertEquals("from client", String(frame.data))
        send(ProxyMessage.ClientFrame(newClient.clientId, "from server".toByteArray(), false).toFrame())
        withTimeout(20.seconds) { exchange.join() }
        assertEquals(newClient.clientId, next<ProxyMessage.ClientDisconnected>().clientId)

        client.webSocket("/s1/api/songs") {
            val reason = withTimeout(20.seconds) { closeReason.await() }
            assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, reason?.code)
            assertEquals("Only RPC paths are proxied", reason?.message)
        }
        client.webSocket("/missing/rpc/hello") {
            val reason = withTimeout(20.seconds) { closeReason.await() }
            assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, reason?.code)
        }
        assertEquals(emptyList(), messagesUntilPong())
    }

    @Test
    fun `frames for a closing websocket client keep the tunnel`() = tunnelTest { client ->
        val healthyFrames = Channel<String>(Channel.UNLIMITED)
        val healthy = launch {
            client.webSocket("/s1/rpc/healthy") {
                for (frame in incoming) healthyFrames.send((frame as Frame.Text).readText())
            }
        }
        val healthyId = next<ProxyMessage.NewClient>().clientId
        val payload = ByteArray(4096)

        val left = mutableSetOf<UUID>()
        repeat(200) { round ->
            val leave = CompletableDeferred<Unit>()
            val leaving = launch {
                client.webSocket("/s1/rpc/leaving") {
                    leave.await()
                }
            }
            var opened = next<ProxyMessage>()
            while (opened is ProxyMessage.ClientDisconnected) {
                assertTrue(opened.clientId in left, opened.toString())
                opened = next<ProxyMessage>()
            }
            val leavingId = assertIs<ProxyMessage.NewClient>(opened).clientId
            leave.complete(Unit)
            repeat(64) {
                send(ProxyMessage.ClientFrame(leavingId, payload, true).toFrame())
            }
            withTimeout(20.seconds) { leaving.join() }
            left += leavingId
            var disconnected = next<ProxyMessage.ClientDisconnected>()
            while (disconnected.clientId != leavingId) {
                assertTrue(disconnected.clientId in left, disconnected.toString())
                disconnected = next<ProxyMessage.ClientDisconnected>()
            }
            send(ProxyMessage.ClientFrame(healthyId, "round $round".toByteArray(), false).toFrame())
            assertEquals("round $round", withTimeout(20.seconds) { healthyFrames.receive() })
        }
        for (message in messagesUntilPong()) {
            assertTrue(assertIs<ProxyMessage.ClientDisconnected>(message).clientId in left, message.toString())
        }
        healthy.cancel()
    }

    private fun nettyTest(block: suspend DefaultClientWebSocketSession.(port: Int) -> Unit) = runBlocking {
        val server = embeddedServer(Netty, port = 0) { module() }.start(wait = false)
        val client = HttpClient(CIO) {
            install(WebSockets)
        }
        try {
            val port = server.engine.resolvedConnectors().first().port
            withTimeout(60.seconds) {
                client.webSocket("ws://127.0.0.1:$port/proxy/server?id=s1&http=1") {
                    next<ProxyMessage.AssignedId>()
                    block(port)
                }
            }
        } finally {
            client.close()
            server.stop(0, 0)
        }
    }

    private suspend fun open(port: Int, request: String): Socket = withContext(Dispatchers.IO) {
        Socket("127.0.0.1", port).apply {
            soTimeout = 20_000
            getOutputStream().write(request.toByteArray())
            getOutputStream().flush()
        }
    }

    private suspend fun Socket.readHead(): String = withContext(Dispatchers.IO) {
        val head = StringBuilder()
        val input = getInputStream()
        while (!head.endsWith("\r\n\r\n")) {
            val next = input.read()
            if (next == -1) break
            head.append(next.toChar())
        }
        head.toString()
    }

    private fun String.headerLines(name: String): List<String> =
        lines().filter { it.startsWith("$name:", ignoreCase = true) }.map { it.substringAfter(':').trim() }

    @Test
    fun `netty writes the tunnelled response to the wire`() = nettyTest { port ->
        val socket = open(
            port,
            "GET /s1/api/songs?x=1 HTTP/1.1\r\nHost: proxy.example\r\nConnection: close\r\n" +
                "X-Multi: a\r\nX-Multi: b\r\nAuthorization: Bearer token\r\n\r\n"
        )
        socket.use {
            val request = next<ProxyMessage.HttpRequest>()
            assertEquals("GET", request.method)
            assertEquals("/api/songs?x=1", request.uri)
            assertTrue(request.noBody)
            assertEquals(listOf("a", "b"), request.headers.getAll("X-Multi"))
            assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
            assertFalse(request.headers.contains(HttpHeaders.Host))
            assertFalse(request.headers.contains(HttpHeaders.Connection))

            send(
                ProxyMessage.HttpResponse(
                    request.clientId,
                    200,
                    Headers.build {
                        append(HttpHeaders.ContentType, "application/json")
                        append(HttpHeaders.ContentLength, "5")
                        append("X-Multi", "c")
                        append("X-Multi", "d")
                    },
                    false
                ).toFrame()
            )
            send(ProxyMessage.HttpBody(request.clientId, "hello".toByteArray(), true).toFrame())

            val head = socket.readHead()
            assertTrue(head.startsWith("HTTP/1.1 200"), head)
            assertEquals(listOf("5"), head.headerLines(HttpHeaders.ContentLength), head)
            assertEquals(emptyList(), head.headerLines(HttpHeaders.TransferEncoding), head)
            assertEquals(listOf("application/json"), head.headerLines(HttpHeaders.ContentType), head)
            assertEquals(listOf("c", "d"), head.headerLines("X-Multi"), head)
            assertEquals("hello", withContext(Dispatchers.IO) { String(socket.getInputStream().readBytes()) })
        }
        assertEquals(emptyList(), messagesUntilPong())
    }

    @Test
    fun `netty streams a response without content length chunk by chunk`() = nettyTest { port ->
        val socket = open(port, "GET /s1/events HTTP/1.1\r\nHost: proxy.example\r\nConnection: close\r\n\r\n")
        socket.use {
            val request = next<ProxyMessage.HttpRequest>()
            send(
                ProxyMessage.HttpResponse(
                    request.clientId,
                    200,
                    headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    false
                ).toFrame()
            )
            send(ProxyMessage.HttpBody(request.clientId, "data: 1\n\n".toByteArray(), false).toFrame())
            val head = socket.readHead()
            assertEquals(listOf("chunked"), head.headerLines(HttpHeaders.TransferEncoding), head)
            assertEquals(emptyList(), head.headerLines(HttpHeaders.ContentLength), head)
            assertEquals(listOf("text/event-stream"), head.headerLines(HttpHeaders.ContentType), head)

            val firstEvent = withContext(Dispatchers.IO) { String(socket.getInputStream().readNBytes(14)) }
            assertEquals("9\r\ndata: 1\n\n\r\n", firstEvent)

            send(ProxyMessage.HttpBody(request.clientId, "data: 2\n\n".toByteArray(), true).toFrame())
            val rest = withContext(Dispatchers.IO) { String(socket.getInputStream().readBytes()) }
            assertEquals("9\r\ndata: 2\n\n\r\n0\r\n\r\n", rest)
        }
    }

    @Test
    fun `netty keeps the content length of a head response`() = nettyTest { port ->
        val socket = open(port, "HEAD /s1/api/songs/1/stream HTTP/1.1\r\nHost: proxy.example\r\nConnection: close\r\n\r\n")
        socket.use {
            val request = next<ProxyMessage.HttpRequest>()
            assertEquals("HEAD", request.method)
            send(
                ProxyMessage.HttpResponse(
                    request.clientId,
                    200,
                    headersOf(
                        HttpHeaders.ContentType to listOf("audio/flac"),
                        HttpHeaders.ContentLength to listOf("123456")
                    ),
                    true
                ).toFrame()
            )
            val head = socket.readHead()
            assertTrue(head.startsWith("HTTP/1.1 200"), head)
            assertEquals(listOf("123456"), head.headerLines(HttpHeaders.ContentLength), head)
            assertEquals(emptyList(), head.headerLines(HttpHeaders.TransferEncoding), head)
            assertEquals(listOf("audio/flac"), head.headerLines(HttpHeaders.ContentType), head)
            assertEquals(0, withContext(Dispatchers.IO) { socket.getInputStream().readBytes() }.size)
        }
    }

    @Test
    fun `netty client disconnect before the head cancels the stream`() = nettyTest { port ->
        val socket = open(port, "GET /s1/api/slow HTTP/1.1\r\nHost: proxy.example\r\n\r\n")
        val request = next<ProxyMessage.HttpRequest>()
        withContext(Dispatchers.IO) { socket.close() }
        val reset = next<ProxyMessage.HttpReset>()
        assertEquals(request.clientId, reset.clientId)
        assertEquals(HttpResetReason.CANCEL, reset.reason)
        assertEquals(emptyList(), messagesUntilPong())
    }

    @Test
    fun `netty client disconnect during the body cancels the stream`() = nettyTest { port ->
        val socket = open(port, "GET /s1/events HTTP/1.1\r\nHost: proxy.example\r\n\r\n")
        val request = next<ProxyMessage.HttpRequest>()
        send(
            ProxyMessage.HttpResponse(request.clientId, 200, headersOf(HttpHeaders.ContentType, "text/event-stream"), false)
                .toFrame()
        )
        send(ProxyMessage.HttpBody(request.clientId, "data: 1\n\n".toByteArray(), false).toFrame())
        socket.readHead()
        assertEquals("9\r\n", withContext(Dispatchers.IO) { String(socket.getInputStream().readNBytes(3)) })
        withContext(Dispatchers.IO) { socket.close() }
        val reset = next<ProxyMessage.HttpReset>()
        assertEquals(request.clientId, reset.clientId)
        assertEquals(HttpResetReason.CANCEL, reset.reason)
        assertEquals(emptyList(), messagesUntilPong())
    }

    @Test
    fun `netty client disconnect during the upload cancels the stream`() = nettyTest { port ->
        val socket = open(
            port,
            "POST /s1/api/upload HTTP/1.1\r\nHost: proxy.example\r\nContent-Length: 100000\r\n\r\n" + "a".repeat(1000)
        )
        val request = next<ProxyMessage.HttpRequest>()
        assertFalse(request.noBody)
        val chunk = next<ProxyMessage.HttpBody>()
        assertFalse(chunk.last)
        withContext(Dispatchers.IO) { socket.close() }
        var message = next<ProxyMessage>()
        while (message is ProxyMessage.HttpBody) {
            assertFalse(message.last)
            message = next<ProxyMessage>()
        }
        val reset = assertIs<ProxyMessage.HttpReset>(message)
        assertEquals(request.clientId, reset.clientId)
        assertEquals(HttpResetReason.CANCEL, reset.reason)
        assertEquals(emptyList(), messagesUntilPong())
    }

    @Test
    fun `netty closes the connection when the stream fails during the body`() = nettyTest { port ->
        val socket = open(port, "GET /s1/api/songs/1/stream HTTP/1.1\r\nHost: proxy.example\r\n\r\n")
        socket.use {
            val request = next<ProxyMessage.HttpRequest>()
            send(
                ProxyMessage.HttpResponse(request.clientId, 200, headersOf(HttpHeaders.ContentLength, "1000000"), false)
                    .toFrame()
            )
            repeat(2) {
                send(ProxyMessage.HttpBody(request.clientId, pattern(HttpTunnel.BODY_CHUNK_SIZE), false).toFrame())
            }
            val head = socket.readHead()
            assertEquals(listOf("1000000"), head.headerLines(HttpHeaders.ContentLength), head)
            val firstChunk = withContext(Dispatchers.IO) { socket.getInputStream().readNBytes(HttpTunnel.BODY_CHUNK_SIZE) }
            assertContentEquals(pattern(HttpTunnel.BODY_CHUNK_SIZE), firstChunk)
            send(ProxyMessage.HttpReset(request.clientId, HttpResetReason.INTERNAL).toFrame())
            val rest = withContext(Dispatchers.IO) { runCatching { socket.getInputStream().readBytes() } }
            assertTrue(rest.isFailure || rest.getOrThrow().size < 1000000 - HttpTunnel.BODY_CHUNK_SIZE, rest.toString())
            for (message in messagesUntilPong()) {
                assertEquals(request.clientId, assertIs<ProxyMessage.HttpWindow>(message).clientId)
            }
        }
    }

    @Test
    fun `netty websocket client disconnect still notifies the server`() = nettyTest { port ->
        val client = HttpClient(CIO) {
            install(WebSockets)
        }
        try {
            client.webSocket("ws://127.0.0.1:$port/s1/rpc/hello") {
                send("from client")
            }
        } finally {
            client.close()
        }
        val newClient = next<ProxyMessage.NewClient>()
        assertEquals("/rpc/hello", newClient.uri)
        assertEquals("from client", String(next<ProxyMessage.ClientFrame>().data))
        assertEquals(newClient.clientId, next<ProxyMessage.ClientDisconnected>().clientId)
    }
}
