package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.Playlist
import dev.dertyp.db.*
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
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
            })
        }

        database = TestDatabase.connect(dialect, "playlist_test")
        transaction(database) {
            SchemaUtils.create(
                PlaylistTable,
                PlaylistSongTable,
                SongTable,
                AlbumTable,
                ArtistTable,
                SongArtistTable,
                AlbumArtistTable,
                ImageTable
            )
        }
        service = PlaylistService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
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
}
