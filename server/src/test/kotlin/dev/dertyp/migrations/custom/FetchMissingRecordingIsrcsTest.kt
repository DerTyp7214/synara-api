package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.MusicBrainzRecording
import dev.dertyp.db.*
import dev.dertyp.services.ScheduledTaskLogService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
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

class FetchMissingRecordingIsrcsTest : KoinTest {
    private lateinit var database: Database
    private val musicBrainzService = mockk<MusicBrainzService>()
    private val musicBrainzCacheService = mockk<MusicBrainzCacheService>()

    fun setup(dialect: DbDialect) {
        val logService = mockk<ScheduledTaskLogService>(relaxed = true)
        every { logService.startLog(any(), any()) } returns EntityID(UUID.randomUUID(), ScheduledTaskLogTable)

        startKoin {
            modules(module {
                single { logService }
                single { musicBrainzService }
                single { musicBrainzCacheService }
            })
        }

        database = TestDatabase.connect(dialect, "fetch_missing_isrcs_test")
        transaction(database) {
            SchemaUtils.create(
                MBRecordingTable,
                MBRecordingIsrcTable,
                ImageTable,
                AlbumTable,
                SongTable,
                SongMusicBrainzTable,
                ScheduledTaskLogTable
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
    fun `migration should fetch missing isrcs for recordings`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val recordingId1 = UUID.randomUUID()
        val recordingId2 = UUID.randomUUID()
        val recordingId3 = UUID.randomUUID()

        val songId = UUID.randomUUID()
        transaction(database) {
            MBRecordingTable.insert {
                it[id] = recordingId1
                it[title] = "Recording 1"
            }
            MBRecordingTable.insert {
                it[id] = recordingId2
                it[title] = "Recording 2"
            }
            MBRecordingTable.insert {
                it[id] = recordingId3
                it[title] = "Recording 3"
            }

            MBRecordingIsrcTable.insert {
                it[recordingId] = recordingId1
                it[isrc] = "ISRC1"
            }

            val albumId = AlbumTable.insertAndGetId {
                it[name] = "Test Album"
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Test Song"
                it[this.albumId] = albumId
            }
            SongMusicBrainzTable.insert {
                it[this.songId] = songId
                it[musicBrainzId] = recordingId2
            }
        }

        val mockRecording2 = MusicBrainzRecording(id = recordingId2, title = "Recording 2", isrcs = listOf("ISRC2"))
        val mockRecording3 = MusicBrainzRecording(id = recordingId3, title = "Recording 3", isrcs = emptyList())

        coEvery { musicBrainzService.fetchRecordingsMetadataLB(any(), any()) } returns listOf(mockRecording2, mockRecording3)
        coEvery { musicBrainzCacheService.updateRecordingIsrcs(any(), any()) } returns Unit

        val migration = FetchMissingRecordingIsrcs()
        migration.migrate()

        coVerify(exactly = 1) { musicBrainzService.fetchRecordingsMetadataLB(match { it.containsAll(listOf(recordingId2, recordingId3)) }, any()) }
        coVerify(exactly = 0) { musicBrainzService.fetchRecordingById(any(), any()) }

        coVerify(exactly = 1) { musicBrainzCacheService.updateRecordingIsrcs(recordingId2, mockRecording2.isrcs!!) }
        coVerify(exactly = 0) { musicBrainzCacheService.updateRecordingCache(any()) }

        val updatedSongIsrc = transaction(database) {
            SongTable.select(SongTable.isrc).where { SongTable.id eq songId }.single()[SongTable.isrc]
        }
        assertEquals("ISRC2", updatedSongIsrc)
    }
}
