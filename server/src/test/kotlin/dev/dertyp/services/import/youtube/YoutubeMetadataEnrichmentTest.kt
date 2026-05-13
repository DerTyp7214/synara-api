package dev.dertyp.services.import.youtube

import dev.dertyp.ApiClient
import dev.dertyp.PlatformUUID
import dev.dertyp.data.MusicBrainzArtistCredit
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
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class YoutubeMetadataEnrichmentTest : KoinTest {
    private val indexer = mockk<IPluginIndexer>(relaxed = true)
    private val storageService = mockk<IServerStorageService>(relaxed = true)
    private val youtubeApiService = mockk<YoutubeApiService>(relaxed = true)
    private val lrcLibService = mockk<LrcLibService>(relaxed = true)
    private val musicBrainzService = mockk<MusicBrainzService>(relaxed = true)

    private val songService = mockk<SongService>(relaxed = true)
    private val userPlaylistService = mockk<UserPlaylistService>(relaxed = true)
    private val importService = mockk<ImportService>(relaxed = true)

    @TempDir
    lateinit var tempDir: Path

    private lateinit var service: TestYoutubeService

    private class TestYoutubeService(
        indexer: IPluginIndexer,
        storageService: IServerStorageService,
        youtubeApiService: YoutubeApiService,
        lrcLibService: LrcLibService,
        musicBrainzService: MusicBrainzService,
        private val mockFiles: List<Path>
    ) : YoutubeService(indexer, storageService, youtubeApiService, lrcLibService, musicBrainzService) {
        
        override suspend fun collectImportedFiles(
            command: Collection<String>,
            maxRetries: Int,
            currentTry: Int,
            aliveCheck: suspend () -> Boolean,
            userId: PlatformUUID?,
            logProxy: suspend (String) -> Unit,
            onFilesFound: suspend (List<Path>) -> Unit
        ): Pair<ProcessExecutionResult, List<Path>> {
            onFilesFound(mockFiles)
            return Pair(ProcessExecutionResult(0, "Success", ""), mockFiles)
        }
    }

    @BeforeEach
    fun setup() {
        val environment = mockk<ApplicationEnvironment>(relaxed = true)
        startKoin {
            modules(module {
                single { environment }
                single { songService }
                single { userPlaylistService }
                single { importService }
            })
        }

        mockkStatic("dev.dertyp.UtilsKt")
        every { findInPath("yt-dlp") } returns "/usr/bin/yt-dlp"

        mockkObject(ApiClient)
        mockkStatic(AudioFileIO::class)
        
        every { indexer.audioExtension } returns "flac"
        every { indexer.artistDelimiter } returns ", "
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        stopKoin()
    }

    @Test
    fun `downloadContent should enrich YouTube metadata with MusicBrainz and tag file`() = runBlocking {
        val url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"
        val videoId = "aqz-KE-bpKQ"
        val mockJson = """{
            "id": "$videoId", 
            "title": "Big Buck Bunny", 
            "uploader": "Blender", 
            "playlist_id": "",
            "track": "Big Buck Bunny Theme",
            "artist": "Blender Studio"
        }"""

        coEvery {
            executeCommand(match { it.contains("-J") && it.contains("--simulate") }, any(), any(), any(), any(), any())
        } returns ProcessExecutionResult(0, mockJson, "")

        val mbReleaseId = UUID.randomUUID()
        val mbRecordingId = UUID.randomUUID()
        val mbRecording = MusicBrainzRecording(
            id = mbRecordingId,
            title = "Enriched Title",
            artistCredit = listOf(MusicBrainzArtistCredit(name = "Enriched Artist")),
            releases = listOf(
                MusicBrainzRelease(
                    id = mbReleaseId,
                    title = "Enriched Album",
                    date = "2024-01-01"
                )
            )
        )
        coEvery { musicBrainzService.searchRecordingMb("Big Buck Bunny Theme", listOf("Blender Studio")) } returns mbRecording

        val mockAudioFile = mockk<AudioFile>(relaxed = true)
        val mockTag = mockk<Tag>(relaxed = true)
        every { mockAudioFile.tag } returns mockTag
        every { mockTag.getFirst(FieldKey.TITLE) } returns "Big Buck Bunny"
        
        val file = tempDir.resolve("$videoId.flac")
        Files.createFile(file)
        every { AudioFileIO.read(file.toFile()) } returns mockAudioFile

        service = TestYoutubeService(indexer, storageService, youtubeApiService, lrcLibService, musicBrainzService, listOf(file))

        service.importContent(listOf(url), 1, { true }, null) {}

        verify { mockTag.setField(FieldKey.TITLE, "Enriched Title") }
        verify { mockTag.setField(FieldKey.ARTIST, "Enriched Artist") }
        verify { mockTag.setField(FieldKey.ALBUM, "Enriched Album") }
        verify { mockTag.setField(FieldKey.YEAR, "2024-01-01") }
        verify { mockAudioFile.commit() }
    }
}
