package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.services.ScheduledTaskLogService
import dev.dertyp.services.schedule.SearchIndexRebuildWorker
import io.mockk.every
import io.mockk.mockk
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class RebuildSearchIndexTest : KoinTest {
    private lateinit var database: Database

    fun setup(dialect: DbDialect) {
        val logService = mockk<ScheduledTaskLogService>(relaxed = true)
        every { logService.startLog(any(), any()) } returns EntityID(UUID.randomUUID(), ScheduledTaskLogTable)

        val config = MapApplicationConfig(
            "workers.threadMultiplier" to "1.0"
        )

        startKoin {
            modules(module {
                single { logService }
                single<ApplicationConfig> { config }
            })
        }

        database = TestDatabase.connect(dialect, "rebuild_search_index_test")
        transaction(database) {
            SchemaUtils.create(
                ImageTable,
                ArtistTable,
                AlbumTable,
                SongTable,
                SearchIndexQueueTable
            )
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `migration and worker should queue existing entries for rebuild`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val songId1 = UUID.randomUUID()
        val songId2 = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Test Artist"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Test Album"
            }
            SongTable.insert {
                it[id] = songId1
                it[title] = "Song 1"
                it[this.albumId] = albumId
            }
            SongTable.insert {
                it[id] = songId2
                it[title] = "Song 2"
                it[this.albumId] = albumId
            }

            SearchIndexQueueTable.deleteAll()
        }

        val worker = SearchIndexRebuildWorker()
        val results = worker.run()

        assertEquals(2, results["queuedSongs"])
        assertEquals(1, results["queuedAlbums"])
        assertEquals(1, results["queuedArtists"])

        transaction(database) {
            val queuedItems = SearchIndexQueueTable.selectAll().toList()
            assertEquals(4, queuedItems.size, "Should queue exactly 4 items (2 songs, 1 album, 1 artist)")

            val queuedTypesAndIds = queuedItems.map {
                it[SearchIndexQueueTable.entityType] to it[SearchIndexQueueTable.entityId]
            }

            assertTrue(queuedTypesAndIds.contains(SearchIndexEntityType.SONG to songId1))
            assertTrue(queuedTypesAndIds.contains(SearchIndexEntityType.SONG to songId2))
            assertTrue(queuedTypesAndIds.contains(SearchIndexEntityType.ALBUM to albumId))
            assertTrue(queuedTypesAndIds.contains(SearchIndexEntityType.ARTIST to artistId))
        }
    }
}
