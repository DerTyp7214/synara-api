package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.Playlist
import dev.dertyp.db.*
import dev.dertyp.testing.entityChangeTables
import dev.dertyp.testing.entityEventsModule
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class PlaylistServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: PlaylistService

    fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                single { mockk<ImageService>(relaxed = true) }
                includes(entityEventsModule())
            })
        }

        database = TestDatabase.connect(
            dialect, "playlist_test",
            PlaylistTable,
            PlaylistSongTable,
            SongTable,
            SongVariantTable,
            SongTitleTagTable,
            AlbumTitleTagTable,
            AlbumTable,
            ArtistTable,
            SongArtistTable,
            AlbumArtistTable,
            ImageTable,
            ImageMetadataTable,
            *entityChangeTables,
        )
        service = PlaylistService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId should return playlist with cover blurHash`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val playlistId = UUID.randomUUID()
        val imageId = UUID.randomUUID()
        transaction(database) {
            ImageTable.insert {
                it[id] = imageId
                it[path] = "test.jpg"
                it[imageHash] = "hash"
                it[origin] = "test"
                it[blurHash] = "playlist_blurhash"
            }
            PlaylistTable.insert {
                it[id] = playlistId
                it[name] = "Playlist with Cover"
                it[PlaylistTable.imageId] = imageId
            }
        }

        val playlist = service.byId(playlistId)
        assertNotNull(playlist)
        assertEquals(imageId, playlist?.imageId)
        assertEquals("playlist_blurhash", playlist?.blurHash)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId should return playlist with songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val playlistId = UUID.randomUUID()
        val songId = UUID.randomUUID()
        transaction(database) {
            PlaylistTable.insert {
                it[id] = playlistId
                it[name] = "Test Playlist"
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Song"
                it[albumId] = UUID.randomUUID().also { albumId ->
                    AlbumTable.insert { album -> album[id] = albumId; album[name] = "Album" }
                }
            }
            PlaylistSongTable.insert {
                it[PlaylistSongTable.playlistId] = playlistId
                it[PlaylistSongTable.songId] = songId
                it[position] = 1
            }
        }

        val playlist = service.byId(playlistId)
        assertNotNull(playlist)
        assertEquals("Test Playlist", playlist?.name)
        assertEquals(1, playlist?.songs?.size)
        assertEquals(songId, playlist?.songs?.first())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch should handle new playlists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = UUID.randomUUID()
        val songPath = "/path/to/song.mp3"
        transaction(database) {
            SongTable.insert {
                it[id] = songId
                it[title] = "Song"
                it[filePath] = songPath
                it[albumId] = UUID.randomUUID().also { albumId ->
                    AlbumTable.insert { album -> album[id] = albumId; album[name] = "Album" }
                }
            }
        }

        val playlists = listOf(
            InsertablePlaylist("New Playlist", songPaths = listOf(songPath))
        )

        val result = service.createBatch(playlists)
        assertEquals(1, result.size)

        val playlist = service.byId(result[0])
        assertNotNull(playlist)
        assertEquals("New Playlist", playlist?.name)
        assertEquals(1, playlist?.songs?.size)
        assertEquals(songId, playlist?.songs?.first())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch should keep the id of a playlist with the same name and replace its songs`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val firstSong = UUID.randomUUID()
            val secondSong = UUID.randomUUID()
            val otherPlaylist = UUID.randomUUID()
            transaction(database) {
                val album = UUID.randomUUID()
                AlbumTable.insert {
                    it[id] = album
                    it[name] = "Album"
                }
                for ((song, path) in listOf(firstSong to "/path/first.mp3", secondSong to "/path/second.mp3")) {
                    SongTable.insert {
                        it[id] = song
                        it[title] = "Song"
                        it[filePath] = path
                        it[albumId] = album
                    }
                }
                PlaylistTable.insert {
                    it[id] = otherPlaylist
                    it[name] = "Other"
                }
                PlaylistSongTable.insert {
                    it[playlistId] = otherPlaylist
                    it[songId] = firstSong
                    it[position] = 1
                }
            }

            val created = service.createBatch(listOf(InsertablePlaylist("Mix", songPaths = listOf("/path/first.mp3"))))
            val again = service.createBatch(
                listOf(
                    InsertablePlaylist(
                        "Mix",
                        songPaths = listOf("/path/second.mp3", "/path/missing.mp3", "/path/first.mp3", "/path/second.mp3")
                    )
                )
            )

            assertEquals(created, again)
            assertEquals(listOf(secondSong, firstSong), service.byId(created.single())?.songs)
            assertEquals(
                listOf(secondSong to 1, firstSong to 2),
                transaction(database) {
                    PlaylistSongTable.selectAll()
                        .where { PlaylistSongTable.playlistId eq created.single() }
                        .orderBy(PlaylistSongTable.position)
                        .map { it[PlaylistSongTable.songId].value to it[PlaylistSongTable.position] }
                }
            )
            assertEquals(2L, transaction(database) { PlaylistTable.selectAll().count() })
            assertEquals(listOf(firstSong), service.byId(otherPlaylist)?.songs)
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch should keep one playlist of several with the same name and delete the others with their songs`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val song = UUID.randomUUID()
        val duplicates = List(3) { UUID.randomUUID() }
        val other = UUID.randomUUID()
        transaction(database) {
            val album = UUID.randomUUID()
            AlbumTable.insert {
                it[id] = album
                it[name] = "Album"
            }
            SongTable.insert {
                it[id] = song
                it[title] = "Song"
                it[filePath] = "/path/song.mp3"
                it[albumId] = album
            }
            for ((playlist, playlistName) in duplicates.map { it to "Mix" } + (other to "Other")) {
                PlaylistTable.insert {
                    it[id] = playlist
                    it[name] = playlistName
                }
                PlaylistSongTable.insert {
                    it[playlistId] = playlist
                    it[songId] = song
                    it[position] = 1
                }
            }
        }
        val kept = duplicates.minBy { it.toString() }

        val result = service.createBatch(listOf(InsertablePlaylist("Mix", songPaths = listOf("/path/song.mp3"))))

        assertEquals(listOf(kept), result)
        assertEquals(
            setOf(kept, other),
            transaction(database) { PlaylistTable.selectAll().map { it[PlaylistTable.id].value }.toSet() }
        )
        assertEquals(
            setOf(kept, other),
            transaction(database) { PlaylistSongTable.selectAll().map { it[PlaylistSongTable.playlistId].value }.toSet() }
        )
        assertEquals(listOf(song), service.byId(kept)?.songs)
        assertEquals(listOf(song), service.byId(other)?.songs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `upsertPlaylist should update existing playlist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val playlistId = UUID.randomUUID()
        transaction(database) {
            PlaylistTable.insert {
                it[id] = playlistId
                it[name] = "Original"
            }
        }

        val updated = Playlist(playlistId, "Updated", emptyList())
        service.upsertPlaylist(updated)

        val fromDb = service.byId(playlistId)
        assertEquals("Updated", fromDb?.name)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `allPlaylists pages past the first page and allPlaylistsFlow emits every playlist`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val ids = (1..105).map { UUID.randomUUID() }
            transaction(database) {
                ids.forEachIndexed { index, playlistId ->
                    PlaylistTable.insert {
                        it[id] = playlistId
                        it[name] = "Playlist $index"
                    }
                }
            }

            val second = service.allPlaylists(1, 50)
            assertEquals(50, second.data.size)
            assertEquals(105, second.total)
            assertEquals(true, second.hasNextPage)

            val last = service.allPlaylists(2, 50)
            assertEquals(5, last.data.size)
            assertEquals(false, last.hasNextPage)

            val emitted = service.allPlaylistsFlow().toList().map { it.id }
            assertEquals(105, emitted.size)
            assertEquals(ids.toSet(), emitted.toSet())
        }
}
