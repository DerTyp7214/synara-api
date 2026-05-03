package dev.dertyp.services.schedule

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.Artist
import dev.dertyp.data.Song
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.SyncedLyricsTable
import dev.dertyp.services.LrcLibResponse
import dev.dertyp.services.LrcLibService
import dev.dertyp.services.SongService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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

class LrcLibWorkerTest : KoinTest {

    private fun setup(dialect: DbDialect) {
        TestDatabase.connect(dialect, "lrclib_worker_test")
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
    fun `worker should fetch lyrics and update song`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val lrcLibService = mockk<LrcLibService>()
        val songService = mockk<SongService>()
        
        val albumId = transaction {
            AlbumTable.insert {
                it[name] = "Test Album"
            }[AlbumTable.id]
        }

        val songId = UUID.randomUUID()

        transaction {
            SongTable.insert {
                it[id] = songId
                it[title] = "Test Song"
                it[this.albumId] = albumId
                it[lyrics] = ""
                it[lastLyricsFetchAttempt] = 0
            }
        }

        val mockArtist = mockk<Artist>()
        every { mockArtist.name } returns "Test Artist"

        val songMetadata = mockk<Song>()
        every { songMetadata.id } returns songId
        every { songMetadata.title } returns "Test Song"
        every { songMetadata.artists } returns listOf(mockArtist)
        every { songMetadata.album } returns mockk { every { name } returns "Test Album" }
        every { songMetadata.duration } returns 180000L
        
        coEvery { songService.byId(songId) } returns songMetadata
        
        coEvery { lrcLibService.getLyrics(any(), any(), any(), any()) } returns LrcLibResponse(
            id = 1,
            trackName = "Test Song",
            artistName = "Test Artist",
            albumName = "Test Album",
            duration = 180.0,
            instrumental = false,
            syncedLyrics = "[00:10.00] Lyrics"
        )

        startKoin {
            modules(module {
                single { lrcLibService }
                single { songService }
            })
        }

        val worker = LrcLibWorker()
        val results = worker.run()

        assertEquals(1, results["synced"])

        transaction {
            val song = SongTable.selectAll().where { SongTable.id eq songId }.single()
            assertEquals("[00:10.00] Lyrics", song[SongTable.lyrics])
            assertTrue(song[SongTable.lastLyricsFetchAttempt] > 0)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `worker should skip songs tried within last week`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val lrcLibService = mockk<LrcLibService>()
        val songService = mockk<SongService>()

        val now = System.currentTimeMillis()
        val twoDaysAgo = now - (2 * 24 * 60 * 60 * 1000L)

        transaction {
            val albumId = AlbumTable.insert {
                it[name] = "Test Album"
            }[AlbumTable.id]

            SongTable.insert {
                it[title] = "Recent Song"
                it[this.albumId] = albumId
                it[lyrics] = ""
                it[lastLyricsFetchAttempt] = twoDaysAgo
            }
        }

        startKoin {
            modules(module {
                single { lrcLibService }
                single { songService }
            })
        }

        val worker = LrcLibWorker()
        val results = worker.run()

        assertEquals(0, results["synced"] ?: 0)
        assertEquals(0, results["notFound"] ?: 0)
    }
}
