@file:OptIn(ExperimentalSerializationApi::class)

package dev.dertyp.core

import dev.dertyp.core.wire.OldClientCbor
import dev.dertyp.core.wire.newSong
import dev.dertyp.core.wire.oldSong
import dev.dertyp.data.ApiVersion
import dev.dertyp.data.ArtistPlaylistSortStrategy
import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.UserPlaylist
import dev.dertyp.data.UserSong
import dev.dertyp.serializers.AppCbor
import dev.dertyp.services.ICollectionService
import dev.dertyp.services.ILyricsService
import dev.dertyp.services.ISongService
import dev.dertyp.services.IUserPlaylistService
import io.ktor.client.request.header
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.KrpcSerialFormat
import kotlinx.rpc.krpc.serialization.KrpcSerialFormatBuilder
import kotlinx.rpc.withService
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.cbor.CborBuilder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.UUID
import dev.dertyp.core.wire.old.UserSong as OldUserSong

private class PlainClientFormat(val cbor: Cbor, val received: MutableList<Pair<String, ByteArray>>) : BinaryFormat {
    override val serializersModule: SerializersModule get() = cbor.serializersModule

    override fun <T> encodeToByteArray(serializer: SerializationStrategy<T>, value: T): ByteArray =
        cbor.encodeToByteArray(serializer, value)

    override fun <T> decodeFromByteArray(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
        received += deserializer.descriptor.serialName to bytes
        return cbor.decodeFromByteArray(deserializer, bytes)
    }
}

private object PlainClientKrpcFormat : KrpcSerialFormat<PlainClientFormat, CborBuilder> {
    override fun withBuilder(from: PlainClientFormat?, builderConsumer: CborBuilder.() -> Unit): PlainClientFormat {
        val base = requireNotNull(from)
        return PlainClientFormat(Cbor(base.cbor, builderConsumer), base.received)
    }

    override fun CborBuilder.applySerializersModule(serializersModule: SerializersModule) {
        this.serializersModule = this.serializersModule + serializersModule
    }
}

