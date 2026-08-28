package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class UserPlaylistAuthorizationTest : KoinTest {
    private lateinit var database: Database

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "playlist_auth")
        transaction(database) {
            SchemaUtils.create(
                UserTable, ImageTable, UserPlaylistTable, SongTable, SongVariantTable, 
                UserPlaylistSongTable, SongMusicBrainzTable, MBReleaseTable,
                AlbumTable, ArtistTable, SongArtistTable, AlbumArtistTable
            )
        }

        startKoin {
            modules(module {
                single { mockk<ApplicationEnvironment>(relaxed = true) }
                single { mockk<ImageService>(relaxed = true) }
                single { SongService() }
            })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `allPlaylists with null creator should return all playlists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service = UserPlaylistService()
        val user1Id = UUID.randomUUID()
        val user2Id = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = user1Id; it[username] = "user1"; it[passwordHash] = "" }
            UserTable.insert { it[id] = user2Id; it[username] = "user2"; it[passwordHash] = "" }
            
            UserPlaylistTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "User 1 Playlist"
                it[description] = ""
                it[creator] = user1Id
            }
            UserPlaylistTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "User 2 Playlist"
                it[description] = ""
                it[creator] = user2Id
            }
        }

        val result = service.allPlaylists(null, 0, 10)
        assertEquals(2, result.data.size, "Should return playlists from both users when creator is null")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `allPlaylists with specific creator should filter results`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service = UserPlaylistService()
        val user1Id = UUID.randomUUID()
        val user2Id = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = user1Id; it[username] = "user1"; it[passwordHash] = "" }
            UserTable.insert { it[id] = user2Id; it[username] = "user2"; it[passwordHash] = "" }
            
            UserPlaylistTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "User 1 Playlist"
                it[description] = ""
                it[creator] = user1Id
            }
            UserPlaylistTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "User 2 Playlist"
                it[description] = ""
                it[creator] = user2Id
            }
        }

        val result = service.allPlaylists(user1Id, 0, 10)
        assertEquals(1, result.data.size)
        assertEquals("User 1 Playlist", result.data.first().name)
    }
}
