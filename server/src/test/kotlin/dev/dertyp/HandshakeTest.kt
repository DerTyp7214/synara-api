package dev.dertyp

import dev.dertyp.data.HandshakeResponse
import dev.dertyp.serializers.AppCbor
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.HandshakeService
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.ktor.client.plugins.websocket.WebSockets.Plugin as WebSocketsClientPlugin
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ContentNegotiationApplicationPlugin

@OptIn(ExperimentalSerializationApi::class)
class HandshakeTest {

    @Test
    fun `handshake rest endpoint should return secure status`() = testApplication {
        application {
            install(ContentNegotiationApplicationPlugin) {
                json(AppJson)
            }
        }
        routing {
            get("/handshake") {
                call.respond(HandshakeService.determineHandshakeResponse(call))
            }
        }
        val client = createClient {
            install(ContentNegotiation) {
                json(AppJson)
            }
        }

        val response = client.get("/handshake").body<HandshakeResponse>()
        assertFalse(response.secure)
        assertTrue(response.wssSupported)
    }

    @Test
    fun `handshake rest endpoint should detect secure via header`() = testApplication {
        application {
            install(ContentNegotiationApplicationPlugin) {
                json(AppJson)
            }
        }
        routing {
            get("/handshake") {
                call.respond(HandshakeService.determineHandshakeResponse(call))
            }
        }
        val client = createClient {
            install(ContentNegotiation) {
                json(AppJson)
            }
        }

        val response = client.get("/handshake") {
            header("X-Forwarded-Proto", "https")
        }.body<HandshakeResponse>()
        assertTrue(response.secure)
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
            assertTrue(response.wssSupported)
        }
    }
}
