package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
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
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class FulfillIncompleteMusicBrainzCacheTest : KoinTest {
    private lateinit var database: Database
    private val mbService = mockk<MusicBrainzService>()
    private val mbCacheService = mockk<MusicBrainzCacheService>()

    fun setup(dialect: DbDialect) {
        val logService = mockk<ScheduledTaskLogService>(relaxed = true)
        every { logService.startLog(any(), any()) } returns EntityID(UUID.randomUUID(), ScheduledTaskLogTable)

        startKoin {
            modules(module {
                single { logService }
                single { mbService }
                single { mbCacheService }
            })
        }

        database = TestDatabase.connect(dialect, "fulfill_mb_cache_test")
        transaction(database) {
            SchemaUtils.create(
                *allMusicBrainzTables,
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
    fun `migration should fetch incomplete releases and recordings`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val incompleteReleaseId = UUID.randomUUID()
        val incompleteRecordingId = UUID.randomUUID()
        val completeReleaseId = UUID.randomUUID()
        val completeRecordingId = UUID.randomUUID()
        val mbArtistId = UUID.randomUUID()

        transaction(database) {
            MBArtistTable.insert {
                it[id] = mbArtistId
                it[name] = "Artist"
                it[sortName] = "Artist"
            }

            MBReleaseTable.insert {
                it[id] = incompleteReleaseId
                it[title] = ""
            }

            MBReleaseTable.insert {
                it[id] = completeReleaseId
                it[title] = "Complete Release"
            }
            MBMediaTable.insert {
                it[releaseId] = completeReleaseId
                it[position] = 1
                it[trackCount] = 1
            }

            MBRecordingTable.insert {
                it[id] = incompleteRecordingId
                it[title] = "Incomplete Recording"
                it[length] = null
            }
            MBRecordingArtistCreditTable.insert {
                it[recordingId] = incompleteRecordingId
                it[artistId] = mbArtistId
                it[name] = "Artist"
                it[position] = 0
            }

            MBRecordingTable.insert {
                it[id] = completeRecordingId
                it[title] = "Complete Recording"
                it[length] = 1000L
            }
            MBRecordingArtistCreditTable.insert {
                it[recordingId] = completeRecordingId
                it[artistId] = mbArtistId
                it[name] = "Artist"
                it[position] = 0
            }
        }

        coEvery { mbService.fetchReleaseById(incompleteReleaseId, any()) } returns mockk()
        coEvery { mbService.fetchRecordingById(incompleteRecordingId, any()) } returns mockk()
        coEvery { mbCacheService.updateReleaseCache(any()) } returns Unit
        coEvery { mbCacheService.updateRecordingCache(any()) } returns Unit

        val migration = FulfillIncompleteMusicBrainzCache()
        migration.migrate()

        coVerify(exactly = 1) { mbService.fetchReleaseById(incompleteReleaseId, any()) }
        coVerify(exactly = 1) { mbService.fetchRecordingById(incompleteRecordingId, any()) }
        coVerify(exactly = 0) { mbService.fetchReleaseById(completeReleaseId, any()) }
        coVerify(exactly = 0) { mbService.fetchRecordingById(completeRecordingId, any()) }
    }
}
