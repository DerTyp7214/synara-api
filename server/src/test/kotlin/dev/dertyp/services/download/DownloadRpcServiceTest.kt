package dev.dertyp.services.download

import dev.dertyp.core.getMetadataProvider
import dev.dertyp.data.User
import dev.dertyp.plugins.IDownloader
import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.MetadataService
import io.ktor.server.application.ApplicationCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadRpcServiceTest {
    private val user = mockk<User>()
    private val call = mockk<ApplicationCall>()
    private val downloadService = mockk<DownloadService>()
    private val downloaderProxy = mockk<DownloaderProxy>()
    private val pluginManager = mockk<PluginManager>()
    
    private lateinit var rpcService: DownloadRpcService

    @BeforeEach
    fun setup() {
        every { user.id } returns UUID.randomUUID()
        every { downloadService.pluginManager } returns pluginManager
        every { downloaderProxy.defaultService } returns DownloadBackend.Tiddl
        
        rpcService = DownloadRpcService(user, call, downloadService, downloaderProxy)
    }

    @Test
    fun `downloadIds should group by prefix and handle various prefixes`() = runBlocking {
        val ids = listOf("tdn:id1", "tiddl:id2", "tidal:id3", "unknown:id4", "id5")
        
        coEvery { downloadService.downloadIds(any(), any(), any(), any(), any()) } returns (true to emptyList())

        rpcService.downloadIds(ids, Type.SONG, null)

        coVerify(exactly = 4) {
            downloadService.downloadIds(any(), Type.SONG, user, any(), any())
        }
        
        coVerify { downloadService.downloadIds(any(), Type.SONG, user, "tdn", any()) }
        coVerify { downloadService.downloadIds(any(), Type.SONG, user, "tiddl", any()) }
        coVerify { downloadService.downloadIds(any(), Type.SONG, user, "tidal", any()) }
        coVerify { downloadService.downloadIds(any(), Type.SONG, user, "unknown", any()) }
    }

    @Test
    fun `existsByOriginalId should use downloader metadataType`() = runBlocking {
        val id = "tdn:id1"
        val downloader = mockk<IDownloader>()
        every { downloader.metadataType } returns IMetadataService.MetadataType.tidal
        every { pluginManager.getDownloader("tdn") } returns downloader
        
        mockkStatic("dev.dertyp.core.CallKt")
        val metadataService = mockk<MetadataService>()
        every { call.getMetadataProvider(IMetadataService.MetadataType.tidal) } returns metadataService
        coEvery { metadataService.getTrackById("id1") } returns mockk()

        val result = rpcService.existsByOriginalId(id, Type.SONG)
        
        assertTrue(result)
        coVerify { metadataService.getTrackById("id1") }
    }

    @Test
    fun `existsByOriginalId should fetch correct downloader from plugin manager`() = runBlocking {
        val prefixes = listOf("tdn", "tiddl", "tidal", "unknown")
        
        mockkStatic("dev.dertyp.core.CallKt")
        
        prefixes.forEach { prefix ->
            val id = "$prefix:id"
            val downloader = mockk<IDownloader>(relaxed = true)
            every { pluginManager.getDownloader(prefix) } returns downloader
            every { call.getMetadataProvider(any()) } returns null
            
            rpcService.existsByOriginalId(id, Type.SONG)
            
            verify { pluginManager.getDownloader(prefix) }
        }
    }

    @Test
    fun `existsByOriginalId should return false for wrong prefix`() = runBlocking {
        val id = "wrong:id"
        every { pluginManager.getDownloader("wrong") } returns null
        
        val result = rpcService.existsByOriginalId(id, Type.SONG)
        
        assertFalse(result)
        verify { pluginManager.getDownloader("wrong") }
    }
}
