package dev.dertyp.services.import.youtube

import dev.dertyp.audio.AudioConfig
import dev.dertyp.data.MusicBrainzRecording
import dev.dertyp.data.MusicBrainzRelease
import dev.dertyp.executeCommand
import dev.dertyp.findInPath
import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.IServerStorageService
import dev.dertyp.services.LrcLibService
import dev.dertyp.services.SongService
import dev.dertyp.services.UserPlaylistService
import dev.dertyp.services.import.ImportService
import dev.dertyp.services.import.ProcessExecutionResult
import dev.dertyp.services.metadata.MusicBrainzService
import dev.dertyp.services.youtube.YoutubeApiService
import dev.dertyp.services.youtube.YoutubeService
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class YoutubeCommandGenerationTest : KoinTest {
    private val indexer = mockk<IPluginIndexer>(relaxed = true)
    private val storageService = mockk<IServerStorageService>(relaxed = true)
    private val youtubeApiService = mockk<YoutubeApiService>(relaxed = true)
    private val lrcLibService = mockk<LrcLibService>(relaxed = true)
    private val musicBrainzService = mockk<MusicBrainzService>(relaxed = true)

    private val songService = mockk<SongService>(relaxed = true)
    private val userPlaylistService = mockk<UserPlaylistService>(relaxed = true)
    private val importService = mockk<ImportService>(relaxed = true)

    private lateinit var service: YoutubeService

    @BeforeEach
    fun setup() {
        val environment = mockk<ApplicationEnvironment>(relaxed = true)
        startKoin {
            modules(module {
                single { environment }
                single { AudioConfig() }
                single { songService }
                single { userPlaylistService }
                single { importService }
            })
        }

        mockkStatic("dev.dertyp.UtilsKt")
        every { findInPath("yt-dlp") } returns "/usr/bin/yt-dlp"

        service = YoutubeService(
            indexer,
            storageService,
            youtubeApiService,
            lrcLibService,
            musicBrainzService
        )
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        unmockkAll()
    }

    @Test
    fun `downloadContent should generate correct yt-dlp command`() = runBlocking {
        val url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"
        val videoId = "aqz-KE-bpKQ"
        val mockJson = """{"id": "$videoId", "title": "Big Buck Bunny", "uploader": "Blender", "playlist_id": ""}"""

        coEvery {
            executeCommand(match { it.contains("-J") }, any(), any(), any(), any(), any())
        } returns ProcessExecutionResult(0, mockJson, "")

        coEvery {
            executeCommand(match { !it.contains("-J") }, any(), any(), any(), any(), any())
        } returns ProcessExecutionResult(0, "Success", "")

        coEvery { musicBrainzService.searchRecordingMb(any(), any()) } returns null
        every { youtubeApiService.enabled } returns false

        service.importContent(listOf(url), 1, { true }, null) {}

        coVerify {
            executeCommand(match { cmd ->
                cmd.contains("-x") &&
                cmd.contains("--audio-format") &&
                cmd.contains("flac") &&
                cmd.contains("-o") &&
                cmd.any { it.contains(videoId) }
            }, any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `downloadContent should use playlist ID in output template if present`() = runBlocking {
        val url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ&list=PL123"
        val videoId = "aqz-KE-bpKQ"
        val playlistId = "PL123"
        val mockJson = """{"id": "$videoId", "title": "Big Buck Bunny", "uploader": "Blender", "playlist_id": "$playlistId"}"""

        coEvery {
            executeCommand(match { it.contains("-J") }, any(), any(), any(), any(), any())
        } returns ProcessExecutionResult(0, mockJson, "")

        coEvery {
            executeCommand(match { !it.contains("-J") }, any(), any(), any(), any(), any())
        } returns ProcessExecutionResult(0, "Success", "")

        coEvery { musicBrainzService.searchRecordingMb(any(), any()) } returns null
        every { youtubeApiService.enabled } returns false

        service.importContent(listOf(url), 1, { true }, null) {}

        coVerify {
            executeCommand(match { cmd ->
                cmd.contains("-o") && cmd.contains("$playlistId/$videoId.%(ext)s")
            }, any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `downloadContent should use MusicBrainz Release ID in output template if present`() = runBlocking {
        val url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"
        val videoId = "aqz-KE-bpKQ"
        val mbReleaseId = UUID.randomUUID()
        val mbRecordingId = UUID.randomUUID()
        val mockJson = """{"id": "$videoId", "title": "Big Buck Bunny", "uploader": "Blender", "playlist_id": ""}"""

        coEvery {
            executeCommand(match { it.contains("-J") }, any(), any(), any(), any(), any())
        } returns ProcessExecutionResult(0, mockJson, "")

        coEvery {
            executeCommand(match { !it.contains("-J") }, any(), any(), any(), any(), any())
        } returns ProcessExecutionResult(0, "Success", "")

        val mbRecording = MusicBrainzRecording(
            id = mbRecordingId,
            title = "Big Buck Bunny",
            releases = listOf(
                MusicBrainzRelease(
                    id = mbReleaseId,
                    title = "Blender Short Films",
                    date = "2008"
                )
            )
        )
        coEvery { musicBrainzService.searchRecordingMb(any(), any()) } returns mbRecording
        every { youtubeApiService.enabled } returns false

        service.importContent(listOf(url), 1, { true }, null) {}

        coVerify {
            executeCommand(match { cmd ->
                cmd.contains("-o") && cmd.contains("$mbReleaseId/$mbRecordingId.%(ext)s")
            }, any(), any(), any(), any(), any())
        }
    }
}
