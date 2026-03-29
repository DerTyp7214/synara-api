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

class UserPlaylistServiceTest {
    private lateinit var database: Database
    private lateinit var service: UserPlaylistService

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "user_playlist_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                UserPlaylistTable,
                UserPlaylistSongTable,
                SongTable,
                AlbumTable,
                ArtistTable,
                SongArtistTable,
                AlbumArtistTable,
                ImageTable
            )
        }
        service = UserPlaylistService()
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId should return user playlist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val playlistId = UUID.randomUUID()
        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "testuser"
                it[passwordHash] = "hash"
            }
            UserPlaylistTable.insert {
                it[id] = playlistId
                it[name] = "My Playlist"
                it[description] = ""
                it[creator] = userId
            }
        }

        val playlist = service.byId(playlistId)
        assertNotNull(playlist)
        assertEquals("My Playlist", playlist?.name)
    }
}
