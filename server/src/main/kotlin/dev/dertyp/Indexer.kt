package dev.dertyp

import dev.dertyp.data.User
import dev.dertyp.plugins.*
import dev.dertyp.services.*
import dev.dertyp.services.ui.PluginSettingsService
import dev.dertyp.services.ui.TranslationService
import dev.dertyp.services.ui.UiRegistry
import dev.dertyp.services.import.ImportBackend
import dev.dertyp.services.import.ImportService
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.MetadataDispatcherService
import dev.dertyp.services.schedule.ScheduleService
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.koin.core.component.KoinComponent
import java.nio.file.Path
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.Path

class RpcIndexer(private val indexer: Indexer, private val user: User) : IIndexer {
    @OptIn(ExperimentalAtomicApi::class)
    override fun start(): Flow<String> = channelFlow {
        if (!indexer.isActive.compareAndSet(expectedValue = false, newValue = true)) {
            indexer.logger.warn("Indexer is already running.")
            send("Indexer is already running.")
            return@channelFlow
        }

        try {
            indexer.logger.info("Starting indexing...")
            send("Starting indexing...")

            coroutineScope {
                if (indexer.enabled) {
                    launch {
                        indexer.start(user.id) { stdout ->
                            send(stdout)
                        }
                    }
                }

                indexer.otherIndexers.filter { it.enabled }.forEach { otherIndexer ->
                    launch {
                        otherIndexer.start(user.id) { stdout ->
                            send(stdout)
                        }
                    }
                }
            }

            indexer.logger.info("Finished indexing...")
            send("Finished indexing...")
        } finally {
            indexer.isActive.store(false)
        }
    }
}