class KrpcCallShapesTest {
    private fun uuid(n: Int): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(n))

    private val songId = uuid(101)
    private val playlistId = uuid(102)
    private val otherPlaylistId = uuid(103)
    private val creatorId = uuid(104)
    private val imageId = uuid(105)
    private val userId = uuid(106)
    private val createdId = uuid(107)
    private val artistId = uuid(108)
    private val albumId = uuid(109)
    private val sourcePlaylistId = uuid(110)
    private val collectionId = uuid(111)
    private val collectionSongIds = listOf(uuid(112), uuid(113), uuid(114))

    private val playlist = UserPlaylist(
        id = playlistId,
        name = "Mix",
        songs = listOf(songId),
        creator = creatorId,
        description = "A mix",
        imageId = imageId,
    )
    private val page = PaginatedResponse(data = listOf(playlist), total = 1, pageSize = 50)
    private val insertable = InsertablePlaylist(name = "New", description = "Fresh", songPaths = listOf("/a.flac"))

    private val lyrics = mockk<ILyricsService>()
    private val playlists = mockk<IUserPlaylistService>()
    private val collections = mockk<ICollectionService>()
    private val songs = mockk<ISongService>()

    private fun stubServices() {
        coEvery { lyrics.getSyncedLyrics(any()) } returns null
        coEvery { playlists.byId(any()) } returns playlist
        coEvery { playlists.byIds(any()) } returns listOf(playlist)
        coEvery { playlists.allPlaylists(any(), any(), any()) } returns page
        coEvery { playlists.rankedSearch(any(), any(), any(), any()) } returns page
        coEvery { playlists.setPlaylistImage(any(), any()) } returns true
        coEvery { playlists.getOrAddPlaylist(any(), any(), any()) } returns createdId
        coEvery { playlists.createPlaylistFromArtists(any(), any(), any(), any(), any()) } returns createdId
        coEvery { playlists.addAlbumToPlaylist(any(), any()) } just Runs
        coEvery { playlists.addPlaylistToPlaylist(any(), any()) } just Runs
        every { collections.songIds(any()) } returns flowOf(*collectionSongIds.toTypedArray())
        coEvery { songs.byId(any()) } returns newSong
        coEvery { songs.byIds(any()) } returns listOf(newSong)
    }

    private fun lastReceived(received: List<Pair<String, ByteArray>>, serialName: String): ByteArray =
        received.last { it.first == serialName }.second

    private fun callEveryShape(
        version: String,
        clientCbor: Cbor,
        checkSongs: (UserSong?, List<UserSong>, List<Pair<String, ByteArray>>) -> Unit
    ) =
        testApplication {
            stubServices()
            application {
                install(WebSockets)
            }
            routing {
                rpc("/rpc") {
                    rpcConfig { serialization { cborFor(call.clientInfo) } }
                    registerService(ILyricsService::class) { lyrics }
                    registerService(IUserPlaylistService::class) { playlists }
                    registerService(ICollectionService::class) { collections }
                    registerService(ISongService::class) { songs }
                }
            }

            val received: MutableList<Pair<String, ByteArray>> = Collections.synchronizedList(mutableListOf())
            val client = createClient {
                installKrpc {
                    serialization {
                        register(
                            KrpcSerialFormatBuilder.Binary(
                                PlainClientKrpcFormat,
                                PlainClientFormat(clientCbor, received)
                            ) {})
                    }
                }
            }
            val rpc = client.rpc("/rpc") { header(ApiVersion.HEADER, version) }
            val lyricsApi = rpc.withService<ILyricsService>()
            val playlistApi = rpc.withService<IUserPlaylistService>()
            val collectionApi = rpc.withService<ICollectionService>()
            val songApi = rpc.withService<ISongService>()

            assertNull(lyricsApi.getSyncedLyrics(songId))
            assertEquals(playlist, playlistApi.byId(playlistId))
            assertEquals(listOf(playlist), playlistApi.byIds(listOf(playlistId, otherPlaylistId)))
            assertEquals(page, playlistApi.allPlaylists(null))
            assertEquals(page, playlistApi.rankedSearch(creatorId, query = "mix"))
            assertTrue(playlistApi.setPlaylistImage(playlistId, imageId))
            assertTrue(playlistApi.setPlaylistImage(otherPlaylistId, null))
            assertEquals(createdId, playlistApi.getOrAddPlaylist(userId, "custom", insertable))
            assertEquals(
                createdId,
                playlistApi.createPlaylistFromArtists(
                    userId,
                    "Artists",
                    listOf(artistId),
                    sortStrategy = ArtistPlaylistSortStrategy.SHUFFLED
                ),
            )
            playlistApi.addAlbumToPlaylist(playlistId, albumId)
            playlistApi.addPlaylistToPlaylist(playlistId, sourcePlaylistId)
            assertEquals(collectionSongIds, collectionApi.songIds(collectionId).toList())
            val song = songApi.byId(songId)
            val songList = songApi.byIds(listOf(songId))

            coVerify(exactly = 1) { lyrics.getSyncedLyrics(songId) }
            coVerify(exactly = 1) { playlists.byId(playlistId) }
            coVerify(exactly = 1) { playlists.byIds(listOf(playlistId, otherPlaylistId)) }
            coVerify(exactly = 1) { playlists.allPlaylists(null, 0, 50) }
            coVerify(exactly = 1) { playlists.rankedSearch(creatorId, 0, 50, "mix") }
            coVerify(exactly = 1) { playlists.setPlaylistImage(playlistId, imageId) }
            coVerify(exactly = 1) { playlists.setPlaylistImage(otherPlaylistId, null) }
            coVerify(exactly = 1) { playlists.getOrAddPlaylist(userId, "custom", insertable) }
            coVerify(exactly = 1) {
                playlists.createPlaylistFromArtists(
                    userId,
                    "Artists",
                    listOf(artistId),
                    10,
                    ArtistPlaylistSortStrategy.SHUFFLED
                )
            }
            coVerify(exactly = 1) { playlists.addAlbumToPlaylist(playlistId, albumId) }
            coVerify(exactly = 1) { playlists.addPlaylistToPlaylist(playlistId, sourcePlaylistId) }
            verify(exactly = 1) { collections.songIds(collectionId) }
            coVerify(exactly = 1) { songs.byId(songId) }
            coVerify(exactly = 1) { songs.byIds(listOf(songId)) }

            checkSongs(song, songList, received.toList())
        }

    @Test
    fun `old clients below api version 8 reach every call shape with their arguments intact`() =
        callEveryShape("7", OldClientCbor) { _, _, received ->
            val songBytes = lastReceived(received, UserSong.serializer().nullable.descriptor.serialName)
            assertEquals(oldSong, OldClientCbor.decodeFromByteArray(OldUserSong.serializer().nullable, songBytes))
            val listBytes = lastReceived(received, ListSerializer(UserSong.serializer()).descriptor.serialName)
            assertEquals(
                listOf(oldSong),
                OldClientCbor.decodeFromByteArray(ListSerializer(OldUserSong.serializer()), listBytes)
            )
        }

    @Test
    fun `clients from api version 8 reach every call shape with their arguments intact`() =
        callEveryShape("8", AppCbor) { song, songList, _ ->
            assertEquals(newSong, song)
            assertEquals(listOf(newSong), songList)
        }
}
