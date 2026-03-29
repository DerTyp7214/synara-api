package dev.dertyp.services

import dev.dertyp.db.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class PlaylistServiceTest {
    private lateinit var database: Database
    private lateinit var service: PlaylistService

    @BeforeEach
    fun setup() {
        database = Database.connect("jdbc:h2:mem:playlist_test_${UUID.randomUUID().toString().replace("-", "")};MODE=MYSQL;DB_CLOSE_DELAY=-1", "org.h2.Driver")
        transaction(database) {
            SchemaUtils.create(
                PlaylistTable,
                PlaylistSongTable,
                SongTable,
                AlbumTable,
                ArtistTable,
                ArtistMusicBrainzTable,
                ArtistAliasTable,
                AlbumMusicBrainzTable,
                SongMusicBrainzTable,
                ImageTable
            )
        }
        service = PlaylistService()
    }

    @AfterEach
    fun tearDown() {
    }

    @Test
    fun `byId should return playlist if it exists`() = runBlocking {
        val id = UUID.randomUUID()
        transaction(database) {
            PlaylistTable.insert {
                it[PlaylistTable.id] = id
                it[name] = "Test Playlist"
            }
        }

        val playlist = service.byId(id)
        assertNotNull(playlist)
        assertEquals(id, playlist?.id)
        assertEquals("Test Playlist", playlist?.name)
    }

    @Test
    fun `allPlaylists should return all created playlists`() = runBlocking {
        transaction(database) {
            PlaylistTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Playlist 1"
            }
            PlaylistTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Playlist 2"
            }
        }

        val result = service.allPlaylists(0, 10)
        assertEquals(2, result.data.size)
    }
}
