package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
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
import java.util.UUID

class PlaylistServiceTest {
    private lateinit var database: Database
    private lateinit var service: PlaylistService

    fun setup(dialect: DbDialect) {
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
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId should return playlist with songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val playlistId = UUID.randomUUID()
        transaction(database) {
            PlaylistTable.insert {
                it[id] = playlistId
                it[name] = "Test Playlist"
            }
        }

        val playlist = service.byId(playlistId)
        assertNotNull(playlist)
        assertEquals("Test Playlist", playlist?.name)
    }
}
