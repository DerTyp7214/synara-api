package dev.dertyp

import dev.dertyp.data.ApiVersion
import dev.dertyp.data.HandshakeResponse
import dev.dertyp.ui.UiSchemaVersion
import dev.dertyp.serializers.AppCbor
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.HandshakeService
import dev.dertyp.services.IHandshakeService
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.rpc.withService
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import io.ktor.client.plugins.websocket.WebSockets.Plugin as WebSocketsClientPlugin
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ContentNegotiationApplicationPlugin

@OptIn(ExperimentalSerializationApi::class, ExperimentalAtomicApi::class)
class HandshakeTest {

    @Test
    fun `handshake should return sslSupported false when no config present`() = testApplication {
        application {
            install(ContentNegotiationApplicationPlugin) { json(AppJson) }
        }
        routing {
            get("/handshake") {
                call.respond(HandshakeService.determineHandshakeResponse(call))
            }
        }
        val client = createClient {
            install(ContentNegotiation) { json(AppJson) }
        }

        val response = client.get("/handshake").body<HandshakeResponse>()
        assertFalse(response.secure)
        assertFalse(response.sslSupported)
        assertEquals(ApiVersion.CURRENT, response.apiVersion)
        assertEquals(UiSchemaVersion.CURRENT, response.uiSchemaVersion)
    }

    @Test
    fun `handshake should return sslSupported true when serverSslSupported true`() = testApplication {
        environment {
            config = MapApplicationConfig("server.sslSupported" to "true")
        }
        application {
            install(ContentNegotiationApplicationPlugin) { json(AppJson) }
        }
        routing {
            get("/handshake") {
                call.respond(HandshakeService.determineHandshakeResponse(call))
            }
        }
        val client = createClient {
            install(ContentNegotiation) { json(AppJson) }
        }

        val response = client.get("/handshake").body<HandshakeResponse>()
        assertTrue(response.sslSupported)
    }

    @Test
    fun `handshake should detect secure via header and return sslSupported true`() = testApplication {
        application {
            install(ContentNegotiationApplicationPlugin) { json(AppJson) }
        }
        routing {
            get("/handshake") {
                call.respond(HandshakeService.determineHandshakeResponse(call))
            }
        }
        val client = createClient {
            install(ContentNegotiation) { json(AppJson) }
        }

        val response = client.get("/handshake") {
            header("X-Forwarded-Proto", "https")
        }.body<HandshakeResponse>()
        assertTrue(response.secure)
        assertTrue(response.sslSupported)
    }

    @Test
    fun `handshake ws endpoint should return secure status`() = testApplication {
        application {
            install(WebSockets)
        }
        routing {
            webSocket("/handshake") {
                val response = HandshakeService.determineHandshakeResponse(call)
                send(Frame.Binary(true, AppCbor.encodeToByteArray(response)))
            }
        }
        val client = createClient {
            install(WebSocketsClientPlugin)
        }

        client.webSocket("/handshake") {
            val frame = incoming.receive() as Frame.Binary
            val response = AppCbor.decodeFromByteArray<HandshakeResponse>(frame.readBytes())
            assertFalse(response.secure)
            assertFalse(response.sslSupported)
        }
    }

    @Test
    fun `handshake krpc endpoint should return secure status`() = testApplication {
        application {
            install(WebSockets)
            install(ContentNegotiationApplicationPlugin) {
                json(AppJson)
            }
            install(Krpc) {
                serialization {
                    cbor(AppCbor)
                }
            }
        }
        routing {
            rpc("/rpc") {
                registerService(IHandshakeService::class) { HandshakeService(call) }
            }
        }

        val client = createClient {
            installKrpc {
                serialization {
                    cbor(AppCbor)
                }
            }
        }

        val rpcClient = client.rpc("/rpc")
        val handshakeService = rpcClient.withService<IHandshakeService>()

        val response = handshakeService.handshake()
        assertFalse(response.secure)
        assertFalse(response.sslSupported)
    }
}
