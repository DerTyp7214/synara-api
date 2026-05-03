package dev.dertyp.services.schedule

import dev.dertyp.AudioUtils
import dev.dertyp.data.SimpleSong
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class AutoTranscodeWorkerTest : KoinTest {

    @AfterEach
    fun tearDown() {
        stopKoin()
        unmockkAll()
    }

    @Test
    fun `worker should skip songs with non-existent files`() = runBlocking {
        val environment = mockk<ApplicationEnvironment>()
        val config = MapApplicationConfig(
            "audio.autoTranscode" to "128",
            "audio.tracks" to "/data/tracks",
        )
        every { environment.config } returns config

        val song = SimpleSong(
            id = UUID.randomUUID(),
            title = "Missing Song",
            duration = 100L,
            explicit = false,
            releaseDate = LocalDate.of(2023, 1, 1),
            path = "/non/existent/path.flac",
            originalUrl = "",
            trackNumber = 1,
            discNumber = 1,
            sampleRate = 44100,
            bitsPerSample = 16,
            bitRate = 1000L,
            fileSize = 1000000L,
            coverId = null,
            transcodedTo = emptyList()
        )

        mockkObject(AudioUtils)
        every { AudioUtils.isTranscoderActive.compareAndSet(any(), any()) } returns true
        every { AudioUtils.isTranscoderActive.store(any()) } just Runs
        coEvery { AudioUtils.getSongsWithTranscodingInfo(any()) } returns listOf(song)
        coEvery { AudioUtils.insertTranscodedSong(any<List<Triple<SimpleSong, File, Int>>>()) } just Runs

        startKoin {
            modules(
                module {
                    single { environment }
                    single<ApplicationConfig> { config }
                }
            )
        }

        val worker = AutoTranscodeWorker()
        val results = worker.run()

        assertEquals(0, results["quality_128"])
        coVerify(exactly = 0) { AudioUtils.transcodeFlacToOpus(any(), any(), any()) }
    }
}
