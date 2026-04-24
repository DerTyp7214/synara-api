package dev.dertyp.plugins

import dev.dertyp.Indexer
import dev.dertyp.services.AlbumService
import dev.dertyp.services.ArtistService
import dev.dertyp.services.ILrcLibService
import dev.dertyp.services.ImageService
import dev.dertyp.services.LrcLibService
import dev.dertyp.services.Service
import dev.dertyp.services.SongService
import dev.dertyp.services.StorageService
import dev.dertyp.services.UserPlaylistService
import dev.dertyp.services.download.DownloadBackend
import dev.dertyp.services.download.DownloadService
import dev.dertyp.services.download.TidalPlugin
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.MetadataDispatcherService
import dev.dertyp.services.schedule.ScheduleService
import dev.dertyp.services.youtube.YoutubePlugin
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
    single<IPluginDownloadService> { get<DownloadService>() }
    single<SongLibrary> { get<SongService>() }
    single<AlbumLibrary> { get<AlbumService>() }
    single<ArtistLibrary> { get<ArtistService>() }
    single<PlaylistLibrary> { get<UserPlaylistService>() }
    single<ImageLibrary> { get<ImageService>() }
    single<IMetadataService> { get<MetadataDispatcherService>() }
    single<IScheduleService> { get<ScheduleService>() }
    single<ILrcLibService> { get<LrcLibService>() }
    single<IServerStorageService> { get<StorageService>() }
}

class PluginManager(
    private val storageService: StorageService,
    private val indexer: Indexer
) : Service() {
    private val pluginsDir = File("plugins").apply { mkdirs() }
    private val loadedPlugins = mutableListOf<ISynaraPlugin>()
    private val downloaders = mutableMapOf<String, IDownloader>()
    private val indexers = mutableSetOf<IPluginIndexer>()

    var defaultDownloaderId: String = "tiddl"

    companion object {
        const val CURRENT_API_VERSION = 1
    }

    override suspend fun startService() {
        loadPlugin(TidalPlugin())
        loadPlugin(YoutubePlugin())
        loadPlugins()
    }

    fun registerDownloader(downloader: IDownloader) {
        downloaders[downloader.id] = downloader
        indexers.add(downloader.indexer)
        indexer.registerIndexer(downloader.indexer)
        logger.info("Registered downloader: ${downloader.name} (${downloader.id})")
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
                override val storageService = baseContext.storageService.forDownloader(DownloadBackend(plugin.id))
            }

            plugin.init(pluginContext)
            loadedPlugins.add(plugin)

            plugin.getIndexers().forEach {
                indexers.add(it)
                indexer.registerIndexer(it)
            }

            plugin.getDownloaders().forEach {
                registerDownloader(it)
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
        override val downloadService: IPluginDownloadService by inject()
        override val songLibrary: SongLibrary by inject()
        override val albumLibrary: AlbumLibrary by inject()
        override val artistLibrary: ArtistLibrary by inject()
        override val playlistLibrary: PlaylistLibrary by inject()
        override val imageLibrary: ImageLibrary by inject()
        override val metadataService: IMetadataService by inject()
        override val lrcLibService: ILrcLibService by inject()
        override val scheduleService: IScheduleService by inject()
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

    fun getDownloader(id: String = defaultDownloaderId): IDownloader? = downloaders[id]
    fun getAllDownloaders(): Collection<IDownloader> = downloaders.values
    fun getAllIndexers(): Collection<IPluginIndexer> = indexers
}
