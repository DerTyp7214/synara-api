package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
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

class DbManagementServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: DbManagementService

    private val allTables = arrayOf(
        UserTable,
        SongTable,
        AlbumTable,
        ImageTable,
        ArtistTable,
        FavSyncTable,
        SessionTable,
        PlaylistTable,
        UserSongTable,
        SongArtistTable,
        AlbumArtistTable,
        ArtistAliasTable,
        SyncServiceTable,
        PlaylistSongTable,
        RefreshTokenTable,
        UserPlaylistTable,
        TranscodedSongTable,
        UserPlaylistSongTable,
        ArtistMusicBrainzTable,
        AlbumMusicBrainzTable,
        SongMusicBrainzTable,
        ArtistSplitAliasTable,
        ScheduledTaskLogTable,
        FollowedArtistTable,
        RecentReleaseTable
    )

    fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                single { mockk<ImageService>(relaxed = true) }
            })
        }

        database = TestDatabase.connect(dialect, "db_mgmt_test")
        transaction(database) {
            SchemaUtils.create(*allTables)
        }
        service = DbManagementService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `export and import should preserve data`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        
        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "testuser"
                it[passwordHash] = "hash"
            }
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Test Artist"
            }
        }

        val exportedData = service.exportData()

        transaction(database) {
            SchemaUtils.drop(*allTables)
            SchemaUtils.create(*allTables)
        }
        
        transaction(database) {
            assertEquals(0, UserTable.selectAll().count())
            assertEquals(0, ArtistTable.selectAll().count())
        }

        service.importData(exportedData)

        transaction(database) {
            val users = UserTable.selectAll().toList()
            assertEquals(1, users.size)
            assertEquals("testuser", users[0][UserTable.username])
            assertEquals(userId, users[0][UserTable.id].value)

            val artists = ArtistTable.selectAll().toList()
            assertEquals(1, artists.size)
            assertEquals("Test Artist", artists[0][ArtistTable.name])
            assertEquals(artistId, artists[0][ArtistTable.id].value)
        }
    }
}
