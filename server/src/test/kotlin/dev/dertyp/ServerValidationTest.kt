package dev.dertyp

import dev.dertyp.data.AuthenticationResponse
import dev.dertyp.data.ProxyInfo
import dev.dertyp.data.ServerStats
import dev.dertyp.rpc.BaseRpcServiceManager
import dev.dertyp.serializers.AppCbor
import dev.dertyp.services.IServerStatsService
import io.ktor.client.HttpClient
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalSerializationApi::class)
class ServerValidationTest {

    class TestRpcManager(client: HttpClient) : BaseRpcServiceManager(client) {
        override suspend fun getRpcUrl(): String? = null
        override suspend fun setRpcUrl(url: String) {}
        override fun getAuthToken(): String? = null
        override fun getRefreshToken(): String? = null
        override fun isTokenExpired(): Boolean = false
        override fun isAuthenticated(): Boolean = true
        override suspend fun updateAuth(response: AuthenticationResponse) {}
        override suspend fun handleAuthFailure() {}
    }

    private class MockStatsService : IServerStatsService {
        override suspend fun getStats(): ServerStats = ServerStats(
            songCount = 0,
            albumCount = 0,
            artistCount = 0,
            imagesCount = 0,
            playlistCount = 0,
            totalFileSize = 0,
            indexedFileSize = 0,
            averageSizePerSong = 0,
            totalDuration = 0,
            version = ServerStats.Version("", "", "", "", "")
        )
        override suspend fun health(): Boolean = true
        override suspend fun getProxyInfo(): ProxyInfo? = null
    }

    @Test
    fun `validateServer should return validated true and useSsl false for plain ws`() = testApplication {
        application {
            install(WebSockets)
            install(Krpc) {
                serialization {
                    cbor(AppCbor)
                }
            }
            routing {
                rpc("/rpc") {
                    registerService(IServerStatsService::class) { MockStatsService() }
                }
            }
        }

        val client = createClient {
            installKrpc {
                serialization {
                    cbor(AppCbor)
                }
            }
        }

        val manager = TestRpcManager(client)

        val result = manager.validateServer("localhost", 80, "/", useSsl = false)
        
        assertTrue(result.validated)
        assertFalse(result.useSsl)
    }

    @Test
    fun `validateServer should return validated true and useSsl true when wss is available`() = testApplication {
        application {
            install(WebSockets)
            install(Krpc) {
                serialization {
                    cbor(AppCbor)
                }
            }
            routing {
                rpc("/rpc") {
                    registerService(IServerStatsService::class) { MockStatsService() }
                }
            }
        }

        val client = createClient {
            installKrpc {
                serialization {
                    cbor(AppCbor)
                }
            }
        }

        val manager = TestRpcManager(client)

        val result = manager.validateServer("localhost", 80, "/", useSsl = true)
        
        assertTrue(result.validated)
        assertTrue(result.useSsl)
    }

    @Test
    fun `validateServer should fallback to ws when wss fails`() = testApplication {
        application {
            install(WebSockets)
            install(Krpc) {
                serialization {
                    cbor(AppCbor)
                }
            }
            routing {
                rpc("/rpc") {
                    registerService(IServerStatsService::class) { MockStatsService() }
                }
            }
        }

        val client = createClient {
            installKrpc {
                serialization {
                    cbor(AppCbor)
                }
            }
        }

        val manager = TestRpcManager(client)
        
        val result = manager.validateServer("localhost", 80, "/wrong-path", useSsl = true)
        
        assertFalse(result.validated)
    }
}
