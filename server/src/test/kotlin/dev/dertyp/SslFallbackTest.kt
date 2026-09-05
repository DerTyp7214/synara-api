package dev.dertyp

import dev.dertyp.data.AuthenticationResponse
import dev.dertyp.data.HandshakeResponse
import dev.dertyp.rpc.BaseRpcServiceManager
import dev.dertyp.serializers.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ContentNegotiationApplicationPlugin

@OptIn(ExperimentalSerializationApi::class)
class SslFallbackTest {

    class TestRpcManager(client: HttpClient, var url: String) : BaseRpcServiceManager(client) {
        override suspend fun getRpcUrl(): String = url
        override suspend fun setRpcUrl(host: String, port: Int, ssl: Boolean, path: String) {
            val protocol = if (url.startsWith("ws")) (if (ssl) "wss" else "ws") else (if (ssl) "https" else "http")
            val p = if (ssl && port == 443) "" else if (!ssl && port == 80) "" else if (!ssl && port == 443) "" else ":$port"
            url = "$protocol://$host$p$path".removeSuffix("/")
        }
        override fun getAuthToken(): String? = null
        override fun getRefreshToken(): String? = null
        override fun isTokenExpired(): Boolean = false
        override fun isAuthenticated(): Boolean = true
        override suspend fun updateAuth(response: AuthenticationResponse) {}
        override suspend fun handleAuthFailure(reason: Throwable?) {}
        var confirmed: Boolean = false
        override val sslConfirmed: Boolean get() = confirmed
        override suspend fun setSslConfirmed(value: Boolean) {
            confirmed = value
        }
    }

    @Test
    fun `manager should fallback to http if server returns secure false`() = testApplication {
        application {
            install(ContentNegotiationApplicationPlugin) {
                json(AppJson)
            }
            routing {
                get("/handshake") {
                    call.respond(HandshakeResponse(secure = false, sslSupported = true))
                }
            }
        }

        val client = createClient {
            install(ContentNegotiation) {
                json(AppJson)
            }
        }

        val manager = TestRpcManager(client, "https://localhost")

        assertTrue(manager.checkSslSupport())

        assertEquals("https://localhost", manager.url)
        assertTrue(manager.confirmed)
        assertEquals(false, manager.handshake.value?.secure)
    }
}
