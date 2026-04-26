package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.SongAudioData
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.PersonTable
import dev.dertyp.db.SongAudioDataTable
import dev.dertyp.db.SongComposerTable
import dev.dertyp.db.SongLyricistTable
import dev.dertyp.db.SongProducerTable
import dev.dertyp.db.SongTable
import dev.dertyp.dbQuery
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.get
import java.util.UUID
import kotlin.test.assertEquals

class DiscoveryServiceTest : KoinTest {

    private val mockAudioAnalysisService = mockk<AudioAnalysisService>()

    private fun setup(dialect: DbDialect) = runBlocking {
        TestDatabase.connect(dialect, "discovery_test")
        dbQuery {
            SchemaUtils.create(
                AlbumTable, SongTable, SongAudioDataTable,
                PersonTable, SongComposerTable, SongLyricistTable, SongProducerTable
            )
        }
        
        startKoin {
            modules(module {
                single { mockk<SongService>() }
                single { mockAudioAnalysisService }
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
    fun `should calculate similarities and return similar songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val discoveryService = DiscoveryService()
        val songService = get<SongService>()
        
        val seedSongId = UUID.randomUUID()
        val similarSongId = UUID.randomUUID()
        val differentSongId = UUID.randomUUID()
        val albumId = UUID.randomUUID()

        dbQuery {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Test Album"
            }
            SongTable.insert {
                it[id] = seedSongId
                it[title] = "Seed Song"
                it[this.albumId] = albumId
            }
            SongAudioDataTable.insert {
                it[this.songId] = seedSongId
                it[bpm] = 120.0
                it[energy] = 0.8
                it[danceability] = 0.7
            }
            SongTable.insert {
                it[id] = similarSongId
                it[title] = "Similar Song"
                it[this.albumId] = albumId
            }
            SongAudioDataTable.insert {
                it[this.songId] = similarSongId
                it[bpm] = 122.0
                it[energy] = 0.78
                it[danceability] = 0.72
            }
            SongTable.insert {
                it[id] = differentSongId
                it[title] = "Different Song"
                it[this.albumId] = albumId
            }
            SongAudioDataTable.insert {
                it[this.songId] = differentSongId
                it[bpm] = 90.0
                it[energy] = 0.2
                it[danceability] = 0.1
            }
        }

        coEvery { mockAudioAnalysisService.getAudioData(seedSongId) } returns SongAudioData(
            bpm = 120.0,
            energy = 0.8,
            danceability = 0.7
        )

        val userId = UUID.randomUUID()
        coEvery { songService.byIds(any(), userId) } coAnswers {
            val ids = firstArg<Collection<UUID>>()
            dbQuery {
                SongTable.selectAll().where { SongTable.id inList ids }.map { 
                    SongService.mapUserSong(it, emptyList()) 
                }
            }
        }
        
        val similarSongs = discoveryService.getSimilarSongs(listOf(seedSongId), limit = 10, userId = userId)
        
        assertEquals(2, similarSongs.size)
        assertEquals(similarSongId, similarSongs[0].id)
        assertEquals(differentSongId, similarSongs[1].id)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `should find songs by same composers`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val discoveryService = DiscoveryService()
        val songService = get<SongService>()
        
        val seedSongId = UUID.randomUUID()
        val matchedSongId = UUID.randomUUID()
        val unrelatedSongId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val personId = UUID.randomUUID()

        dbQuery {
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
            PersonTable.insert { it[id] = personId; it[name] = "Great Composer" }
            
            SongTable.insert { it[id] = seedSongId; it[title] = "Seed"; it[this.albumId] = albumId }
            SongComposerTable.insert { it[songId] = seedSongId; it[this.personId] = personId }
            
            SongTable.insert { it[id] = matchedSongId; it[title] = "Matched"; it[this.albumId] = albumId }
            SongComposerTable.insert { it[songId] = matchedSongId; it[this.personId] = personId }
            
            SongTable.insert { it[id] = unrelatedSongId; it[title] = "Unrelated"; it[this.albumId] = albumId }
        }

        val userId = UUID.randomUUID()
        coEvery { songService.byIds(any(), userId) } coAnswers {
            val ids = firstArg<Collection<UUID>>()
            dbQuery {
                SongTable.selectAll().where { SongTable.id inList ids }.map { 
                    SongService.mapUserSong(it, emptyList()) 
                }
            }
        }
        
        val matchedSongs = discoveryService.getSongsBySameComposers(listOf(seedSongId), userId = userId)
        
        assertEquals(1, matchedSongs.size)
        assertEquals(matchedSongId, matchedSongs[0].id)
    }
}