@Suppress("unused")
@OptIn(ExperimentalAtomicApi::class)
class Indexer(
    private val songService: SongService,
    private val imageService: ImageService,
    private val storageService: StorageService,
    private val scheduleService: ScheduleService,
) : IPluginIndexer, KoinComponent {
    override val id: String = "router"
    override val name: String = "Indexer Router"

    val logger = KtorSimpleLogger("Indexer")
    val isActive = AtomicBoolean(false)

    private val coreIndexer = object : BaseIndexer(object : PluginContext, KoinComponent {
        override val logger = this@Indexer.logger
        override val storageService = this@Indexer.storageService.forImporter(ImportBackend("core"))
        override val indexer: IPluginIndexer get() = this@Indexer
        override val importService: IPluginImportService get() = getKoin().get<ImportService>()
        override val songLibrary: SongLibrary get() = songService
        override val albumLibrary: AlbumLibrary get() = getKoin().get<AlbumService>()
        override val artistLibrary: ArtistLibrary get() = getKoin().get<ArtistService>()
        override val playlistLibrary: PlaylistLibrary get() = getKoin().get<UserPlaylistService>()
        override val imageLibrary: ImageLibrary get() = imageService
        override val metadataService: IMetadataService get() = getKoin().get<MetadataDispatcherService>()
        override val lrcLibService: ILrcLibService get() = getKoin().get<LrcLibService>()
        override val scheduleService: IScheduleService get() = this@Indexer.scheduleService
        override val hooks: HookBus get() = getKoin().get<HookBus>()
        override val apiKeyScopes: ApiKeyScopeRegistrar get() = getKoin().get<ApiKeyScopeRegistry>().forPlugin("core")
        override val ui: UiRegistrar get() = getKoin().get<UiRegistry>().forSource(UiRegistry.SERVER_SOURCE)
        override val settings: PluginSettings get() = getKoin().get<PluginSettingsService>().forPlugin(UiRegistry.SERVER_SOURCE)
        override val i18n: TranslationRegistrar get() = getKoin().get<TranslationService>().forSource(UiRegistry.SERVER_SOURCE)
    }, metadataType = null) {
        override val id: String = "core"
        override val name: String = "Core Indexer"
    }

    override val audioExtension: String get() = coreIndexer.audioExtension
    override val audioExtensions: Set<String> get() = coreIndexer.audioExtensions
    override val playlistExtension: String get() = coreIndexer.playlistExtension
    override val artistDelimiter: String get() = ";"

    val secondaryTracksPaths = storageService.secondaryTracksPaths.map { Path(it) }

    suspend fun queue(userId: PlatformUUID? = null, stdout: suspend (String) -> Unit) = coroutineScope {
        val log = { line: String -> async { stdout(line) } }
        if (storageService.tracksPath == null || storageService.playlistsPath == null)
            return@coroutineScope log("audio paths are not configured")

        val songRootPath = Path(storageService.tracksPath)
        val playlistRootPath = Path(storageService.playlistsPath)

        return@coroutineScope queue(
            listOf(songRootPath, *secondaryTracksPaths.toTypedArray()),
            listOf(playlistRootPath), null, userId, stdout
        )
    }

    override suspend fun start(userId: PlatformUUID?, stdout: suspend (String) -> Unit) = coroutineScope {
        val log = { line: String -> async { stdout(line) } }
        if (storageService.tracksPath == null || storageService.playlistsPath == null)
            return@coroutineScope log("audio paths are not configured").await()

        val songRootPath = Path(storageService.tracksPath)
        val playlistRootPath = Path(storageService.playlistsPath)

        start(
            listOf(songRootPath, *secondaryTracksPaths.toTypedArray()),
            listOf(playlistRootPath), userId, stdout
        )
    }

    val otherIndexers = mutableSetOf<IPluginIndexer>()

    fun registerIndexer(indexer: IPluginIndexer) {
        if (indexer != this) {
            otherIndexers.add(indexer)
        }
    }

    override fun canHandle(path: Path): Boolean {
        return coreIndexer.canHandle(path) || otherIndexers.any { it.canHandle(path) }
    }

    override suspend fun queue(
        songPaths: List<Path>,
        playlistPaths: List<Path>,
        type: String?,
        userId: PlatformUUID?,
        stdout: suspend (String) -> Unit
    ): Deferred<Unit> {
        if (type != null && type != this.id && type != "core") {
            val indexer = otherIndexers.find { it.id == type }
            if (indexer != null) {
                return CoroutineScope(Dispatchers.IO).async { indexer.queue(songPaths, playlistPaths, type, userId, stdout).await() }
            }
        }

        if (type == "core") {
            return coreIndexer.queue(songPaths, playlistPaths, type, userId, stdout)
        }

        val mySongs = mutableListOf<Path>()
        val myPlaylists = mutableListOf<Path>()
        val delegatedTasks = mutableListOf<Deferred<Unit>>()

        if (type == null) {
            val songGroups = songPaths.groupBy { path -> otherIndexers.find { it.canHandle(path) } }
            val playlistGroups = playlistPaths.groupBy { path -> otherIndexers.find { it.canHandle(path) } }

            songGroups.forEach { (indexer, paths) ->
                if (indexer != null) {
                    delegatedTasks.add(CoroutineScope(Dispatchers.IO).async { indexer.queue(paths, emptyList(), null, userId, stdout).await() })
                } else {
                    mySongs.addAll(paths)
                }
            }

            playlistGroups.forEach { (indexer, paths) ->
                if (indexer != null) {
                    delegatedTasks.add(CoroutineScope(Dispatchers.IO).async { indexer.queue(emptyList(), paths, null, userId, stdout).await() })
                } else {
                    myPlaylists.addAll(paths)
                }
            }
        } else {
            mySongs.addAll(songPaths)
            myPlaylists.addAll(playlistPaths)
        }

        if (mySongs.isEmpty() && myPlaylists.isEmpty()) {
            return CoroutineScope(Dispatchers.IO).async { delegatedTasks.awaitAll() }
        }

        return coreIndexer.queue(mySongs, myPlaylists, "core", userId, stdout)
            .also { deferred -> deferred.invokeOnCompletion { storageService.invalidate(StorageCategory.TOTAL) } }
    }

    suspend fun start(
        songPaths: List<Path>,
        playlistPaths: List<Path>,
        userId: PlatformUUID? = null,
        stdout: suspend (String) -> Unit
    ) = coroutineScope {
        val mySongs = mutableListOf<Path>()
        val myPlaylists = mutableListOf<Path>()
        val delegatedTasks = mutableListOf<Deferred<Unit>>()

        val songGroups = songPaths.groupBy { path -> otherIndexers.find { it.canHandle(path) } }
        val playlistGroups = playlistPaths.groupBy { path -> otherIndexers.find { it.canHandle(path) } }

        songGroups.forEach { (indexer, paths) ->
            if (indexer != null) {
                delegatedTasks.add(async { indexer.queue(paths, emptyList(), null, userId, stdout).await() })
            } else {
                mySongs.addAll(paths)
            }
        }

        playlistGroups.forEach { (indexer, paths) ->
            if (indexer != null) {
                delegatedTasks.add(async { indexer.queue(emptyList(), paths, null, userId, stdout).await() })
            } else {
                myPlaylists.addAll(paths)
            }
        }

        if (mySongs.isEmpty() && myPlaylists.isEmpty()) {
            delegatedTasks.awaitAll()
            return@coroutineScope
        }

        coreIndexer.start(mySongs, myPlaylists, userId, stdout)
        storageService.invalidate(StorageCategory.TOTAL)
    }
}
