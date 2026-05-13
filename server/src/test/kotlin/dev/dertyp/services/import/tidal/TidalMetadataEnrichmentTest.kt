package dev.dertyp.services.import.tidal

import dev.dertyp.ApiClient
import dev.dertyp.PlatformUUID
import dev.dertyp.data.*
import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.IServerStorageService
import dev.dertyp.services.ILrcLibService
import dev.dertyp.services.ImageService
import dev.dertyp.services.SongService
import dev.dertyp.services.UserPlaylistService
import dev.dertyp.services.import.ImportService
import dev.dertyp.services.import.ProcessExecutionResult
import dev.dertyp.services.import.TidalBaseImporter
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.IMusicBrainzService
import dev.dertyp.services.metadata.MetadataService
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
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
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

class TidalMetadataEnrichmentTest : KoinTest {
    private val indexer = mockk<IPluginIndexer>(relaxed = true)
    private val storageService = mockk<IServerStorageService>(relaxed = true)
    private val songService = mockk<SongService>(relaxed = true)
    private val userPlaylistService = mockk<UserPlaylistService>(relaxed = true)
    private val imageService = mockk<ImageService>(relaxed = true)
    private val importService = mockk<ImportService>(relaxed = true)
    private val lrcLibService = mockk<ILrcLibService>(relaxed = true)
    private val musicBrainzService = mockk<IMusicBrainzService>(relaxed = true)

    @TempDir
    lateinit var tempDir: Path

    private lateinit var downloader: TestTidalImporter

    private class TestTidalImporter(
        indexer: IPluginIndexer,
        storageService: IServerStorageService,
        private val mockFiles: List<Path>
    ) : TidalBaseImporter(indexer, storageService) {
        override val id: String = "test"
        override val enabled: Boolean = true
        override val loginCommand: MutableList<String> = mutableListOf()
        override val importCommand: MutableList<String> = mutableListOf("test-dl")
        override val favImportCommand: MutableList<String> = mutableListOf()
        override fun authorizedCheck(result: ProcessExecutionResult): Boolean = true
        override fun tokenFileExists(): Boolean = true
        override fun canHandle(url: String): Boolean = true
        override suspend fun executeImporter(
            command: Collection<String>,
            aliveCheck: suspend () -> Boolean,
            directory: File?,
            onLineReceived: suspend (String) -> Unit
        ): ProcessExecutionResult = ProcessExecutionResult(0, "Success", "")

        override suspend fun collectImportedFiles(
            command: Collection<String>,
            maxRetries: Int,
            currentTry: Int,
            aliveCheck: suspend () -> Boolean,
            userId: PlatformUUID?,
            logProxy: suspend (String) -> Unit,
            onFilesFound: suspend (List<Path>) -> Unit
        ): Pair<ProcessExecutionResult, List<Path>> {
            logProxy("Mock collectDownloadedFiles calling onFilesFound with ${mockFiles.size} files")
            onFilesFound(mockFiles)
            return Pair(ProcessExecutionResult(0, "Success", ""), mockFiles)
        }

        suspend fun testDownloadContent(urls: List<String>) = importContent(urls, 1, { true }, null) {
            println("LIVE OUTPUT: $it")
        }
    }

    @BeforeEach
    fun setup() {
        startKoin {
            modules(module {
                single { songService }
                single { userPlaylistService }
                single { imageService }
                single { importService }
                single { lrcLibService }
                single { musicBrainzService }
                single { mockk<ApplicationEnvironment>(relaxed = true) }
            })
        }

        mockkObject(MetadataService)
        mockkObject(ApiClient)
        
        val mockResponse = mockk<HttpResponse>(relaxed = true)
        every { mockResponse.status } returns HttpStatusCode.OK
        coEvery { mockResponse.body<ByteArray>() } returns ByteArray(0)
        coEvery { ApiClient.queueInstance.enqueue(any(), any(), any()) } returns mockResponse

        mockkStatic(AudioFileIO::class)

        every { storageService.forImporter(any()) } returns mockk(relaxed = true) {
            every { tracksPath } returns tempDir.toString()
        }
        
        every { indexer.audioExtension } returns "flac"
        every { indexer.artistDelimiter } returns ", "
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        stopKoin()
    }

    @Test
    fun `downloadContent should enrich metadata and tag file`() = runBlocking {
        val tidalTrack = IMetadataService.Track(
            id = "777",
            title = "Get Lucky",
            artists = listOf("Daft Punk"),
            duration = 4.minutes,
            images = listOf(IMetadataService.Image("tidal-url", 500, 500)),
            albumId = "album-999",
            albumTitle = "Random Access Memories"
        )

        val url = "https://tidal.com/track/777"
        val mbReleaseId = UUID.randomUUID()
        val mbRecordingId = UUID.randomUUID()

        val mockTidalService = mockk<MetadataService>(relaxed = true)
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns mockTidalService
        coEvery { mockTidalService.getTrackById("777", any()) } returns tidalTrack

        val mbRelease = MusicBrainzRelease(
            id = mbReleaseId,
            title = "Random Access Memories",
            date = "2013-05-17",
            media = listOf(
                MusicBrainzMedia(
                    tracks = listOf(
                        MusicBrainzTrack(
                            id = UUID.randomUUID(),
                            title = "Get Lucky",
                            recording = MusicBrainzRecording(
                                id = mbRecordingId,
                                title = "Get Lucky",
                                artistCredit = listOf(MusicBrainzArtistCredit(name = "Daft Punk"))
                            )
                        )
                    )
                )
            )
        )

        coEvery { musicBrainzService.searchRelease("Random Access Memories", listOf("Daft Punk")) } returns mbRelease
        coEvery { musicBrainzService.getRelease(mbReleaseId) } returns mbRelease

        val mockAudioFile = mockk<AudioFile>(relaxed = true)
        val mockTag = mockk<Tag>(relaxed = true)
        every { mockAudioFile.tag } returns mockTag
        every { mockTag.getFirst(FieldKey.TITLE) } returns "Get Lucky"
        
        val file = tempDir.resolve("777.flac")
        Files.createFile(file)
        every { AudioFileIO.read(file.toFile()) } returns mockAudioFile

        downloader = TestTidalImporter(indexer, storageService, listOf(file))

        downloader.testDownloadContent(listOf(url))

        verify { mockTag.setField(FieldKey.TITLE, "Get Lucky") }
        verify { mockTag.setField(FieldKey.ALBUM, "Random Access Memories") }
        verify { mockTag.setField(FieldKey.YEAR, "2013-05-17") }
        verify { mockAudioFile.commit() }
    }
}
