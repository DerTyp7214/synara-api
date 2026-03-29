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

class AlbumServiceTest {
    private lateinit var database: Database
    private lateinit var service: AlbumService
    private val musicBrainzService = mockk<MusicBrainzService>()

    @BeforeEach
    fun setup() {
        database = Database.connect("jdbc:h2:mem:album_test_${UUID.randomUUID().toString().replace("-", "")};MODE=MYSQL;DB_CLOSE_DELAY=-1", "org.h2.Driver")
        transaction(database) {
            SchemaUtils.create(
                AlbumTable,
                AlbumArtistTable,
                ArtistTable,
                ArtistMusicBrainzTable,
                ArtistAliasTable,
                AlbumMusicBrainzTable,
                ImageTable,
                SongTable,
                SongArtistTable,
                SongMusicBrainzTable
            )
        }
        
        try {
            startKoin {
                modules(module {
                    single { musicBrainzService }
                })
            }
        } catch (_: Exception) {
            // Might be already started
        }
        
        service = AlbumService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `byId should return album if it exists`() = runBlocking {
        val id = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[AlbumTable.id] = id
                it[name] = "Test Album"
                it[songCount] = 10
            }
        }

        val album = service.byId(id)
        assertNotNull(album)
        assertEquals(id, album?.id)
        assertEquals("Test Album", album?.name)
    }

    @Test
    fun `rankedSearch should find albums by name`() = runBlocking {
        transaction(database) {
            AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Master of Puppets"
                it[songCount] = 8
            }
            AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Rust in Peace"
                it[songCount] = 9
            }
        }

        val result = service.rankedSearch(0, 10, "Master")
        assertEquals(1, result.data.size)
        assertEquals("Master of Puppets", result.data[0].name)
    }
}
