package dev.dertyp.services.schedule

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.MusicBrainzArtist
import dev.dertyp.db.MBArtistTable
import dev.dertyp.db.MBRecordingTable
import dev.dertyp.db.MBReleaseGroupTable
import dev.dertyp.db.MBReleaseTable
import dev.dertyp.dbQuery
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class MusicBrainzCacheWorkerTest : KoinTest {

    private fun setup(dialect: DbDialect) = runBlocking {
        TestDatabase.connect(dialect, "mb_cache_worker_test")
        dbQuery {
            SchemaUtils.create(MBArtistTable, MBReleaseGroupTable, MBReleaseTable, MBRecordingTable)
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `worker should refresh stale cache entries`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val musicBrainzService = mockk<MusicBrainzService>()
        val musicBrainzCacheService = mockk<MusicBrainzCacheService>()
        
        val artistId = UUID.randomUUID()
        coEvery { musicBrainzCacheService.staleArtistIdsFlow(any()) } returns flowOf(artistId)
        coEvery { musicBrainzCacheService.staleReleaseGroupIdsFlow(any()) } returns flowOf()
        coEvery { musicBrainzCacheService.staleReleaseIdsFlow(any()) } returns flowOf()
        coEvery { musicBrainzCacheService.staleRecordingIdsFlow(any()) } returns flowOf()

        val artist = mockk<MusicBrainzArtist>()
        coEvery { musicBrainzService.fetchArtistById(artistId, any()) } returns artist
        coEvery { musicBrainzCacheService.updateArtistCache(artist) } returns Unit

        startKoin {
            modules(module {
                single { musicBrainzService }
                single { musicBrainzCacheService }
            })
        }

        val worker = MusicBrainzCacheWorker()
        worker.run()

        coVerify { musicBrainzService.fetchArtistById(artistId, any()) }
        coVerify { musicBrainzCacheService.updateArtistCache(artist) }
    }
}
