package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
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

    private fun insertPlaylists(count: Int): UUID {
        val userId = UUID.randomUUID()
        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "pager"; it[passwordHash] = "" }
            (1..count).forEach { index ->
                UserPlaylistTable.insert {
                    it[id] = UUID.randomUUID()
                    it[name] = "Playlist %03d".format(index)
                    it[description] = ""
                    it[creator] = userId
                }
            }
        }
        return userId
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `allPlaylists returns the requested page with the full total`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service = UserPlaylistService()
        insertPlaylists(5)

        val first = service.allPlaylists(null, 0, 2)
        assertEquals(listOf("Playlist 001", "Playlist 002"), first.data.map { it.name })
        assertEquals(5, first.total)
        assertEquals(true, first.hasNextPage)

        val second = service.allPlaylists(null, 1, 2)
        assertEquals(listOf("Playlist 003", "Playlist 004"), second.data.map { it.name })
        assertEquals(5, second.total)
        assertEquals(true, second.hasNextPage)

        val last = service.allPlaylists(null, 2, 2)
        assertEquals(listOf("Playlist 005"), last.data.map { it.name })
        assertEquals(5, last.total)
        assertEquals(false, last.hasNextPage)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `allPlaylistsFlow emits every playlist beyond the first page`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service = UserPlaylistService()
        val creator = insertPlaylists(105)

        val names = service.allPlaylistsFlow(creator).toList().map { it.name }
        assertEquals((1..105).map { "Playlist %03d".format(it) }, names)
    }
}
