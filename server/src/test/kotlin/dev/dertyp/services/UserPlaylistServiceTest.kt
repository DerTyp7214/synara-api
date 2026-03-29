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

class UserPlaylistServiceTest {
    private lateinit var database: Database
    private lateinit var service: UserPlaylistService

    @BeforeEach
    fun setup() {
        database = Database.connect("jdbc:h2:mem:user_playlist_test_${UUID.randomUUID().toString().replace("-", "")};MODE=MYSQL;DB_CLOSE_DELAY=-1", "org.h2.Driver")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                UserPlaylistTable,
                UserPlaylistSongTable,
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
        service = UserPlaylistService()
    }

    @AfterEach
    fun tearDown() {
    }

    @Test
    fun `byId should return user playlist if it exists`() = runBlocking {
        val userId = UUID.randomUUID()
        val playlistId = UUID.randomUUID()
        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "testuser"
                it[passwordHash] = "hash"
            }
            UserPlaylistTable.insert {
                it[UserPlaylistTable.id] = playlistId
                it[name] = "My Favorites"
                it[creator] = userId
                it[description] = "desc"
                it[origin] = "test"
            }
        }

        val playlist = service.byId(playlistId)
        assertNotNull(playlist)
        assertEquals(playlistId, playlist?.id)
        assertEquals("My Favorites", playlist?.name)
        assertEquals(userId, playlist?.creator)
    }
}
