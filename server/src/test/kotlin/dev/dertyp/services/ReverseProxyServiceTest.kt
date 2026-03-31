package dev.dertyp.services

import dev.dertyp.Indexer
import dev.dertyp.proxy.ProxyMessage
import dev.dertyp.services.metadata.MusicBrainzService
import dev.dertyp.services.tdn.DownloadService
import dev.dertyp.services.tdn.TidalDownloaderProxy
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class ReverseProxyServiceTest : KoinTest {

    private fun tearDown() {
        stopKoin()
    }

    private fun setupKoin(application: Application) {
        startKoin {
            modules(module {
                single { application }
                single { mockk<JwtService>(relaxed = true) }
                single { mockk<UserService>(relaxed = true) }
                single { mockk<ServerStatsService>(relaxed = true) }
                single { mockk<AuthService>(relaxed = true) }
                single { mockk<SessionService>(relaxed = true) }
                single { mockk<Indexer>(relaxed = true) }
                single { mockk<SongService>(relaxed = true) }
                single { mockk<AlbumService>(relaxed = true) }
                single { mockk<ImageService>(relaxed = true) }
                single { mockk<LyricsSearch>(relaxed = true) }
                single { mockk<ArtistService>(relaxed = true) }
                single { mockk<FavSyncService>(relaxed = true) }
                single { mockk<PlaylistService>(relaxed = true) }
                single { mockk<DownloadService>(relaxed = true) }
                single { mockk<UserPlaylistService>(relaxed = true) }
                single { mockk<TidalDownloaderProxy>(relaxed = true) }
                single { mockk<PlaybackService>(relaxed = true) }
                single { mockk<CustomAudioService>(relaxed = true) }
                single { mockk<DbManagementService>(relaxed = true) }
                single { mockk<BackupService>(relaxed = true) }
                single { mockk<UserPlaylistBackupService>(relaxed = true) }
                single { mockk<MirrorService>(relaxed = true) }
                single { mockk<RemoteMirrorService>(relaxed = true) }
                single { mockk<ScheduledTaskLogService>(relaxed = true) }
                single { mockk<ReleaseService>(relaxed = true) }
                single { mockk<MusicBrainzService>(relaxed = true) }
            })
        }
    }

    @Test
    fun `should connect and receive assigned ID`() = runBlocking {
        val assignedId = "test-id-123"
        val connectionReceived = CompletableDeferred<Unit>()

        val server = embeddedServer(Netty, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/proxy/server") {
                    send(ProxyMessage.AssignedId(assignedId).toFrame())
                    connectionReceived.complete(Unit)
                    delay(2000)
                }
            }
        }.start(wait = false)

        val port = server.engine.resolvedConnectors().first().port

        val config = MapApplicationConfig(
            "proxy.hostname" to "127.0.0.1",
            "proxy.controlPort" to port.toString(),
            "proxy.ssl" to "false",
            "proxy.id" to "synara-server",
            "proxy.name" to "Synara",
            "proxy.key" to "secret-key"
        )

        val mockApp = mockk<Application>(relaxed = true)
        setupKoin(mockApp)

        try {
            val service = ReverseProxyService(config)
            val job = launch {
                service.startService()
            }

            withTimeout(10000) {
                connectionReceived.await()
                while (service.proxyId == null) delay(10)
            }

            assertEquals(assignedId, service.proxyId)
            job.cancelAndJoin()
        } finally {
            server.stop(500, 500)
            tearDown()
        }
    }

    @Test
    fun `should reconnect after server-side close`() = runBlocking {
        val connectionCount = Channel<Int>(Channel.UNLIMITED)
        
        val server = embeddedServer(Netty, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/proxy/server") {
                    val count = connectionCount.receive()
                    if (count == 1) {
                        send(ProxyMessage.AssignedId("id-1").toFrame())
                        delay(100)
                        close(CloseReason(CloseReason.Codes.SERVICE_RESTART, "Reconnecting"))
                    } else {
                        send(ProxyMessage.AssignedId("id-2").toFrame())
                        delay(2000)
                    }
                }
            }
        }.start(wait = false)

        val port = server.engine.resolvedConnectors().first().port

        val config = MapApplicationConfig(
            "proxy.hostname" to "127.0.0.1",
            "proxy.controlPort" to port.toString(),
            "proxy.ssl" to "false"
        )

        val mockApp = mockk<Application>(relaxed = true)
        setupKoin(mockApp)

        try {
            val service = ReverseProxyService(config)
            val job = launch {
                service.startService()
            }

            connectionCount.send(1)
            
            withTimeout(10000) {
                while (service.proxyId != "id-1") delay(10)
            }
            
            connectionCount.send(2)
            
            withTimeout(10000) {
                while (service.proxyId != "id-2") delay(10)
            }

            assertEquals("id-2", service.proxyId)
            job.cancelAndJoin()
        } finally {
            server.stop(500, 500)
            tearDown()
        }
    }

    @Test
    fun `should retry after exception`() = runBlocking {
        val firstAttempt = CompletableDeferred<Unit>()
        val secondAttempt = CompletableDeferred<Unit>()
        var attempt = 0

        val server = embeddedServer(Netty, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/proxy/server") {
                    attempt++
                    if (attempt == 1) {
                        firstAttempt.complete(Unit)
                        close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "CRASH"))
                    } else {
                        send(ProxyMessage.AssignedId("ok").toFrame())
                        secondAttempt.complete(Unit)
                        delay(2000)
                    }
                }
            }
        }.start(wait = false)

        val port = server.engine.resolvedConnectors().first().port

        val config = MapApplicationConfig(
            "proxy.hostname" to "127.0.0.1",
            "proxy.controlPort" to port.toString()
        )

        val mockApp = mockk<Application>(relaxed = true)
        setupKoin(mockApp)

        try {
            val service = ReverseProxyService(config)
            val job = launch {
                service.startService()
            }

            withTimeout(10000) {
                firstAttempt.await()
            }

            withTimeout(15000) {
                while (service.proxyId == null) delay(10)
            }

            assertEquals("ok", service.proxyId)
            job.cancelAndJoin()
        } finally {
            server.stop(500, 500)
            tearDown()
        }
    }

    @Test
    fun `should handle NewClient and ClientFrame`() = runBlocking {
        val clientId = UUID.randomUUID()
        val connectionReceived = CompletableDeferred<Unit>()

        val server = embeddedServer(Netty, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/proxy/server") {
                    send(ProxyMessage.AssignedId("server-1").toFrame())
                    send(ProxyMessage.NewClient(clientId, "/rpc", mapOf("Authorization" to "Bearer valid-token")).toFrame())
                    send(ProxyMessage.ClientFrame(clientId, "test-data".toByteArray(), false).toFrame())
                    connectionReceived.complete(Unit)
                    delay(2000)
                }
            }
        }.start(wait = false)

        val port = server.engine.resolvedConnectors().first().port

        val config = MapApplicationConfig(
            "proxy.hostname" to "127.0.0.1",
            "proxy.controlPort" to port.toString()
        )

        val mockApp = mockk<Application>(relaxed = true)
        setupKoin(mockApp)

        try {
            val service = ReverseProxyService(config)
            val job = launch {
                service.startService()
            }

            withTimeout(10000) {
                connectionReceived.await()
                while (service.proxyId == null) delay(10)
            }

            assertEquals("server-1", service.proxyId)
            delay(1000)
            job.cancelAndJoin()
        } finally {
            server.stop(500, 500)
            tearDown()
        }
    }
}
