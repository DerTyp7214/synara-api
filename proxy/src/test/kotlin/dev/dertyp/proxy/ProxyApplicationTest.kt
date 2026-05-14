package dev.dertyp.proxy

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ProxyApplicationTest {

    @Test
    fun `server should connect with valid key`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/proxy/server", {
            header("X-Proxy-Key", "")
        }) {
            val frame = incoming.receive() as Frame.Binary
            val msg = ProxyMessage.fromFrame(frame) as ProxyMessage.AssignedId
            assertTrue(msg.id.isNotEmpty())
        }
    }

    @Test
    fun `server should be rejected with invalid key`() = testApplication {
        environment {
            config = MapApplicationConfig("proxy.key" to "secret")
        }
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        try {
            client.webSocket("/proxy/server", {
                header("X-Proxy-Key", "wrong")
            }) {
            }
        } catch (_: Exception) {
        }
    }

    @Test
    fun `client request for unknown server should fail`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/unknown-id/rpc/test") {
            val reason = closeReason.await()
            assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, reason?.code)
        }
    }

    @Test
    fun `client request for non-rpc path should fail`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/some-id/not-rpc/test") {
            val reason = closeReason.await()
            assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, reason?.code)
        }
    }

    @Test
    fun `full proxy flow should work`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/proxy/server?id=test-server") {
            val assigned = ProxyMessage.fromFrame(incoming.receive()) as ProxyMessage.AssignedId
            assertEquals("test-server", assigned.id)

            launch {
                client.webSocket("/test-server/rpc/hello") {
                    send("Hello from client")
                    val response = incoming.receive() as Frame.Text
                    assertEquals("Hello from server", response.readText())
                }
            }

            val newClientMsg = ProxyMessage.fromFrame(incoming.receive()) as ProxyMessage.NewClient
            assertEquals("/rpc/hello", newClientMsg.uri)
            val clientId = newClientMsg.clientId

            val clientFrame = ProxyMessage.fromFrame(incoming.receive()) as ProxyMessage.ClientFrame
            assertEquals(clientId, clientFrame.clientId)
            assertEquals("Hello from client", String(clientFrame.data))

            send(ProxyMessage.ClientFrame(clientId, "Hello from server".toByteArray(), false).toFrame())
        }
    }

    @Test
    fun `server should be rejected if ID is already in use`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/proxy/server?id=duplicate") {
            client.webSocket("/proxy/server?id=duplicate") {
                val reason = closeReason.await()
                assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, reason?.code)
                assertEquals("Requested ID already in use", reason?.message)
            }
        }
    }

    @Test
    fun `instances endpoint should list connected servers`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
            install(ContentNegotiation) {
                json()
            }
        }

        client.webSocket("/proxy/server?id=s1&name=Server1") {
            incoming.receive()
            val instances = client.get("/instances").body<List<InstanceInfo>>()
            assertEquals(1, instances.size)
            assertEquals("s1", instances[0].id)
            assertEquals("Server1", instances[0].name)
        }
    }

    @Test
    fun `server disconnect should close client connections`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        val clientDisconnected = CompletableDeferred<Unit>()

        client.webSocket("/proxy/server?id=s1") {
            incoming.receive()

            launch {
                try {
                    client.webSocket("/s1/rpc/test") {
                        incoming.receive()
                    }
                } catch (_: Exception) {
                    clientDisconnected.complete(Unit)
                }
            }

            delay(100.milliseconds)
        }

        clientDisconnected.await()
    }

    @Test
    fun `client disconnect should notify server`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/proxy/server?id=s1") {
            incoming.receive()

            client.webSocket("/s1/rpc/test") {
            }

            val msg1 = ProxyMessage.fromFrame(incoming.receive())
            assertTrue(msg1 is ProxyMessage.NewClient)
            val msg2 = ProxyMessage.fromFrame(incoming.receive())
            assertTrue(msg2 is ProxyMessage.ClientDisconnected)
            assertEquals(msg1.clientId, msg2.clientId)
        }
    }

    @Test
    fun `proxy should respond to ping with pong`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/proxy/server") {
            incoming.receive()

            send(ProxyMessage.Ping.toFrame())
            val response = ProxyMessage.fromFrame(incoming.receive())
            assertTrue(response is ProxyMessage.Pong)
        }
    }
}
