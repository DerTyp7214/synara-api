package dev.dertyp.services

import dev.dertyp.config.ServerConfig
import dev.dertyp.core.HttpClientFactory
import dev.dertyp.proxy.HttpResetReason
import dev.dertyp.proxy.HttpTunnel
import dev.dertyp.proxy.ProxyMessage
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.writeStringUtf8
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ReverseProxyHttpGatewayTest : KoinTest {

    class Tunnel(
        val targetPort: Int,
        val targetHits: AtomicInteger,
        val registration: Deferred<Parameters>,
        private val toServer: Channel<ProxyMessage>,
        private val fromServer: Channel<ProxyMessage>,
        private val disconnect: CompletableDeferred<Unit>,
    ) {
        suspend fun send(message: ProxyMessage) = toServer.send(message)

        suspend fun next(): ProxyMessage = withTimeout(10.seconds) { fromServer.receive() }

        suspend fun nextOrNull(wait: Duration): ProxyMessage? = withTimeoutOrNull(wait) { fromServer.receive() }

        suspend fun request(
            method: String,
            uri: String,
            headers: Headers = Headers.Empty,
            noBody: Boolean = true,
        ): UUID {
            val id = UUID.randomUUID()
            send(ProxyMessage.HttpRequest(id, method, uri, headers, noBody))
            return id
        }

        suspend fun head(id: UUID): ProxyMessage.HttpResponse {
            val head = next()
            assertTrue(head is ProxyMessage.HttpResponse, "expected a response head, got $head")
            assertEquals(id, head.clientId)
            return head as ProxyMessage.HttpResponse
        }

        suspend fun body(id: UUID, limit: Int = Int.MAX_VALUE): ByteArray {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() < limit) {
                val chunk = next()
                assertTrue(chunk is ProxyMessage.HttpBody, "expected a body chunk, got $chunk")
                chunk as ProxyMessage.HttpBody
                assertEquals(id, chunk.clientId)
                assertTrue(chunk.data.size <= HttpTunnel.BODY_CHUNK_SIZE)
                bytes.write(chunk.data)
                if (chunk.last) break
                assertTrue(limit == Int.MAX_VALUE || bytes.size() <= limit, "more than $limit bytes were sent")
            }
            return bytes.toByteArray()
        }

        fun disconnect() {
            disconnect.complete(Unit)
        }
    }

    private fun tunnelTest(routes: Routing.() -> Unit, block: suspend Tunnel.() -> Unit) = runBlocking {
        val targetHits = AtomicInteger(0)
        val target = embeddedServer(Netty, port = 0) {
            intercept(ApplicationCallPipeline.Monitoring) { targetHits.incrementAndGet() }
            routing(routes)
        }.start(wait = false)

        val registration = CompletableDeferred<Parameters>()
        val toServer = Channel<ProxyMessage>(Channel.UNLIMITED)
        val fromServer = Channel<ProxyMessage>(Channel.UNLIMITED)
        val disconnect = CompletableDeferred<Unit>()
        val proxy = embeddedServer(Netty, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/proxy/server") {
                    registration.complete(call.request.queryParameters)
                    send(ProxyMessage.AssignedId("gateway-test").toFrame())
                    launch { for (message in toServer) send(message.toFrame()) }
                    launch {
                        disconnect.await()
                        close(CloseReason(CloseReason.Codes.GOING_AWAY, "test"))
                    }
                    for (frame in incoming) {
                        val message = ProxyMessage.fromFrame(frame) ?: continue
                        if (message !is ProxyMessage.Ping) fromServer.send(message)
                    }
                }
            }
        }.start(wait = false)

        val httpClientFactory = HttpClientFactory()
        startKoin {
            modules(module {
                single<Application> { target.application }
                single { httpClientFactory }
                single { ReverseProxyHttpGateway() }
            })
        }

        val config = MapApplicationConfig(
            "proxy.hostname" to "127.0.0.1",
            "proxy.controlPort" to proxy.engine.resolvedConnectors().first().port.toString()
        )
        val service = ReverseProxyService(ServerConfig(config))
        val job = launch(Dispatchers.Default) { service.startService() }
        try {
            withTimeout(60.seconds) {
                registration.await()
                Tunnel(
                    targetPort = target.engine.resolvedConnectors().first().port,
                    targetHits = targetHits,
                    registration = registration,
                    toServer = toServer,
                    fromServer = fromServer,
                    disconnect = disconnect,
                ).block()
            }
        } finally {
            job.cancelAndJoin()
            httpClientFactory.stopService()
            proxy.stop(500, 500)
            target.stop(500, 500)
            stopKoin()
        }
    }

    @Test
    fun `the registration announces the HTTP capability`() = tunnelTest({}) {
        assertEquals("1", registration.await()["http"])
        assertEquals(HttpTunnel.CAPABILITY_VALUE, registration.await()[HttpTunnel.CAPABILITY_PARAMETER])
    }

    @Test
    fun `a GET on a listed path is answered with head and body`() {
        val uris = Channel<String>(Channel.UNLIMITED)
        tunnelTest({
            get("/album/byId/{id}") {
                uris.send(call.request.uri)
                call.response.header("X-Canned", "yes")
                call.response.header(HttpHeaders.SetCookie, "a=1")
                call.response.header(HttpHeaders.SetCookie, "b=2")
                call.respondText("""{"id":"abc"}""", ContentType.Application.Json, HttpStatusCode.Accepted)
            }
        }) {
            val id = request("GET", "/album/byId/abc")
            val head = head(id)
            assertEquals(202, head.status)
            assertFalse(head.noBody)
            assertEquals("yes", head.headers["X-Canned"])
            assertEquals(listOf("a=1", "b=2"), head.headers.getAll(HttpHeaders.SetCookie))
            assertEquals("12", head.headers[HttpHeaders.ContentLength])
            assertTrue(ContentType.parse(head.headers[HttpHeaders.ContentType]!!).match(ContentType.Application.Json))
            assertNull(head.headers[HttpHeaders.Connection])
            assertNull(head.headers[HttpHeaders.TransferEncoding])
            assertEquals("""{"id":"abc"}""", String(body(id)))
            assertEquals("/album/byId/abc", uris.receive())

            val encoded = "/album/byId/a%2Fb+c%20d%C3%A4;v=1?x=1&y=a+b%20c&flag"
            val second = request("GET", encoded)
            assertEquals(202, head(second).status)
            assertEquals("""{"id":"abc"}""", String(body(second)))
            assertEquals(encoded, uris.receive())
        }
    }

    @Test
    fun `a POST body sent in chunks arrives intact and is granted credit as it is written`() {
        val payload = Random(1).nextBytes(HttpTunnel.BODY_CHUNK_SIZE * 2 + 1000)
        tunnelTest({
            post("/animatedImage/batch") {
                call.respondBytes(call.receive<ByteArray>(), ContentType.Application.OctetStream)
            }
        }) {
            val headers = headersOf(
                HttpHeaders.ContentLength to listOf(payload.size.toString()),
                "content-type" to listOf("application/octet-stream"),
            )
            val id = request("POST", "/animatedImage/batch", headers, noBody = false)
            send(ProxyMessage.HttpBody(id, payload.copyOfRange(0, HttpTunnel.BODY_CHUNK_SIZE), false))
            assertEquals(ProxyMessage.HttpWindow(id, HttpTunnel.BODY_CHUNK_SIZE.toLong()), next())
            send(
                ProxyMessage.HttpBody(
                    id,
                    payload.copyOfRange(HttpTunnel.BODY_CHUNK_SIZE, HttpTunnel.BODY_CHUNK_SIZE * 2),
                    false
                )
            )
            assertEquals(ProxyMessage.HttpWindow(id, HttpTunnel.BODY_CHUNK_SIZE.toLong()), next())
            send(ProxyMessage.HttpBody(id, payload.copyOfRange(HttpTunnel.BODY_CHUNK_SIZE * 2, payload.size), true))

            val head = head(id)
            assertEquals(200, head.status)
            assertEquals(payload.size.toString(), head.headers[HttpHeaders.ContentLength])
            assertArrayEquals(payload, body(id))

            val chunked = request("POST", "/animatedImage/batch", noBody = false)
            send(ProxyMessage.HttpBody(chunked, payload.copyOfRange(0, 3000), false))
            send(ProxyMessage.HttpBody(chunked, payload.copyOfRange(3000, 5000), false))
            send(ProxyMessage.HttpBody(chunked, ByteArray(0), true))
            assertEquals(200, head(chunked).status)
            assertArrayEquals(payload.copyOfRange(0, 5000), body(chunked))
        }
    }

    @Test
    fun `a request body longer than its Content-Length is not written`() {
        val bodies = Channel<Int>(Channel.UNLIMITED)
        tunnelTest({
            post("/animatedImage/batch") {
                bodies.send(call.receive<ByteArray>().size)
                call.respondText("ok")
            }
        }) {
            val smuggled = "0123456789GET /admin/mirror HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray()
            val id = request(
                "POST",
                "/animatedImage/batch",
                headersOf(HttpHeaders.ContentLength, "10"),
                noBody = false
            )
            send(ProxyMessage.HttpBody(id, smuggled, true))
            assertEquals(ProxyMessage.HttpReset(id, HttpResetReason.INTERNAL), next())
            assertNull(nextOrNull(500.milliseconds))
            assertTrue(targetHits.get() <= 1)
            assertEquals(0, bodies.tryReceive().getOrNull() ?: 0)

            val conflicting = request(
                "POST",
                "/animatedImage/batch",
                headersOf(HttpHeaders.ContentLength to listOf("1", "2")),
                noBody = false
            )
            val head = head(conflicting)
            assertEquals(400, head.status)
            assertTrue(head.noBody)
        }
    }

    @Test
    fun `a response larger than the window waits for credit`() {
        val payload = Random(2).nextBytes(600_000)
        tunnelTest({
            get("/song/streamSong/{id}") {
                call.respondBytes(payload, ContentType.Audio.MPEG)
            }
        }) {
            val id = request("GET", "/song/streamSong/abc?offset=0")
            val head = head(id)
            assertEquals(200, head.status)
            assertEquals(payload.size.toString(), head.headers[HttpHeaders.ContentLength])

            val received = ByteArrayOutputStream()
            received.write(body(id, HttpTunnel.INITIAL_WINDOW))
            assertEquals(HttpTunnel.INITIAL_WINDOW, received.size())
            assertNull(nextOrNull(1.seconds))

            send(ProxyMessage.HttpWindow(id, 100_000))
            received.write(body(id, 100_000))
            assertEquals(HttpTunnel.INITIAL_WINDOW + 100_000, received.size())
            assertNull(nextOrNull(1.seconds))

            send(ProxyMessage.HttpWindow(id, 1_000_000))
            received.write(body(id))
            assertArrayEquals(payload, received.toByteArray())
            assertNull(nextOrNull(300.milliseconds))
        }
    }

    @Test
    fun `a streaming response is forwarded while it is open and a reset stops its handler`() {
        val stopped = CompletableDeferred<Unit>()
        tunnelTest({
            get("/change/observeChanges") {
                call.respondBytesWriter(ContentType.Text.EventStream) {
                    try {
                        writeStringUtf8("data: first\n\n")
                        flush()
                        while (true) {
                            delay(100.milliseconds)
                            writeStringUtf8(": keep-alive\n\n")
                            flush()
                        }
                    } finally {
                        stopped.complete(Unit)
                    }
                }
            }
        }) {
            val id = request("GET", "/change/observeChanges", headersOf(HttpHeaders.Accept, "text/event-stream"))
            val head = head(id)
            assertEquals(200, head.status)
            assertFalse(head.noBody)
            assertTrue(ContentType.parse(head.headers[HttpHeaders.ContentType]!!).match(ContentType.Text.EventStream))

            val first = next()
            assertTrue(first is ProxyMessage.HttpBody)
            first as ProxyMessage.HttpBody
            assertFalse(first.last)
            assertTrue(String(first.data).startsWith("data: first\n\n"))
            assertFalse(stopped.isCompleted)

            send(ProxyMessage.HttpReset(id, HttpResetReason.CANCEL))
            withTimeout(10.seconds) { stopped.await() }

            while (true) {
                val late = nextOrNull(500.milliseconds) ?: break
                assertTrue(late is ProxyMessage.HttpBody && !late.last, "unexpected frame after the reset: $late")
            }
            assertNull(nextOrNull(500.milliseconds))
        }
    }

    @Test
    fun `a path that is not listed is answered with 404 without reaching the server`() = tunnelTest({
        get("/admin/mirror") { call.respondText("secret") }
        post("/album/byId/{id}") { call.respondText("secret") }
    }) {
        val plain = head(request("GET", "/admin/mirror"))
        assertEquals(404, plain.status)
        assertTrue(plain.noBody)
        assertEquals("0", plain.headers[HttpHeaders.ContentLength])
        assertNull(plain.headers[HttpHeaders.AccessControlAllowOrigin])

        val cors = head(request("GET", "/admin/mirror", headersOf(HttpHeaders.Origin, "https://app.example")))
        assertEquals(404, cors.status)
        assertTrue(cors.noBody)
        assertEquals("*", cors.headers[HttpHeaders.AccessControlAllowOrigin])

        val withBody = request("POST", "/album/byId/abc", noBody = false)
        val refused = head(withBody)
        assertEquals(404, refused.status)
        assertTrue(refused.noBody)
        send(ProxyMessage.HttpBody(withBody, "ignored".toByteArray(), true))

        assertEquals(404, head(request("GET", "/album/byId/abc/")).status)
        assertEquals(404, head(request("GET", "/album/byId/..")).status)
        assertEquals(404, head(request("GET", "/album/byId/%2E%2E")).status)
        assertEquals(404, head(request("GET / HTTP/1.1\r\nX: y", "/album/byId/abc")).status)

        assertNull(nextOrNull(500.milliseconds))
        assertEquals(0, targetHits.get())
    }

    @Test
    fun `uris that do not name a path on the server are answered with 400`() = tunnelTest({
        get("/album/byId/{id}") { call.respondText("reached") }
    }) {
        val uris = listOf(
            "//evil.example/x",
            "@evil.example/x",
            "http://evil.example/x",
            "album/byId/abc",
            "",
            "/album/byId/abc#fragment",
            "/album/byId/a b",
            "/album/byId/abc HTTP/1.1\r\nHost: evil.example\r\n\r\nGET /admin/mirror",
            "/album/byId/ä",
            "/album/byId/abc ",
        )
        uris.forEach { uri ->
            val head = head(request("GET", uri, headersOf(HttpHeaders.Origin, "https://app.example")))
            assertEquals(400, head.status, uri)
            assertTrue(head.noBody, uri)
            assertEquals("*", head.headers[HttpHeaders.AccessControlAllowOrigin])
        }
        assertNull(nextOrNull(500.milliseconds))
        assertEquals(0, targetHits.get())
    }

    @Test
    fun `a HEAD response has no body and keeps its Content-Length`() = tunnelTest({
        head("/song/streamSong/{id}") {
            call.respond(object : OutgoingContent.NoContent() {
                override val contentLength: Long = 12345
                override val contentType: ContentType = ContentType.Audio.MPEG
            })
        }
    }) {
        val id = request("HEAD", "/song/streamSong/abc")
        val head = head(id)
        assertEquals(200, head.status)
        assertTrue(head.noBody)
        assertEquals("12345", head.headers[HttpHeaders.ContentLength])
        assertNull(nextOrNull(500.milliseconds))

        val next = request("HEAD", "/song/streamSong/def")
        assertTrue(head(next).noBody)
    }

    @Test
    fun `the stream beyond the limit is refused and a duplicate id is a protocol error`() {
        val started = AtomicInteger(0)
        val allStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        tunnelTest({
            get("/album/byId/{id}") {
                if (started.incrementAndGet() == HttpTunnel.MAX_CONCURRENT_STREAMS) allStarted.complete(Unit)
                release.await()
            }
        }) {
            val ids = (1..HttpTunnel.MAX_CONCURRENT_STREAMS).map { request("GET", "/album/byId/$it") }
            withTimeout(30.seconds) { allStarted.await() }

            val overflow = request("GET", "/album/byId/overflow")
            assertEquals(ProxyMessage.HttpReset(overflow, HttpResetReason.REFUSED), next())

            send(ProxyMessage.HttpRequest(ids.first(), "GET", "/album/byId/again", Headers.Empty, true))
            assertEquals(ProxyMessage.HttpReset(ids.first(), HttpResetReason.PROTOCOL), next())

            val replacement = request("GET", "/admin/mirror")
            assertEquals(404, head(replacement).status)

            assertNull(nextOrNull(500.milliseconds))
            assertEquals(HttpTunnel.MAX_CONCURRENT_STREAMS, started.get())

            ids.forEach { send(ProxyMessage.HttpReset(it, HttpResetReason.CANCEL)) }
            assertNull(nextOrNull(500.milliseconds))
            release.complete(Unit)
        }
    }

    @Test
    fun `request headers reach the server and the host is the loopback target`() {
        val seen = Channel<Headers>(Channel.UNLIMITED)
        tunnelTest({
            get("/album/byId/{id}") {
                seen.send(call.request.headers)
                call.respond(HttpStatusCode.NoContent)
            }
        }) {
            val id = request(
                "GET",
                "/album/byId/abc",
                headersOf(
                    HttpHeaders.Authorization to listOf("Bearer token-1"),
                    "cookie" to listOf("synara-auth=abc", "theme=dark"),
                    HttpHeaders.UserAgent to listOf("Synara/3.11 (Android)"),
                    "X-Multi" to listOf("one", "two"),
                    "content-type" to listOf("application/json"),
                    HttpHeaders.Host to listOf("public.example"),
                    HttpHeaders.Connection to listOf("keep-alive, X-Hop"),
                    "X-Hop" to listOf("dropped"),
                    HttpHeaders.AcceptEncoding to listOf("gzip"),
                )
            )
            val head = head(id)
            assertEquals(204, head.status)
            assertTrue(head.noBody)

            val headers = seen.receive()
            assertEquals(listOf("Bearer token-1"), headers.getAll(HttpHeaders.Authorization))
            assertEquals(listOf("synara-auth=abc; theme=dark"), headers.getAll(HttpHeaders.Cookie))
            assertEquals(listOf("Synara/3.11 (Android)"), headers.getAll(HttpHeaders.UserAgent))
            assertEquals(listOf("one", "two"), headers.getAll("X-Multi")!!.flatMap { it.split(',') })
            assertEquals(listOf("application/json"), headers.getAll(HttpHeaders.ContentType))
            assertEquals(listOf("gzip"), headers.getAll(HttpHeaders.AcceptEncoding))
            assertEquals(listOf("127.0.0.1:$targetPort"), headers.getAll(HttpHeaders.Host))
            assertNull(headers["X-Hop"])
            assertNull(headers[HttpHeaders.Accept])
            assertNull(headers["Accept-Charset"])
            assertNull(headers[HttpHeaders.ContentLength])
            assertNull(headers[HttpHeaders.TransferEncoding])
        }
    }

    @Test
    fun `a request header value that is UTF-8 read as Latin-1 is written with its original bytes`() {
        val seen = Channel<List<String>>(Channel.UNLIMITED)
        tunnelTest({
            get("/album/byId/{id}") {
                seen.send(call.request.headers.getAll("X-Note").orEmpty())
                call.respond(HttpStatusCode.NoContent)
            }
        }) {
            val arrivals = mapOf(
                "plain ~ text" to "plain ~ text",
                "caf\u00C3\u00A9" to "caf\u00C3\u00A9",
                "\u00E4\u00B8\u00AD" to "\u00E4\u00B8\u00AD",
                "\u00F0\u009F\u0098\u0080" to "\u00F0\u009F\u0098\u0080",
                "caf\u00E9" to "caf\u00C3\u00A9",
                "\u00C3\u00A9 \u00E9" to "\u00C3\u0083\u00C2\u00A9 \u00C3\u00A9",
                "\u00C0\u00AF" to "\u00C3\u0080\u00C2\u00AF",
                "\u0142" to "\u00C5\u0082",
                "\u00C3\u00A9\u0142" to "\u00C3\u0083\u00C2\u00A9\u00C5\u0082",
            )
            for ((sent, arrived) in arrivals) {
                val id = request("GET", "/album/byId/abc", headersOf("X-Note", sent))
                assertEquals(204, head(id).status, sent)
                assertEquals(listOf(arrived), seen.receive(), sent)
            }

            val several = request(
                "GET",
                "/album/byId/abc",
                headersOf(
                    "X-Note" to listOf("\u00C3\u00A9"),
                    "cookie" to listOf("a=\u00C3\u00A4", "b=\u00E4"),
                    "content-type" to listOf("text/plain; note=\u00C3\u00A4"),
                )
            )
            assertEquals(204, head(several).status)
            assertEquals(listOf("\u00C3\u00A9"), seen.receive())
        }
    }

    @Test
    fun `a response header value is carried as one char per byte the server sent`() = tunnelTest({
        get("/album/byId/{id}") {
            call.response.header("X-Ascii", "plain ~ text")
            call.response.header("X-Utf8", "caf\u00C3\u00A9 \u00F0\u009F\u0098\u0080")
            call.response.header("X-Outside", "\u0142\u4E2D")
            call.response.header("X-Latin", "s\u00E4nger")
            call.respond(HttpStatusCode.NoContent)
        }
    }) {
        val head = head(request("GET", "/album/byId/abc"))
        assertEquals(204, head.status)
        assertEquals(listOf("plain ~ text"), head.headers.getAll("X-Ascii"))
        assertEquals(listOf("caf\u00C3\u00A9 \u00F0\u009F\u0098\u0080"), head.headers.getAll("X-Utf8"))
        assertEquals(listOf("??"), head.headers.getAll("X-Outside"))
        assertEquals(listOf("s?nger"), head.headers.getAll("X-Latin"))
    }

    @Test
    fun `closing the tunnel cancels its open streams`() {
        val stopped = CompletableDeferred<Unit>()
        tunnelTest({
            get("/change/observeChanges") {
                call.respondBytesWriter(ContentType.Text.EventStream) {
                    try {
                        while (true) {
                            writeStringUtf8("data: tick\n\n")
                            flush()
                            delay(100.milliseconds)
                        }
                    } finally {
                        stopped.complete(Unit)
                    }
                }
            }
        }) {
            val id = request("GET", "/change/observeChanges")
            assertEquals(200, head(id).status)
            assertTrue(next() is ProxyMessage.HttpBody)
            assertFalse(stopped.isCompleted)

            disconnect()
            withTimeout(10.seconds) { stopped.await() }
        }
    }
}
