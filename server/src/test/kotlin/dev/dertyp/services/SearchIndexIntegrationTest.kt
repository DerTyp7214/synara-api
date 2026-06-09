package dev.dertyp.services

import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class SearchIndexIntegrationTest : KoinTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager

    private val artistService = mockk<ArtistService>(relaxed = true)
    private val albumService = mockk<AlbumService>(relaxed = true)
    private val imageService = mockk<ImageService>(relaxed = true)
    private val genreService = mockk<GenreService>(relaxed = true)

    fun setup() {
        val container = TestDatabase.postgresContainer
        assertNotNull(container, "PostgreSQL testcontainer must be available")

        val dbName = "integration_test_${UUID.randomUUID().toString().replace("-", "")}".lowercase()
        val dbUrl = TestDatabase.getPostgresDbUrl(dbName)
        
        val config = MapApplicationConfig(
            "storage.driverClassName" to "org.postgresql.Driver",
            "storage.jdbcURL" to dbUrl,
            "storage.user" to container!!.username,
            "storage.password" to container.password,
            "client.id" to "test-client",
            "client.secret" to "test-secret"
        )
        
        val environment = mockk<ApplicationEnvironment>()
        every { environment.config } returns config
        
        databaseManager = DatabaseManager(environment)
        
        database = Database.connect(
            url = dbUrl,
            driver = "org.postgresql.Driver",
            user = container.username,
            password = container.password
        )

        startKoin {
            modules(module {
                single { environment }
                single { databaseManager }
                single { mockk<MusicBrainzService>(relaxed = true) }
                single { mockk<CachedMusicBrainzService>(relaxed = true) }
                single { mockk<MusicBrainzCacheService>(relaxed = true) }
                single { artistService }
                single { albumService }
                single { genreService }
                single { imageService }
                single { LibraryMergeService() }
                single { SearchIndexWorker() }
            })
        }

        databaseManager.init()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        if (::databaseManager.isInitialized) {
            databaseManager.close()
        }
        TestDatabase.cleanUp()
    }

    @Test
    fun `database triggers and search index worker integration test`() = runBlocking {
        if (TestDatabase.postgresContainer == null) {
            println("Skipping PostgreSQL integration test because Docker is not available.")
            return@runBlocking
        }
        
        setup()

        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "testuser"
                it[passwordHash] = ""
            }
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Infected Mushroom"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Converting Vegetarians"
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Deeply Disturbed"
                it[this.albumId] = albumId
            }
            SongArtistTable.insert {
                it[this.songId] = songId
                it[this.artistId] = artistId
            }
        }

        val queuedItems = transaction(database) {
            SearchIndexQueueTable.selectAll().toList()
        }
        
        assertTrue(queuedItems.isNotEmpty(), "Triggers should have added rows to the search index queue table")
        
        val queuedEntities = queuedItems.map { 
            it[SearchIndexQueueTable.entityType] to it[SearchIndexQueueTable.entityId] 
        }
        
        assertTrue(queuedEntities.contains(SearchIndexEntityType.SONG to songId), "Song should be queued")
        assertTrue(queuedEntities.contains(SearchIndexEntityType.ALBUM to albumId), "Album should be queued")
        assertTrue(queuedEntities.contains(SearchIndexEntityType.ARTIST to artistId), "Artist should be queued")

        transaction(database) {
            val songRow = SongTable.selectAll().where { SongTable.id eq songId }.single()
            assertNull(songRow[SongTable.searchVector], "search_vector should be null initially")
        }

        val worker = SearchIndexWorker()
        val processedCount = worker.processBatch()
        assertTrue(processedCount > 0, "Worker should have processed some items from the queue")

        transaction(database) {
            val queueSize = SearchIndexQueueTable.selectAll().count()
            assertEquals(0, queueSize, "Queue should be empty after processing")
        }

        transaction(database) {
            val songRow = SongTable.selectAll().where { SongTable.id eq songId }.single()
            assertNotNull(songRow[SongTable.searchVector], "search_vector should be populated by worker")
        }

        val songService = SongService()
        val result = songService.rankedSearch(0, 10, "Disturbed", true, userId)
        assertEquals(1, result.data.size, "Should find the song")
        assertEquals("Deeply Disturbed", result.data.first().title)
    }
}
