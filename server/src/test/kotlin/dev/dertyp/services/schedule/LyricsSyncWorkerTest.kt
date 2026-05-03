package dev.dertyp.services.schedule

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.SyncedLyricsTable
import dev.dertyp.services.LyricsService
import dev.dertyp.services.models.SyncedLyrics
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LyricsSyncWorkerTest : KoinTest {

    private fun setup(dialect: DbDialect) {
        TestDatabase.connect(dialect, "lyrics_sync_test")
        transaction {
            SchemaUtils.create(SongTable, AlbumTable, ImageTable, SyncedLyricsTable)
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
        Worker.resetActiveWorkers()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `worker should process songs and handle not found`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val lyricsService = mockk<LyricsService>()
        every { lyricsService.isConfigured() } returns true
        coEvery { lyricsService.isReachable() } returns true
        
        val albumId = transaction {
            AlbumTable.insert {
                it[name] = "Test Album"
            }[AlbumTable.id]
        }

        val song1Id = UUID.randomUUID()
        val song2Id = UUID.randomUUID()
        val song3Id = UUID.randomUUID()

        transaction {
            SongTable.insert {
                it[id] = song1Id
                it[title] = "Song with lyrics"
                it[this.albumId] = albumId
                it[lyrics] = "Some lyrics"
            }
            SongTable.insert {
                it[id] = song2Id
                it[title] = "Song without lyrics"
                it[this.albumId] = albumId
                it[lyrics] = ""
            }
            SongTable.insert {
                it[id] = song3Id
                it[title] = "Another song"
                it[this.albumId] = albumId
                it[lyrics] = ""
            }
        }

        // song1: transcription success
        coEvery { lyricsService.transcribeLyrics(song1Id, any()) } returns mockk<SyncedLyrics>()
        // song2: transcription success
        coEvery { lyricsService.transcribeLyrics(song2Id, any()) } returns mockk<SyncedLyrics>()
        // song3: transcription failure
        coEvery { lyricsService.transcribeLyrics(song3Id, any()) } returns null

        startKoin {
            modules(module {
                single { lyricsService }
            })
        }

        val worker = LyricsSyncWorker()
        val results = worker.run()

        assertEquals(2, results["synced"])
        assertEquals(1, results["notFound"])

        transaction {
            val notFoundCount = SyncedLyricsTable.selectAll().where { SyncedLyricsTable.provider eq "not_found" }.count()
            assertEquals(1, notFoundCount)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `worker should respect atomic running state`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val lyricsService = mockk<LyricsService>()
        every { lyricsService.isConfigured() } returns true
        coEvery { lyricsService.isReachable() } returns true
        
        // Mock a slow transcription to keep the worker running
        coEvery { lyricsService.transcribeLyrics(any(), any()) } coAnswers {
            delay(1.seconds)
            null
        }

        // Insert at least one song so it doesn't return early
        transaction {
            val albumId = AlbumTable.insert {
                it[name] = "Test Album"
            }[AlbumTable.id]
            SongTable.insert {
                it[title] = "Test Song"
                it[this.albumId] = albumId
            }
        }

        startKoin {
            modules(module {
                single { lyricsService }
            })
        }

        val worker = LyricsSyncWorker()
        
        coroutineScope {
            val firstRun = async { worker.run() }
            delay(100.milliseconds)
            val secondRun = worker.run()

            assertTrue(secondRun.isEmpty()) // Second run should return early because first is still running
            firstRun.await()
        }
    }
}
