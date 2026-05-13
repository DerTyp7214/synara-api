package dev.dertyp.services.import

import dev.dertyp.core.*
import dev.dertyp.data.User
import dev.dertyp.data.UserSong
import dev.dertyp.killAll
import dev.dertyp.plugins.IImporter
import dev.dertyp.plugins.IPluginImportService
import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.*
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.sync.SyncService
import dev.dertyp.utils.LogParam
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.engine.launchOnCancellation
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.milliseconds

class ImportRpcService(
    private val user: User,
    private val call: ApplicationCall,
    private val importService: ImportService,
    private val importerProxy: ImporterProxy,
) : IImportService, KoinComponent {
    private val applicationEnvironment by inject<ApplicationEnvironment>()
    private val syncService by lazy {
        SyncService.getInstance(
            user,
            applicationEnvironment,
            ISyncService.SyncServiceType.tidal
        )
    }

    override fun logs(): Flow<LogLine> = importService.logs()
    override suspend fun currentImport(): ImportQueueEntry? = importService.currentImport(user)
    override suspend fun importQueue(): List<ImportQueueEntry> = importService.importQueue(user)
    override suspend fun finishedImports(): List<FinishedImportQueueEntry> = importService.finishedImports(user)
    override suspend fun syncFavouritesAvailable(): Boolean = importService.syncFavouritesAvailable(call)
    override suspend fun syncFavourites() {
        importService.syncFavourites(call, true).invokeOnCompletion {}
    }

    override suspend fun importIds(@LogParam("size") ids: List<PrefixedId>, type: Type, importer: ImportBackend?) {
        if (importer != null) {
            importService.importIds(
                ids = ids.asFlow(),
                type = type,
                user = user,
                importerId = importer.id,
                callback = {}
            )
        } else {
            val groups = ids.groupBy { it.getPrefix() ?: importerProxy.defaultService.id }

            groups.forEach { (importerId, groupIds) ->
                val finalIds = groupIds.map { it.stripPrefix() }

                importService.importIds(
                    ids = finalIds.asFlow(),
                    type = type,
                    user = user,
                    importerId = importerId,
                    callback = {}
                )
            }
        }
    }

    override suspend fun importUrls(urls: List<String>) {
        importService.logger.info("Processing ${urls.size} import URLs for user ${user.username}")
        val groups = urls.groupBy { url ->
            importService.pluginManager.getAllImporters().find { it.canHandle(url) }
        }

        groups.forEach { (importer, groupUrls) ->
            if (importer == null) {
                importService.logger.warn("No specific importer found for ${groupUrls.size} URLs, using default queue")
                importService.addToQueue(
                    UrlImportQueueEntry(
                        urls = groupUrls.toMutableList(),
                        byUser = user.id
                    )
                )
            } else {
                importService.logger.info("Routing ${groupUrls.size} URLs to importer: ${importer.id}")
                val parsed = groupUrls.map { it to importer.parseUrl(it) }
                val unparsed = parsed.filter { it.second == null }.map { it.first }
                if (unparsed.isNotEmpty()) {
                    importService.logger.info("Queueing ${unparsed.size} unparsed URLs for ${importer.id}")
                    importService.addToQueue(
                        UrlImportQueueEntry(
                            urls = unparsed.toMutableList(),
                            byUser = user.id,
                            importer = ImportBackend(importer.id)
                        )
                    )
                }

                parsed.mapNotNull { it.second }.groupBy { it.second }.forEach { (type, resultPairs) ->
                    val ids = resultPairs.map { it.first }
                    importService.logger.info("Routing ${ids.size} items of type $type to ${importer.id}")
                    importService.importIds(ids.asFlow(), type, user, importer.id)
                }
            }
        }
    }

    override suspend fun getImporterForUrl(url: String): ImportBackend? {
        return importService.pluginManager.getAllImporters()
            .find { it.enabled && it.canHandle(url) }
            ?.let { ImportBackend(it.id) }
    }

    override suspend fun existsByOriginalId(id: PrefixedId, type: Type): Boolean {
        val (importer, actualId) = getImporterAndId(id)

        if (importer == null) return false

        val metadataService = try {
            call.getMetadataProvider(importer.metadataType)
        } catch (_: Exception) {
            null
        } ?: return false

        return when (type) {
            Type.SONG -> metadataService.getTrackById(actualId) != null
            Type.ALBUM -> metadataService.albumExistsById(actualId)
            Type.PLAYLIST -> metadataService.getPlaylistsByIds(listOf(actualId)).firstOrNull() != null
            else -> false
        }
    }

    private fun getImporterAndId(id: PrefixedId): Pair<IImporter?, String> {
        val prefix = id.getPrefix()
        return if (prefix != null) {
            importService.pluginManager.getImporter(prefix) to id.stripPrefix()
        } else {
            importService.pluginManager.getImporter(importerProxy.defaultService.id) to id
        }
    }

    override suspend fun getImportService(): ImportBackend = importerProxy.defaultService
    override suspend fun getAllImportServices(): List<ImportBackend> = importService.getAllImportServices()
    override suspend fun setImportService(service: ImportBackend) {
        importerProxy.defaultService = service
    }

    override suspend fun importAuthorized(): Boolean = importerProxy.tokenFileExists()

    override fun importLogin() = channelFlow {
        val importer = importerProxy.getImporter(importerProxy.defaultService)
        importer.login(
            aliveCheck = { currentCoroutineContext().isActive },
            onLiveOutput = { log ->
                importer.extractLoginUrl(log)?.let { url ->
                    trySend(url)
                }
                yield()
            }
        )
    }

    override suspend fun tidalSyncAuthorized(): Boolean = syncService.getAccessToken() != null
    override suspend fun getAuthUrl(): String = syncService.buildAuthUrl(call)

    override suspend fun killAllChildProcesses() = killAll()

    override suspend fun search(
        query: String?,
        title: String?,
        artist: String?,
        count: Int
    ): List<ImportSong> {
        return importService.search(call, query, title, artist, count)
    }
}

