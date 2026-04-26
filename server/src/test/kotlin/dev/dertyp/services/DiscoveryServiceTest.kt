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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
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

        coEvery { mockAudioAnalysisService.getAudioDataBatch(any()) } coAnswers {
            val ids = firstArg<Collection<UUID>>()
            ids.associateWith {
                SongAudioData(
                    bpm = 120.0,
                    energy = 0.8,
                    danceability = 0.7
                )
            }
        }

        val userId = UUID.randomUUID()
        coEvery { songService.byIds(any(), userId) } coAnswers {
            val ids = firstArg<Collection<UUID>>()
            dbQuery {
                val songMap = SongTable.selectAll().where { SongTable.id inList ids }.associate { 
                    it[SongTable.id].value to SongService.mapUserSong(it, emptyList()) 
                }
                ids.mapNotNull { songMap[it] }
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
                val songMap = SongTable.selectAll().where { SongTable.id inList ids }.associate { 
                    it[SongTable.id].value to SongService.mapUserSong(it, emptyList()) 
                }
                ids.mapNotNull { songMap[it] }
            }
        }
        
        val matchedSongs = discoveryService.getSongsBySameComposers(listOf(seedSongId), userId = userId)
        
        assertEquals(1, matchedSongs.size)
        assertEquals(matchedSongId, matchedSongs[0].id)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `should find similar songs by playlist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val discoveryService = DiscoveryService()
        val songService = get<SongService>()
        
        val playlistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()

        dbQuery {
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
        }

        val seedSongs = (1..5).map { i ->
            val id = UUID.randomUUID()
            dbQuery {
                SongTable.insert { it[this.id] = id; it[title] = "Seed $i"; it[this.albumId] = albumId }
                SongAudioDataTable.insert {
                    it[this.songId] = id
                    it[bpm] = 100.0 + (i * 10)
                    it[energy] = 0.5 + (i * 0.05)
                    it[danceability] = 0.5 + (i * 0.05)
                }
            }
            coEvery { mockAudioAnalysisService.getAudioData(id) } returns SongAudioData(
                bpm = 100.0 + (i * 10),
                energy = 0.5 + (i * 0.05),
                danceability = 0.5 + (i * 0.05)
            )
            id
        }

        coEvery { mockAudioAnalysisService.getAudioDataBatch(any()) } coAnswers {
            val ids = firstArg<Collection<UUID>>()
            ids.associateWith {
                SongAudioData(
                    bpm = 120.0,
                    energy = 0.8,
                    danceability = 0.7
                )
            }
        }

        for (i in 1..15) {
            val id = UUID.randomUUID()
            dbQuery {
                SongTable.insert { it[this.id] = id; it[title] = "Candidate $i"; it[this.albumId] = albumId }
                SongAudioDataTable.insert {
                    it[this.songId] = id
                    it[bpm] = 105.0 + (i * 5)
                    it[energy] = 0.4 + (i * 0.03)
                    it[danceability] = 0.4 + (i * 0.03)
                }
            }
        }

        every { songService.songIdsByUserPlaylist(playlistId) } returns seedSongs.asFlow()

        val userId = UUID.randomUUID()
        coEvery { songService.byIds(any(), userId) } coAnswers {
            val ids = firstArg<Collection<UUID>>()
            dbQuery {
                val songMap = SongTable.selectAll().where { SongTable.id inList ids }.associate { 
                    it[SongTable.id].value to SongService.mapUserSong(it, emptyList()) 
                }
                ids.mapNotNull { songMap[it] }
            }
        }

        val similarSongs = discoveryService.getSimilarSongsByPlaylist(playlistId, limit = 10, userId = userId)
        
        assertEquals(10, similarSongs.size)
        similarSongs.forEach { song ->
            seedSongs.forEach { seedId ->
                assert(song.id != seedId)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `should handle large libraries and large seed sets`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val discoveryService = DiscoveryService()
        val songService = get<SongService>()
        val albumId = UUID.randomUUID()

        dbQuery {
            AlbumTable.insert { it[id] = albumId; it[name] = "Large Album" }
        }

        val candidateIds = (1..10000).map { UUID.randomUUID() }
        dbQuery {
            SongTable.batchInsert(candidateIds) { id ->
                this[SongTable.id] = id
                this[SongTable.title] = "Candidate"
                this[SongTable.albumId] = albumId
            }
            SongAudioDataTable.batchInsert(candidateIds) { id ->
                this[SongAudioDataTable.songId] = id
                this[SongAudioDataTable.bpm] = 120.0
                this[SongAudioDataTable.energy] = 0.5
                this[SongAudioDataTable.danceability] = 0.5
            }
        }

        val seedIds = (1..3000).map { UUID.randomUUID() }
        dbQuery {
            SongTable.batchInsert(seedIds) { id ->
                this[SongTable.id] = id
                this[SongTable.title] = "Seed"
                this[SongTable.albumId] = albumId
            }
            SongAudioDataTable.batchInsert(seedIds) { id ->
                this[SongAudioDataTable.songId] = id
                this[SongAudioDataTable.bpm] = 120.0
                this[SongAudioDataTable.energy] = 0.5
                this[SongAudioDataTable.danceability] = 0.5
            }
        }

        coEvery { mockAudioAnalysisService.getAudioDataBatch(any()) } coAnswers {
            val ids = firstArg<Collection<UUID>>()
            ids.associateWith {
                SongAudioData(bpm = 120.0, energy = 0.5, danceability = 0.5)
            }
        }

        val userId = UUID.randomUUID()
        coEvery { songService.byIds(any(), userId) } coAnswers {
            val ids = firstArg<Collection<UUID>>()
            ids.map { id ->
                mockk { every { this@mockk.id } returns id }
            }
        }

        val result = discoveryService.getSimilarSongs(seedIds, limit = 10000, userId = userId)
        assertEquals(10000, result.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `should find similar songs by bpm and rank them`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val discoveryService = DiscoveryService()
        val songService = get<SongService>()
        val seedSongId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        
        val perfectMatch = UUID.randomUUID()
        val closeMatch = UUID.randomUUID()
        val farMatch = UUID.randomUUID()

        dbQuery {
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
            SongTable.insert { it[id] = seedSongId; it[title] = "Seed"; it[this.albumId] = albumId }
            SongTable.insert { it[id] = perfectMatch; it[title] = "Perfect"; it[this.albumId] = albumId }
            SongTable.insert { it[id] = closeMatch; it[title] = "Close"; it[this.albumId] = albumId }
            SongTable.insert { it[id] = farMatch; it[title] = "Far"; it[this.albumId] = albumId }
            
            SongAudioDataTable.insert { it[this.songId] = seedSongId; it[bpm] = 120.0 }
            SongAudioDataTable.insert { it[this.songId] = perfectMatch; it[bpm] = 121.0 }
            SongAudioDataTable.insert { it[this.songId] = closeMatch; it[bpm] = 125.0 }
            SongAudioDataTable.insert { it[this.songId] = farMatch; it[bpm] = 140.0 }
        }

        coEvery { mockAudioAnalysisService.getAudioDataBatch(any()) } returns mapOf(seedSongId to SongAudioData(bpm = 120.0))
        val userId = UUID.randomUUID()
        coEvery { songService.byIds(any(), userId) } coAnswers {
            val ids = firstArg<Collection<UUID>>()
            dbQuery {
                val songMap = SongTable.selectAll().where { SongTable.id inList ids }.associate { 
                    it[SongTable.id].value to SongService.mapUserSong(it, emptyList()) 
                }
                ids.mapNotNull { songMap[it] }
            }
        }

        val results = discoveryService.getSimilarSongsByBpm(listOf(seedSongId), limit = 10, userId = userId)
        assertEquals(3, results.size)
        assertEquals(perfectMatch, results[0].id)
        assertEquals(closeMatch, results[1].id)
        assertEquals(farMatch, results[2].id)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `should find similar songs by energy and rank them`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val discoveryService = DiscoveryService()
        val songService = get<SongService>()
        val seedSongId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        
        val match1 = UUID.randomUUID()
        val match2 = UUID.randomUUID()
        val match3 = UUID.randomUUID()

        dbQuery {
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
            SongTable.insert { it[id] = seedSongId; it[title] = "Seed"; it[this.albumId] = albumId }
            SongTable.insert { it[id] = match1; it[title] = "Match1"; it[this.albumId] = albumId }
            SongTable.insert { it[id] = match2; it[title] = "Match2"; it[this.albumId] = albumId }
            SongTable.insert { it[id] = match3; it[title] = "Match3"; it[this.albumId] = albumId }
            
             SongAudioDataTable.insert { it[this.songId] = seedSongId; it[energy] = 0.9 }
             SongAudioDataTable.insert { it[this.songId] = match1; it[energy] = 0.89 }
             SongAudioDataTable.insert { it[this.songId] = match2; it[energy] = 0.8 }
             SongAudioDataTable.insert { it[this.songId] = match3; it[energy] = 0.7 }
        }

        coEvery { mockAudioAnalysisService.getAudioDataBatch(any()) } returns mapOf(seedSongId to SongAudioData(energy = 0.9))
        val userId = UUID.randomUUID()
        coEvery { songService.byIds(any(), userId) } coAnswers {
            val ids = firstArg<Collection<UUID>>()
            dbQuery {
                val songMap = SongTable.selectAll().where { SongTable.id inList ids }.associate { 
                    it[SongTable.id].value to SongService.mapUserSong(it, emptyList()) 
                }
                ids.mapNotNull { songMap[it] }
            }
        }

        val results = discoveryService.getSimilarSongsByEnergy(listOf(seedSongId), limit = 10, userId = userId)
        assertEquals(3, results.size)
        assertEquals(match1, results[0].id)
        assertEquals(match2, results[1].id)
        assertEquals(match3, results[2].id)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `should find similar songs by mood and rank them`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val discoveryService = DiscoveryService()
        val songService = get<SongService>()
        val seedSongId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        
        val match1 = UUID.randomUUID()
        val match2 = UUID.randomUUID()
        val match3 = UUID.randomUUID()

        dbQuery {
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
            SongTable.insert { it[id] = seedSongId; it[title] = "Seed"; it[this.albumId] = albumId }
            SongTable.insert { it[id] = match1; it[title] = "Match1"; it[this.albumId] = albumId }
            SongTable.insert { it[id] = match2; it[title] = "Match2"; it[this.albumId] = albumId }
            SongTable.insert { it[id] = match3; it[title] = "Match3"; it[this.albumId] = albumId }
            
            SongAudioDataTable.insert { it[this.songId] = seedSongId; it[valence] = 0.9 }
            SongAudioDataTable.insert { it[this.songId] = match1; it[valence] = 0.89 }
            SongAudioDataTable.insert { it[this.songId] = match2; it[valence] = 0.8 }
            SongAudioDataTable.insert { it[this.songId] = match3; it[valence] = 0.7 }
        }

        coEvery { mockAudioAnalysisService.getAudioDataBatch(any()) } returns mapOf(seedSongId to SongAudioData(valence = 0.9))
        val userId = UUID.randomUUID()
        coEvery { songService.byIds(any(), userId) } coAnswers {
            val ids = firstArg<Collection<UUID>>()
            dbQuery {
                val songMap = SongTable.selectAll().where { SongTable.id inList ids }.associate { 
                    it[SongTable.id].value to SongService.mapUserSong(it, emptyList()) 
                }
                ids.mapNotNull { songMap[it] }
            }
        }

        val results = discoveryService.getSimilarSongsByMood(listOf(seedSongId), limit = 10, userId = userId)
        assertEquals(3, results.size)
        assertEquals(match1, results[0].id)
        assertEquals(match2, results[1].id)
        assertEquals(match3, results[2].id)
    }
}
