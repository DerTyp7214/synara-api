package dev.dertyp.plugins

import dev.dertyp.Indexer
import dev.dertyp.services.ILrcLibService
import dev.dertyp.services.StorageService
import dev.dertyp.services.metadata.IMetadataService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.error.NoDefinitionFoundException
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.KoinTest
import kotlin.test.assertEquals

class PluginManagerTest : KoinTest {

    private lateinit var storageService: StorageService
    private lateinit var indexer: Indexer
    private lateinit var pluginManager: PluginManager

    @BeforeEach
    fun setup() {
        storageService = mockk(relaxed = true)
        indexer = mockk(relaxed = true)
        
        startKoin {
            modules(module {
                single { storageService }
                single { indexer }

                single { mockk<IPluginImportService>(relaxed = true) }
                single { mockk<SongLibrary>(relaxed = true) }
                single { mockk<AlbumLibrary>(relaxed = true) }
                single { mockk<ArtistLibrary>(relaxed = true) }
                single { mockk<PlaylistLibrary>(relaxed = true) }
                single { mockk<ImageLibrary>(relaxed = true) }
                single { mockk<IMetadataService>(relaxed = true) }
                single { mockk<ILrcLibService>(relaxed = true) }
                single { mockk<IScheduleService>(relaxed = true) }
            })
        }
        
        pluginManager = PluginManager(storageService, indexer)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `should not load disabled plugin and should unload its module`() {
        val disabledPlugin = mockk<ISynaraPlugin>(relaxed = true)
        val testModule = module { 
            single { 42 }
        }
        
        every { disabledPlugin.enabled } returns false
        every { disabledPlugin.getKoinModule() } returns testModule
        every { disabledPlugin.name } returns "Disabled Plugin"
        every { disabledPlugin.apiVersion } returns 1

        val loadPluginMethod = pluginManager.javaClass.getDeclaredMethod("loadPlugin", ISynaraPlugin::class.java)
        loadPluginMethod.isAccessible = true
        
        loadPluginMethod.invoke(pluginManager, disabledPlugin)
        
        verify(exactly = 1) { disabledPlugin.getKoinModule() }

        assertEquals(0, pluginManager.getAllImporters().size)
        verify(exactly = 0) { disabledPlugin.init(any()) }

        assertThrows<NoDefinitionFoundException> {
            getKoin().get<Int>()
        }
    }

    @Test
    fun `should load enabled plugin and its module`() {
        val enabledPlugin = mockk<ISynaraPlugin>(relaxed = true)
        val testModule = module { 
            single { "plugin-service" }
        }
        
        every { enabledPlugin.enabled } returns true
        every { enabledPlugin.getKoinModule() } returns testModule
        every { enabledPlugin.apiVersion } returns 1
        every { enabledPlugin.id } returns "test"
        every { enabledPlugin.name } returns "Enabled Plugin"
        
        val loadPluginMethod = pluginManager.javaClass.getDeclaredMethod("loadPlugin", ISynaraPlugin::class.java)
        loadPluginMethod.isAccessible = true
        
        loadPluginMethod.invoke(pluginManager, enabledPlugin)
        
        verify(exactly = 1) { enabledPlugin.getKoinModule() }
        verify(exactly = 1) { enabledPlugin.init(any()) }
        
        assertEquals("plugin-service", getKoin().get<String>())
    }

    @Test
    fun `should support external plugin-like loading via loadPlugin`() {
        val externalPlugin = object : ISynaraPlugin {
            override val id: String = "external"
            override val name: String = "External Plugin"
            var initCalled = false
            var moduleRequested = false

            override fun init(context: PluginContext) {
                initCalled = true
            }

            override fun getKoinModule(): Module {
                moduleRequested = true
                return module { 
                    single { 1337 }
                }
            }
        }

        val loadPluginMethod = pluginManager.javaClass.getDeclaredMethod("loadPlugin", ISynaraPlugin::class.java)
        loadPluginMethod.isAccessible = true
        
        loadPluginMethod.invoke(pluginManager, externalPlugin)
        
        assert(externalPlugin.moduleRequested)
        assert(externalPlugin.initCalled)
        assertEquals(1337, getKoin().get<Int>())
    }
}