@Suppress("unused")
@OptIn(ExperimentalAtomicApi::class)
class ImportService(
    val importerProxy: ImporterProxy,
    val songService: SongService,
    val favSyncService: FavSyncService,
    val imageService: ImageService,
    val pluginManager: PluginManager,
) : IPluginImportService, Service() {
    private val maxLogLength: Int = 1000

    private val syncMutex = Mutex()
    private val queueMutex = Mutex()
    private val finishedMutex = Mutex()

    private val syncMap = ConcurrentHashMap<UUID, AtomicBoolean>()

    private val importQueue: MutableList<ImportQueueEntry> = arrayListOf()
    private val finishedImports: MutableList<FinishedImportQueueEntry> = arrayListOf()

    private val queueUpdateFlow: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)

    private val active: AtomicBoolean = AtomicBoolean(false)
    private val stopped: AtomicBoolean = AtomicBoolean(true)

    private val internalLog: MutableStateFlow<String?> = MutableStateFlow(null)
    val log = internalLog.asSharedFlow()

    var currentImport: FinishedImportQueueEntry? = null
        private set

    @OptIn(FlowPreview::class)
    override suspend fun startService() {
        if (!stopped.compareAndSet(expectedValue = true, newValue = false)) return
        logger.info("Starting service")

        coroutineScope {
            launch {
                queueUpdateFlow
                    .onStart { emit(Unit) }
                    .debounce(100.milliseconds)
                    .takeWhile { !stopped.load() }
                    .collect {
                        logger.info("Trying to import")
                        import { !stopped.load() }
                    }
            }
        }

        logger.info("Stopping service")
        stopped.store(true)
    }

    override suspend fun stopService() {
        stopped.store(true)
    }

    override suspend fun addToQueue(vararg importEntries: ImportQueueEntry) {
        queueMutex.withLock {
            val existingUrls = importQueue
                .filterIsInstance<UrlImportQueueEntry>()
                .flatMap { it.urls }
                .toMutableList()
            val existingTypes = importQueue
                .filterIsInstance<FavouriteImportQueueEntry>()
                .map { it.favoriteType }
                .toMutableList()

            currentImport?.let {
                when (val entry = it.importQueueEntry) {
                    is UrlImportQueueEntry -> existingUrls.addAll(entry.urls)
                    is FavouriteImportQueueEntry -> existingTypes.add(entry.favoriteType)
                }
            }

            val entries = importEntries.filter {
                when (it) {
                    is UrlImportQueueEntry -> {
                        it.urls.removeAll(existingUrls)
                        it.urls.isNotEmpty()
                    }

                    is FavouriteImportQueueEntry -> !existingTypes.contains(it.favoriteType)
                }
            }

            logger.info(
                "Adding ${entries.size} to queue (${
                    entries.sumOf {
                        if (it is UrlImportQueueEntry) it.urls.size
                        else 1
                    }
                } urls)"
            )

            importQueue.addAll(entries)
        }

        queueUpdateFlow.tryEmit(Unit)
    }

    fun isActive(): Boolean {
        return active.load()
    }

    suspend fun waitForInactive() {
        return active.waitForChange(false)
    }

    suspend fun waitForActive() {
        return active.waitForChange(true)
    }

    fun isStopped(): Boolean {
        return stopped.load()
    }

    fun queueSize(): Int {
        return importQueue.size
    }

    fun logs(): Flow<LogLine> = flow {
        val oldLogs = currentImport?.logs ?: emptyList()

        val currentImportQueueEntry = currentImport?.importQueueEntry

        emitAll(oldLogs.mapNotNull { line ->
            currentImportQueueEntry?.let { LogLine(it, line) }
        }.asFlow())
        log.collect { line ->
            currentImport?.let {
                emit(LogLine(it.importQueueEntry, line))
            }
        }
    }

    fun currentImport(user: User? = null): ImportQueueEntry? {
        if (user?.isAdmin == true) return currentImport?.importQueueEntry
        return when (user?.id) {
            null, currentImport?.importQueueEntry?.byUser -> currentImport?.importQueueEntry
            else -> null
        }
    }

    suspend fun importQueue(user: User? = null): List<ImportQueueEntry> {
        return queueMutex.withLock {
            importQueue.filter { user == null || user.isAdmin || it.byUser == user.id }
        }
    }

    suspend fun finishedImports(user: User? = null): List<FinishedImportQueueEntry> {
        return finishedMutex.withLock {
            finishedImports.toList().filter { user == null || user.isAdmin || it.importQueueEntry.byUser == user.id }
        }
    }

    suspend fun clearErrors() {
        finishedMutex.withLock {
            finishedImports.removeIf {
                !it.result.successful()
            }
        }
    }

    suspend fun retryErrors() {
        val errors = finishedImports.filter { it.result.failed() }.map { it.importQueueEntry }

        addToQueue(*errors.toTypedArray())

        clearErrors()
    }

    private suspend fun import(aliveCheck: suspend () -> Boolean) {
        if (!active.compareAndSet(expectedValue = false, newValue = true)) return

        while (queueMutex.withLock { importQueue.isNotEmpty() }) {
            val entry = queueMutex.withLock { importQueue.removeFirst() }

            val logs = mutableListOf<String>()

            currentImport = FinishedImportQueueEntry(
                importQueueEntry = entry,
                result = ProcessExecutionResult.EMPTY,
                logs = logs
            )

            val logUnit: suspend (String) -> Unit = { line ->
                internalLog.emit(line)
                logs.add(line)

                logger.debug(line)

                if (logs.size > maxLogLength) logs.removeFirst()
            }

            val result = when (entry) {
                is UrlImportQueueEntry -> importerProxy.importContent(
                    urls = entry.urls,
                    maxRetries = entry.maxRetries,
                    aliveCheck = aliveCheck,
                    userId = entry.byUser,
                    service = entry.importer,
                    onLiveOutput = logUnit
                )

                is FavouriteImportQueueEntry -> importerProxy.importFavoriteCollection(
                    type = entry.favoriteType,
                    maxRetries = entry.maxRetries,
                    aliveCheck = aliveCheck,
                    userId = entry.byUser,
                    service = entry.importer ?: importerProxy.defaultService,
                    onLiveOutput = logUnit
                )
            }

            internalLog.emit(null)

            currentImport?.let { currentImport ->
                currentImport.result = result
                if (result.successful()) currentImport.importQueueEntry.callback()

                finishedMutex.withLock {
                    finishedImports.add(currentImport)
                    if (finishedImports.count { it.result.successful() } > 100) finishedImports.removeIf {
                        it.result.successful()
                    }
                }
            }

        }

        currentImport = null
        active.store(false)
    }

    fun getAllImportServices(): List<ImportBackend> =
        pluginManager.getAllImporters().filter { it.enabled }.map { ImportBackend(it.id) }

    suspend fun syncFavouritesAvailable(call: ApplicationCall): Boolean {
        val user = call.getUser() ?: throw IllegalStateException("No user")
        return !(syncMap[user.id]?.load() ?: false)
    }

    suspend fun importIds(
        ids: Flow<String>,
        type: Type,
        user: User,
        importerId: String? = null,
        callback: suspend (List<String>) -> Unit = {}
    ): Pair<Boolean, List<UserSong>> {
        var contentToImport = false
        val allResultSongs = mutableListOf<UserSong>()

        ids.toList().chunked(250).forEach { idChunk ->
            val im = if (importerId != null) {
                pluginManager.getImporter(importerId)
            } else {
                pluginManager.getImporter(importerProxy.defaultService.id)
            }

            if (im != null) {
                val result = im.importIds(idChunk, type, user, callback)
                if (result.first) contentToImport = true
                allResultSongs.addAll(result.second)
            }
        }
        return Pair(contentToImport, allResultSongs)
    }

    @OptIn(ExperimentalCoroutinesApi::class, InternalAPI::class)
    suspend fun syncFavourites(call: ApplicationCall, ignoreService: Boolean = false): CompletableJob {
        val user = call.getUser() ?: throw IllegalStateException("No user")
        if (!ignoreService && call.parameters["service"] != ISyncService.SyncServiceType.tidal.name) throw IllegalStateException(
            "Only tidal"
        )
        val service = if (ignoreService) SyncService.getInstance(
            user,
            call.application.environment,
            ISyncService.SyncServiceType.tidal
        ) else SyncService.getInstance(call, user.username)

        if (service.getAccessToken() == null) throw IllegalStateException("Tidal not authenticated")

        syncMutex.withLock {
            if (!syncMap.computeIfAbsent(user.id) { AtomicBoolean(false) }.compareAndSet(
                    expectedValue = false,
                    newValue = true
                )
            ) throw IllegalStateException("Sync service has already been started")
        }

        return ApplicationScope.scope.async {
            val latestFavSync = favSyncService.getLatestFavSync(user, ISyncService.SyncServiceType.tidal)

            val idMap = ConcurrentHashMap<String, ISyncService.LikedSong>()

            val songs = service.getLikedSongs { songs ->
                latestFavSync == null || songs.none { it.addedAt < latestFavSync.syncedAt }
            }
                .filter { song -> latestFavSync == null || song.addedAt > latestFavSync.syncedAt }
                .onEach { idMap[it.id] = it }

            val idsToFetch = songs.map { it.id }
            val tidalImporter = pluginManager.getAllImporters().find { it.id == "tidal" } ?:
                                 pluginManager.getAllImporters().find { it.canHandle("https://tidal.com") }
            val (_, songsToLike) = importIds(
                ids = idsToFetch,
                type = Type.SONG,
                user = user,
                importerId = tidalImporter?.id
            ) {
                for (song in songService.byOriginalIds(it, user.id)) {
                    if (!(song.isFavourite ?: false)) {
                        val addedAt = idMap[song.originalUrl.tidalId()]?.addedAt?.toInstant()
                        songService.setLikedReturning(song.id, user.id, true, addedAt)
                    }
                }
            }

            logger.info("[${user.username}] Liking existing songs")

            for (song in songsToLike) {
                songService.setLikedReturning(song.id, user.id, true)
            }

            syncMutex.withLock {
                syncMap[user.id]?.store(false)
            }

            favSyncService.insertFavSync(user, ISyncService.SyncServiceType.tidal, Date.from(Instant.now()))

            logger.info("[${user.username}] Sync favourite songs finished.")
        }.launchOnCancellation {
            syncMutex.withLock {
                syncMap[user.id]?.store(false)
            }

            logger.info("[${user.username}] Sync favourite songs cancelled.")
        }
    }

    suspend fun search(
        call: ApplicationCall,
        query: String?,
        title: String?,
        artist: String?,
        count: Int
    ): List<ImportSong> {
        val metadataService = call.getMetadataProvider(IMetadataService.MetadataType.tidal)
            ?: throw IllegalStateException("Tidal metadata service not available")

        val searchResults = if (query != null) {
            metadataService.search(query, count)
        } else if (title != null && artist != null) {
            metadataService.search("$title - $artist", count)
        } else if (title != null) {
            metadataService.search(title, count)
        } else {
            emptyList()
        }

        return searchResults.map {
            ImportSong(
                id = it.id,
                title = it.title,
                artists = it.artists,
                cover = it.images.associate { image -> image.width to image.url },
            )
        }
    }
}
