package dev.dertyp.services.import

import dev.dertyp.core.getMetadataProvider
import dev.dertyp.data.User
import dev.dertyp.plugins.IImporter
import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.MetadataService
import io.ktor.server.application.ApplicationCall
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportRpcServiceTest {
    private val user = mockk<User>()
    private val call = mockk<ApplicationCall>()
    private val importService = mockk<ImportService>()
    private val importerProxy = mockk<ImporterProxy>()
    private val pluginManager = mockk<PluginManager>()
    
    private lateinit var rpcService: ImportRpcService

    @BeforeEach
    fun setup() {
        every { user.id } returns UUID.randomUUID()
        every { importService.pluginManager } returns pluginManager
        every { importerProxy.defaultService } returns ImportBackend.Tiddl
        
        rpcService = ImportRpcService(user, call, importService, importerProxy)
    }

    @Test
    fun `importIds should group by prefix and handle various prefixes`() = runBlocking {
        val ids = listOf("tdn:id1", "tiddl:id2", "tidal:id3", "unknown:id4", "id5")
        
        coEvery { importService.importIds(any(), any(), any(), any(), any()) } returns (true to emptyList())

        rpcService.importIds(ids, Type.SONG, null)

        coVerify(exactly = 4) {
            importService.importIds(any(), Type.SONG, user, any(), any())
        }
        
        coVerify { importService.importIds(any(), Type.SONG, user, "tdn", any()) }
        coVerify { importService.importIds(any(), Type.SONG, user, "tiddl", any()) }
        coVerify { importService.importIds(any(), Type.SONG, user, "tidal", any()) }
        coVerify { importService.importIds(any(), Type.SONG, user, "unknown", any()) }
    }

    @Test
    fun `existsByOriginalId should use importer metadataType`() = runBlocking {
        val id = "tdn:id1"
        val importer = mockk<IImporter>()
        every { importer.metadataType } returns IMetadataService.MetadataType.tidal
        every { pluginManager.getImporter("tdn") } returns importer
        
        mockkStatic("dev.dertyp.core.CallKt")
        val metadataService = mockk<MetadataService>()
        every { call.getMetadataProvider(IMetadataService.MetadataType.tidal) } returns metadataService
        coEvery { metadataService.getTrackById("id1") } returns mockk()

        val result = rpcService.existsByOriginalId(id, Type.SONG)
        
        assertTrue(result)
        coVerify { metadataService.getTrackById("id1") }
    }

    @Test
    fun `existsByOriginalId should fetch correct importer from plugin manager`() = runBlocking {
        val prefixes = listOf("tdn", "tiddl", "tidal", "unknown")
        
        mockkStatic("dev.dertyp.core.CallKt")
        
        prefixes.forEach { prefix ->
            val id = "$prefix:id"
            val importer = mockk<IImporter>(relaxed = true)
            every { pluginManager.getImporter(prefix) } returns importer
            every { call.getMetadataProvider(any()) } returns null
            
            rpcService.existsByOriginalId(id, Type.SONG)
            
            verify { pluginManager.getImporter(prefix) }
        }
    }

    @Test
    fun `existsByOriginalId should return false for wrong prefix`() = runBlocking {
        val id = "wrong:id"
        every { pluginManager.getImporter("wrong") } returns null
        
        val result = rpcService.existsByOriginalId(id, Type.SONG)
        
        assertFalse(result)
        verify { pluginManager.getImporter("wrong") }
    }
}
