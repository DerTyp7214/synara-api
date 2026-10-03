package dev.dertyp.plugins

import dev.dertyp.Indexer
import dev.dertyp.services.ApiKeyScopeRegistry
import dev.dertyp.services.ILrcLibService
import dev.dertyp.services.StorageService
import dev.dertyp.services.credentials.PluginCredentialsFactory
import dev.dertyp.services.metadata.IMetadataService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import dev.dertyp.services.intake.IntakeService
import dev.dertyp.services.jobs.JobService
import dev.dertyp.services.ui.PluginSettingsService
import dev.dertyp.services.ui.TranslationService
import dev.dertyp.services.ui.UiRegistry
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.error.NoDefinitionFoundException
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.KoinTest
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PluginManagerTest : KoinTest {

    private lateinit var storageService: StorageService
    private lateinit var indexer: Indexer
    private lateinit var pluginManager: PluginManager
    private lateinit var credentialsFactory: PluginCredentialsFactory

    @BeforeEach
    fun setup() {
        storageService = mockk(relaxed = true)
        indexer = mockk(relaxed = true)
        credentialsFactory = mockk(relaxed = true)

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
                single { ApiKeyScopeRegistry() }
                single { UiRegistry() }
                single { TranslationService(get()) }
                single { PluginSettingsService() }
                single { IntakeService(get()) }
                single { JobService() }
                single { credentialsFactory }
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

    @Test
    fun `scopes plugin credentials to the plugin id and loads api version 2 plugins`() {
        val scoped = mockk<PluginCredentials>()
        every { credentialsFactory.forPlugin("scoped") } returns scoped
        var received: PluginCredentials? = null

        val plugin = object : ISynaraPlugin {
            override val id: String = "scoped"
            override val name: String = "Scoped"
            override val apiVersion: Int = 2

            override fun init(context: PluginContext) {
                received = context.credentials
            }
        }

        val loadPluginMethod = pluginManager.javaClass.getDeclaredMethod("loadPlugin", ISynaraPlugin::class.java)
        loadPluginMethod.isAccessible = true
        loadPluginMethod.invoke(pluginManager, plugin)

        assertSame(scoped, received)
        verify(exactly = 1) { credentialsFactory.forPlugin("scoped") }
    }

    @Test
    fun `rejects plugins newer than the supported api version`() {
        val plugin = mockk<ISynaraPlugin>(relaxed = true)
        every { plugin.apiVersion } returns PluginManager.CURRENT_API_VERSION + 1
        every { plugin.id } returns "future"
        every { plugin.name } returns "Future"

        val loadPluginMethod = pluginManager.javaClass.getDeclaredMethod("loadPlugin", ISynaraPlugin::class.java)
        loadPluginMethod.isAccessible = true
        loadPluginMethod.invoke(pluginManager, plugin)

        verify(exactly = 0) { plugin.init(any()) }
    }

    @Test
    fun `registers podcast indexes of content source plugins`() {
        val fakeIndex = object : IPodcastIndex {
            override val id: String = "fake"
            override val name: String = "Fake"

            override suspend fun isConfigured(): Boolean = true

            override suspend fun search(query: String, limit: Int): List<PodcastIndexEntry> = emptyList()
        }

        val contentSourcePlugin = object : IContentSourcePlugin {
            override val id: String = "fake-source"
            override val name: String = "Fake Source"

            override fun init(context: PluginContext) {}

            override fun getPodcastIndexes(): List<IPodcastIndex> = listOf(fakeIndex)
        }

        val loadPluginMethod = pluginManager.javaClass.getDeclaredMethod("loadPlugin", ISynaraPlugin::class.java)
        loadPluginMethod.isAccessible = true

        loadPluginMethod.invoke(pluginManager, contentSourcePlugin)

        assertEquals(listOf("fake"), pluginManager.getPodcastIndexes().map { it.id })
    }
}
