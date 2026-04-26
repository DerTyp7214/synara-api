package dev.dertyp.services.schedule

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.SongAudioDataTable
import dev.dertyp.db.SongTable
import dev.dertyp.dbQuery
import dev.dertyp.services.AudioAnalysisService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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

class AudioAnalysisWorkerTest : KoinTest {

    private fun setup(dialect: DbDialect) = runBlocking {
        TestDatabase.connect(dialect, "audio_analysis_worker_test")
        dbQuery {
            SchemaUtils.create(SongTable, SongAudioDataTable)
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `worker should analyze unanalyzed songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val audioAnalysisService = mockk<AudioAnalysisService>()
        val songId = UUID.randomUUID()
        
        coEvery { audioAnalysisService.getUnanalyzedSongIds() } returns listOf(songId)
        coEvery { audioAnalysisService.analyzeSong(songId) } returns Unit

        startKoin {
            modules(module {
                single { audioAnalysisService }
            })
        }

        val worker = AudioAnalysisWorker()
        worker.run()

        coVerify { audioAnalysisService.getUnanalyzedSongIds() }
        coVerify { audioAnalysisService.analyzeSong(songId) }
    }
}
