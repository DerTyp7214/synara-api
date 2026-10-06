@file:OptIn(ExperimentalRpcApi::class)

package dev.dertyp.core

import dev.dertyp.core.wire.fixtures.IWireDefaultsService
import dev.dertyp.data.Album
import dev.dertyp.data.ApiVersion
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.User
import dev.dertyp.serializers.AppCbor
import dev.dertyp.services.AlbumRpcService
import dev.dertyp.services.AlbumService
import dev.dertyp.services.IAlbumService
import io.ktor.client.request.header
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.rpc.RpcCall
import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.descriptor.RpcCallable
import kotlinx.rpc.descriptor.RpcParameter
import kotlinx.rpc.descriptor.RpcServiceDescriptor
import kotlinx.rpc.descriptor.serviceDescriptorOf
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlinx.rpc.krpc.ktor.client.KtorRpcClient
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.rpc.withService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

private class OldCallable<@Rpc T : Any>(real: RpcCallable<T>, kept: Int) : RpcCallable<T> by real {
    override val parameters: Array<out RpcParameter> = real.parameters.copyOfRange(0, kept)
}

private class OldSignatures<@Rpc T : Any>(
    private val real: RpcServiceDescriptor<T>,
    private val kept: Map<String, Int>,
) : RpcServiceDescriptor<T> by real {
    override fun getCallable(name: String): RpcCallable<T>? {
        val callable = real.getCallable(name) ?: return null
        return kept[name]?.let { OldCallable(callable, it) } ?: callable
    }
}

class KrpcMissingDefaultArgumentsTest {
    private val artistId = UUID.fromString("00000000-0000-0000-0000-000000000201")

    private val user = User(id = UUID.fromString("00000000-0000-0000-0000-000000000202"), username = "listener")

    private val page = PaginatedResponse<Album>(data = emptyList(), total = 0, pageSize = 25)

    private val albumService = mockk<AlbumService>()
    private val defaults = mockk<IWireDefaultsService>()

    private val releasedAlbums = OldSignatures(
        serviceDescriptorOf<IAlbumService>(),
        mapOf("allAlbums" to 2, "byArtist" to 4),
    )

    private val releasedDefaults = OldSignatures(
        serviceDescriptorOf<IWireDefaultsService>(),
        mapOf("annotate" to 1, "toggle" to 1),
    )

    private fun connected(version: String, calls: suspend (KtorRpcClient) -> Unit) = testApplication {
        coEvery { albumService.allAlbumsGrouped(any(), any(), any(), any()) } returns page
        coEvery { albumService.byArtistGrouped(any(), any(), any(), any(), any(), any()) } returns page
        coEvery { defaults.annotate(any(), any()) } returns "annotated"
        coEvery { defaults.toggle(any(), any()) } returns "toggled"
        application {
            install(WebSockets)
        }
        routing {
            rpc("/rpc") {
                rpcConfig { serialization { cborFor(call.clientInfo) } }
                registerService(IAlbumService::class) { AlbumRpcService(user, albumService) }
                registerService(IWireDefaultsService::class) { defaults }
            }
        }

        val client = createClient {
            installKrpc {
                serialization { cbor(AppCbor) }
            }
        }
        calls(client.rpc("/rpc") { header(ApiVersion.HEADER, version) })
    }

    private fun releasedClientGetsTheExplicitEdition(version: String) = connected(version) { rpc ->
        assertEquals(
            page,
            rpc.call<PaginatedResponse<Album>>(RpcCall(releasedAlbums, "allAlbums", arrayOf(1, 25), 1)),
        )
        assertEquals(
            page,
            rpc.call<PaginatedResponse<Album>>(
                RpcCall(releasedAlbums, "byArtist", arrayOf(2, 25, artistId, true), 1),
            ),
        )

        coVerify(exactly = 1) { albumService.allAlbumsGrouped(1, 25, true, user.id) }
        coVerify(exactly = 1) { albumService.byArtistGrouped(2, 25, artistId, true, true, user.id) }
    }

    private fun currentClientGetsTheCleanEdition(version: String) = connected(version) { rpc ->
        val albums = rpc.withService<IAlbumService>()

        assertEquals(page, albums.allAlbums(1, 25, explicit = false))
        assertEquals(page, albums.byArtist(2, 25, artistId, singles = true, explicit = false))

        coVerify(exactly = 1) { albumService.allAlbumsGrouped(1, 25, false, user.id) }
        coVerify(exactly = 1) { albumService.byArtistGrouped(2, 25, artistId, true, false, user.id) }
    }

    private fun missingNullableArgumentArrivesAsNull(version: String) = connected(version) { rpc ->
        assertEquals("annotated", rpc.call<String>(RpcCall(releasedDefaults, "annotate", arrayOf(7), 2)))

        coVerify(exactly = 1) { defaults.annotate(7, null) }
    }

    private fun missingNonNullArgumentFailsTheCall(version: String) = connected(version) { rpc ->
        val failure = try {
            rpc.call<String>(RpcCall(releasedDefaults, "toggle", arrayOf(7), 2))
            null
        } catch (e: Throwable) {
            e
        }

        assertEquals(
            "java.lang.NullPointerException: null cannot be cast to non-null type kotlin.Boolean",
            failure?.toString(),
        )
        coVerify(exactly = 0) { defaults.toggle(any(), any()) }
    }

    @Test
    fun `a released client below api version 8 that sends no explicit argument gets the explicit edition preferred`() =
        releasedClientGetsTheExplicitEdition("7")

    @Test
    fun `a released client from api version 8 that sends no explicit argument gets the explicit edition preferred`() =
        releasedClientGetsTheExplicitEdition("8")

    @Test
    fun `a client below api version 8 that sends explicit false gets the clean edition preferred`() =
        currentClientGetsTheCleanEdition("7")

    @Test
    fun `a client from api version 8 that sends explicit false gets the clean edition preferred`() =
        currentClientGetsTheCleanEdition("8")

    @Test
    fun `a missing nullable argument of a client below api version 8 arrives as null and not as its default`() =
        missingNullableArgumentArrivesAsNull("7")

    @Test
    fun `a missing nullable argument of a client from api version 8 arrives as null and not as its default`() =
        missingNullableArgumentArrivesAsNull("8")

    @Test
    fun `a missing non-null argument of a client below api version 8 fails the call before the service`() =
        missingNonNullArgumentFailsTheCall("7")

    @Test
    fun `a missing non-null argument of a client from api version 8 fails the call before the service`() =
        missingNonNullArgumentFailsTheCall("8")
}
