package dev.dertyp.services.import

import dev.dertyp.PlatformUUID
import dev.dertyp.data.MusicBrainzRecording
import dev.dertyp.data.MusicBrainzRelation
import dev.dertyp.data.MusicBrainzRelationUrl
import dev.dertyp.plugins.*
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.MusicBrainzService
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class MusicBrainzImporterTest : KoinTest {

    private lateinit var importer: MusicBrainzImporter
    private val context = mockk<PluginContext>(relaxed = true)
    private val mbService = mockk<MusicBrainzService>(relaxed = true)
    private val pluginManager = mockk<PluginManager>(relaxed = true)
    private val importService = mockk<IPluginImportService>(relaxed = true)
    private val metadataService = mockk<IMetadataService>(relaxed = true)

    @BeforeEach
    fun setup() {
        startKoin {
            modules(module {
                single { mbService }
                single { pluginManager }
            })
        }

        every { context.importService } returns importService
        every { context.metadataService } returns metadataService
        
        importer = MusicBrainzImporter(context)
        importer.indexer = mockk<IPluginIndexer>()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        unmockkAll()
    }

    @Test
    fun `canHandle should recognize musicbrainz and listenbrainz links`() {
        assertTrue(importer.canHandle("https://musicbrainz.org/recording/76807865-c49c-482d-8b06-5389658e2441"))
        assertTrue(importer.canHandle("https://musicbrainz.org/release/8e18585e-b9e7-4f4c-b5f6-86c55982855f"))
        assertTrue(importer.canHandle("https://musicbrainz.org/release-group/a933324c-9f69-32d7-938b-9e4f71a067e4"))
        assertTrue(importer.canHandle("https://listenbrainz.org/recording/76807865-c49c-482d-8b06-5389658e2441"))
        assertTrue(importer.canHandle("https://listenbrainz.org/player/release/8e18585e-b9e7-4f4c-b5f6-86c55982855f"))
    }

    @Test
    fun `parseUrl should extract UUID and Type`() = runBlocking {
        assertEquals("76807865-c49c-482d-8b06-5389658e2441" to Type.SONG, importer.parseUrl("https://musicbrainz.org/recording/76807865-c49c-482d-8b06-5389658e2441"))
        assertEquals("8e18585e-b9e7-4f4c-b5f6-86c55982855f" to Type.ALBUM, importer.parseUrl("https://musicbrainz.org/release/8e18585e-b9e7-4f4c-b5f6-86c55982855f"))
        assertEquals("a933324c-9f69-32d7-938b-9e4f71a067e4" to Type.MIX, importer.parseUrl("https://musicbrainz.org/release-group/a933324c-9f69-32d7-938b-9e4f71a067e4"))
    }

    @Test
    fun `importContent should find and queue streaming links`() = runBlocking {
        val mbid = UUID.randomUUID()
        val streamingUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        
        val recording = mockk<MusicBrainzRecording>()
        every { recording.relations } returns listOf(
            MusicBrainzRelation(
                type = "streaming",
                url = MusicBrainzRelationUrl(PlatformUUID.randomUUID(), streamingUrl)
            )
        )
        
        coEvery { mbService.fetchRecordingById(mbid) } returns recording
        
        val otherImporter = mockk<IImporter>()
        every { otherImporter.id } returns "youtube"
        every { otherImporter.enabled } returns true
        every { otherImporter.canHandle(streamingUrl) } returns true
        
        every { pluginManager.getAllImporters() } returns listOf(importer, otherImporter)
        
        importer.importContent(
            urls = listOf("https://musicbrainz.org/recording/$mbid"),
            maxRetries = 1,
            aliveCheck = { true },
            userId = null,
            metadata = null,
            onLiveOutput = {}
        )
        
        coVerify { 
            importService.addToQueue(match { 
                it is UrlImportQueueEntry && it.urls.contains(streamingUrl) 
            }) 
        }
    }
}
