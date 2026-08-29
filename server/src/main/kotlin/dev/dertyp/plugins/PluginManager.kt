package dev.dertyp.plugins

import dev.dertyp.Indexer
import dev.dertyp.services.*
import dev.dertyp.services.import.ImportBackend
import dev.dertyp.services.import.ImportService
import dev.dertyp.services.import.MusicBrainzPlugin
import dev.dertyp.services.import.TidalPlugin
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.MetadataDispatcherService
import dev.dertyp.services.schedule.ScheduleService
import dev.dertyp.services.gamdl.GamdlPlugin
import dev.dertyp.services.recommendation.RecommendationPlugin
import dev.dertyp.services.soundcloud.SoundcloudPlugin
import dev.dertyp.services.subsonic.SubsonicPlugin
import dev.dertyp.services.ui.PluginSettingsService
import dev.dertyp.services.ui.TranslationService
import dev.dertyp.services.ui.UiRegistry
import dev.dertyp.services.youtube.YoutubePlugin
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.util.logging.KtorSimpleLogger
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.loadKoinModules
import org.koin.core.context.unloadKoinModules
import org.koin.dsl.module
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader

val pluginModule = module {
    single<IPluginIndexer> { get<Indexer>() }
    single<IPluginImportService> { get<ImportService>() }
    single<SongLibrary> { get<SongService>() }
    single<AlbumLibrary> { get<AlbumService>() }
    single<ArtistLibrary> { get<ArtistService>() }
    single<PlaylistLibrary> { get<UserPlaylistService>() }
    single<ImageLibrary> { get<ImageService>() }
    single<IMetadataService> { get<MetadataDispatcherService>() }
    single<IScheduleService> { get<ScheduleService>() }
    single<ILrcLibService> { get<LrcLibService>() }
    single<IServerStorageService> { get<StorageService>() }
    single<HookBus> { get<HookService>() }
}

class PluginManager(
    private val storageService: StorageService,
    private val indexer: Indexer
) : Service() {
    private val application by inject<Application>()
    private val scopeRegistry by inject<ApiKeyScopeRegistry>()
    private val uiRegistry by inject<UiRegistry>()
    private val translationService by inject<TranslationService>()
    private val pluginSettingsService by inject<PluginSettingsService>()
    private val pluginsDir = File("plugins").apply { mkdirs() }
    private val loadedPlugins = mutableListOf<ISynaraPlugin>()
    private val importers = mutableMapOf<String, IImporter>()
    private val indexers = mutableSetOf<IPluginIndexer>()

    var defaultImporterId: String = "tiddl"

    companion object {
        const val CURRENT_API_VERSION = 2
    }

    override suspend fun startService() {
        loadPlugin(TidalPlugin())
        loadPlugin(YoutubePlugin())
        loadPlugin(SoundcloudPlugin())
        loadPlugin(GamdlPlugin())
        loadPlugin(MusicBrainzPlugin())
        loadPlugin(RecommendationPlugin())
        loadPlugin(SubsonicPlugin())
        loadPlugins()
    }

    fun registerImporter(importer: IImporter) {
        importers[importer.id] = importer
        indexers.add(importer.indexer)
        indexer.registerIndexer(importer.indexer)
        logger.info("Registered importer: ${importer.name} (${importer.id})")
    }

    private fun loadPlugin(plugin: ISynaraPlugin) {
        if (plugin.apiVersion > CURRENT_API_VERSION) {
            logger.error("Failed to load plugin: ${plugin.name} (${plugin.id}). Plugin API version (${plugin.apiVersion}) is newer than supported version ($CURRENT_API_VERSION)")
            return
        }

        try {
            val module = plugin.getKoinModule()
            module?.let { loadKoinModules(it) }

            if (!plugin.enabled) {
                logger.info("Plugin ${plugin.name} is disabled, skipping")
                module?.let { unloadKoinModules(it) }
                return
            }

            val pluginContext = object : PluginContext by baseContext {
                override val storageService = baseContext.storageService.forImporter(ImportBackend(plugin.id))
                override val apiKeyScopes = scopeRegistry.forPlugin(plugin.id)
                override val ui = uiRegistry.forSource(plugin.id)
                override val settings = pluginSettingsService.forPlugin(plugin.id)
                override val i18n = translationService.forSource(plugin.id)
            }

            plugin.init(pluginContext)
            loadedPlugins.add(plugin)

            if (plugin is IUiPlugin) {
                plugin.getUiContributions().forEach { pluginContext.ui.register(it) }
            }

            if (plugin is IRoutePlugin) {
                application.routing { plugin.registerRoutes(this) }
            }

            if (plugin is IContentSourcePlugin) {
                plugin.getIndexers().forEach {
                    indexers.add(it)
                    indexer.registerIndexer(it)
                }

                plugin.getImporters().forEach {
                    registerImporter(it)
                }
            }
            logger.info("Loaded plugin: ${plugin.name} (${plugin.id})")
        } catch (e: Exception) {
            logger.error("Failed to load plugin: ${plugin.name}", e)
        }
    }

    private val baseContext = object : PluginContext, KoinComponent {
        override val logger = KtorSimpleLogger("Plugin")
        override val storageService = this@PluginManager.storageService
        override val indexer = this@PluginManager.indexer
        override val importService: IPluginImportService by inject()
        override val songLibrary: SongLibrary by inject()
        override val albumLibrary: AlbumLibrary by inject()
        override val artistLibrary: ArtistLibrary by inject()
        override val playlistLibrary: PlaylistLibrary by inject()
        override val imageLibrary: ImageLibrary by inject()
        override val metadataService: IMetadataService by inject()
        override val lrcLibService: ILrcLibService by inject()
        override val scheduleService: IScheduleService by inject()
        override val hooks: HookBus by inject()
        override val apiKeyScopes get() = scopeRegistry.forPlugin("plugin")
        override val ui get() = uiRegistry.forSource(UiRegistry.SERVER_SOURCE)
        override val settings get() = pluginSettingsService.forPlugin(UiRegistry.SERVER_SOURCE)
        override val i18n get() = translationService.forSource(UiRegistry.SERVER_SOURCE)
    }

    private fun loadPlugins() {
        val jarFiles = pluginsDir.listFiles { _, name -> name.endsWith(".jar") } ?: emptyArray()

        val urls = jarFiles.map { it.toURI().toURL() }.toTypedArray()
        val classLoader = URLClassLoader(urls, this::class.java.classLoader)

        val serviceLoader = ServiceLoader.load(ISynaraPlugin::class.java, classLoader)

        for (plugin in serviceLoader) {
            loadPlugin(plugin)
        }
    }

    fun getImporter(id: String = defaultImporterId): IImporter? = importers[id]
    fun getAllImporters(): Collection<IImporter> = importers.values
    fun getAllIndexers(): Collection<IPluginIndexer> = indexers

    fun getMetadataService(type: IMetadataService.MetadataType): IMetadataService? {
        return loadedPlugins.filterIsInstance<IContentSourcePlugin>()
            .firstNotNullOfOrNull { it.getMetadataService(type) }
    }
}
