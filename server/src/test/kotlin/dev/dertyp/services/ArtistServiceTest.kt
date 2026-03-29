package dev.dertyp.services

import dev.dertyp.db.*
import dev.dertyp.services.metadata.MusicBrainzService
import io.mockk.mockk
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
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.UUID

class ArtistServiceTest {
    private lateinit var database: Database
    private lateinit var service: ArtistService
    private val musicBrainzService = mockk<MusicBrainzService>()

    @BeforeEach
    fun setup() {
        database = Database.connect("jdbc:h2:mem:artist_test_${UUID.randomUUID().toString().replace("-", "")};MODE=MYSQL;DB_CLOSE_DELAY=-1", "org.h2.Driver")
        transaction(database) {
            SchemaUtils.create(
                ArtistTable,
                ArtistMusicBrainzTable,
                ArtistAliasTable,
                ArtistSplitAliasTable,
                ImageTable
            )
        }
        
        try {
            startKoin {
                modules(module {
                    single { musicBrainzService }
                })
            }
        } catch (_: Exception) {
            // Might be already started in some environments
        }
        
        service = ArtistService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `byId should return artist if it exists`() = runBlocking {
        val id = UUID.randomUUID()
        transaction(database) {
            ArtistTable.insert {
                it[ArtistTable.id] = id
                it[name] = "Test Artist"
                it[isGroup] = false
                it[about] = ""
            }
        }

        val artist = service.byId(id)
        assertNotNull(artist)
        assertEquals(id, artist?.id)
        assertEquals("Test Artist", artist?.name)
    }

    @Test
    fun `rankedSearch should find artists by name`() = runBlocking {
        transaction(database) {
            ArtistTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Metallica"
                it[isGroup] = true
                it[about] = ""
            }
            ArtistTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Megadeth"
                it[isGroup] = true
                it[about] = ""
            }
        }

        val result = service.rankedSearch(0, 10, "Metal")
        assertEquals(1, result.data.size)
        assertEquals("Metallica", result.data[0].name)
    }
}
