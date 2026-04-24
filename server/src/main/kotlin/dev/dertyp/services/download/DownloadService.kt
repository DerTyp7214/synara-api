package dev.dertyp.services.download

import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.getMetadataProvider
import dev.dertyp.core.getUser
import dev.dertyp.core.tidalId
import dev.dertyp.core.waitForChange
import dev.dertyp.data.User
import dev.dertyp.data.UserSong
import dev.dertyp.killAll
import dev.dertyp.plugins.IDownloader
import dev.dertyp.plugins.IPluginDownloadService
import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.FavSyncService
import dev.dertyp.services.ISyncService
import dev.dertyp.services.ImageService
import dev.dertyp.services.Service
import dev.dertyp.services.SongService
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.sync.SyncService
import dev.dertyp.utils.LogParam
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.engine.launchOnCancellation
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.milliseconds

class DownloadRpcService(
    private val user: User,
    private val call: ApplicationCall,
    private val downloadService: DownloadService,
    private val downloaderProxy: DownloaderProxy,
) : IDownloadService, KoinComponent {
    private val applicationEnvironment by inject<ApplicationEnvironment>()
    private val syncService by lazy {
        SyncService.getInstance(
            user,
            applicationEnvironment,
            ISyncService.SyncServiceType.tidal
        )
    }

    override fun logs(): Flow<LogLine> = downloadService.logs()
    override suspend fun currentDownload(): DownloadQueueEntry? = downloadService.currentDownload(user)
    override suspend fun downloadQueue(): List<DownloadQueueEntry> = downloadService.downloadQueue(user)
    override suspend fun finishedDownloads(): List<FinishedDownloadQueueEntry> = downloadService.finishedDownloads(user)
    override suspend fun syncFavouritesAvailable(): Boolean = downloadService.syncFavouritesAvailable(call)
    override suspend fun syncFavourites() {
        downloadService.syncFavourites(call, true).invokeOnCompletion {}
    }

    override suspend fun downloadIds(@LogParam("size") ids: List<PrefixedId>, type: Type, downloader: DownloadBackend?) {
        if (downloader != null) {
            downloadService.downloadIds(
                ids = ids.asFlow(),
                type = type,
                user = user,
                downloaderId = downloader.id,
                callback = {}
            )
        } else {
            val groups = ids.groupBy { it.getPrefix() ?: downloaderProxy.defaultService.id }

            groups.forEach { (downloaderId, groupIds) ->
                val finalIds = groupIds.map { it.stripPrefix() }

                downloadService.downloadIds(
                    ids = finalIds.asFlow(),
                    type = type,
                    user = user,
                    downloaderId = downloaderId,
                    callback = {}
                )
            }
        }
    }

    override suspend fun downloadUrls(urls: List<String>) {
        downloadService.logger.info("Processing ${urls.size} download URLs for user ${user.username}")
        val downloaderGroups = urls.groupBy { url ->
            downloadService.pluginManager.getAllDownloaders().find { it.canHandle(url) }
        }

        downloaderGroups.forEach { (downloader, groupUrls) ->
            if (downloader == null) {
                downloadService.logger.warn("No specific downloader found for ${groupUrls.size} URLs, using default queue")
                downloadService.addToQueue(
                    UrlDownloadQueueEntry(
                        urls = groupUrls.toMutableList(),
                        byUser = user.id
                    )
                )
            } else {
                downloadService.logger.info("Routing ${groupUrls.size} URLs to downloader: ${downloader.id}")
                val parsedUrls = groupUrls.map { url -> url to downloader.parseUrl(url) }
                val typeGroups = parsedUrls.groupBy { it.second?.second ?: Type.SONG }

                typeGroups.forEach { (type, pairs) ->
                    val ids = pairs.mapNotNull { it.second?.first }
                    downloadService.logger.info("Handing off ${ids.size} items of type $type to ${downloader.id}")
                    downloadService.downloadIds(ids.asFlow(), type, user, downloader.id)
                }
            }
        }
    }

    override suspend fun getDownloaderForUrl(url: String): DownloadBackend? {
        return downloadService.pluginManager.getAllDownloaders()
            .find { it.enabled && it.canHandle(url) }
            ?.let { DownloadBackend(it.id) }
    }

    override suspend fun existsByOriginalId(id: PrefixedId, type: Type): Boolean {
        val (downloader, actualId) = getDownloaderAndId(id)

        if (downloader == null) return false

        val metadataService = try {
            call.getMetadataProvider(downloader.metadataType)
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

    private fun getDownloaderAndId(id: PrefixedId): Pair<IDownloader?, String> {
        val prefix = id.getPrefix()
        return if (prefix != null) {
            downloadService.pluginManager.getDownloader(prefix) to id.stripPrefix()
        } else {
            downloadService.pluginManager.getDownloader(downloaderProxy.defaultService.id) to id
        }
    }

    override suspend fun getDownloadService(): DownloadBackend = downloaderProxy.defaultService
    override suspend fun getAllDownloadServices(): List<DownloadBackend> = downloadService.getAllDownloadServices()
    override suspend fun setDownloadService(service: DownloadBackend) {
        downloaderProxy.defaultService = service
    }

    override suspend fun downloadAuthorized(): Boolean = downloaderProxy.tokenFileExists()

    override fun downloadLogin() = channelFlow {
        val downloader = downloaderProxy.getDownloader(downloaderProxy.defaultService)
        downloader.login(
            aliveCheck = { currentCoroutineContext().isActive },
            onLiveOutput = { log ->
                downloader.extractLoginUrl(log)?.let { url ->
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
    ): List<DownloadSong> {
        return downloadService.search(call, query, title, artist, count)
    }
}

@Suppress("unused")
@OptIn(ExperimentalAtomicApi::class)
class DownloadService(
    val downloaderProxy: DownloaderProxy,
    val songService: SongService,
    val favSyncService: FavSyncService,
    val imageService: ImageService,
    val pluginManager: PluginManager,
) : IPluginDownloadService, Service() {
    private val maxLogLength: Int = 1000

    private val syncMutex = Mutex()
    private val queueMutex = Mutex()
    private val finishedMutex = Mutex()

    private val syncMap = ConcurrentHashMap<UUID, AtomicBoolean>()

    private val downloadQueue: MutableList<DownloadQueueEntry> = arrayListOf()
    private val finishedDownloads: MutableList<FinishedDownloadQueueEntry> = arrayListOf()

    private val queueUpdateFlow: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)

    private val active: AtomicBoolean = AtomicBoolean(false)
    private val stopped: AtomicBoolean = AtomicBoolean(true)

    private val internalLog: MutableStateFlow<String?> = MutableStateFlow(null)
    val log = internalLog.asSharedFlow()

    var currentlyDownloading: FinishedDownloadQueueEntry? = null
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
                        logger.info("Trying to download")
                        download { !stopped.load() }
                    }
            }
        }

        logger.info("Stopping service")
        stopped.store(true)
    }

    override suspend fun stopService() {
        stopped.store(true)
    }

    override suspend fun addToQueue(vararg downloadEntries: DownloadQueueEntry) {
        queueMutex.withLock {
            val existingUrls = downloadQueue
                .filterIsInstance<UrlDownloadQueueEntry>()
                .flatMap { it.urls }
                .toMutableList()
            val existingTypes = downloadQueue
                .filterIsInstance<FavouriteDownloadQueueEntry>()
                .map { it.favoriteType }
                .toMutableList()

            currentlyDownloading?.let {
                when (val entry = it.downloadQueueEntry) {
                    is UrlDownloadQueueEntry -> existingUrls.addAll(entry.urls)
                    is FavouriteDownloadQueueEntry -> existingTypes.add(entry.favoriteType)
                }
            }

            val entries = downloadEntries.filter {
                when (it) {
                    is UrlDownloadQueueEntry -> {
                        it.urls.removeAll(existingUrls)
                        it.urls.isNotEmpty()
                    }

                    is FavouriteDownloadQueueEntry -> !existingTypes.contains(it.favoriteType)
                }
            }

            logger.info(
                "Adding ${entries.size} to queue (${
                    entries.sumOf {
                        if (it is UrlDownloadQueueEntry) it.urls.size
                        else 1
                    }
                } urls)"
            )

            downloadQueue.addAll(entries)
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
        return downloadQueue.size
    }

    fun logs() = flow {
        val oldLogs = currentlyDownloading?.logs ?: emptyList()

        val currentDownloadQueueEntry = currentlyDownloading?.downloadQueueEntry

        emitAll(oldLogs.mapNotNull { line ->
            currentDownloadQueueEntry?.let { LogLine(it, line) }
        }.asFlow())
        log.collect { line ->
            currentlyDownloading?.let {
                emit(LogLine(it.downloadQueueEntry, line))
            }
        }
    }

    fun currentDownload(user: User? = null): DownloadQueueEntry? {
        return when (user?.id) {
            null, currentlyDownloading?.downloadQueueEntry?.byUser -> currentlyDownloading?.downloadQueueEntry
            else -> null
        }
    }

    suspend fun downloadQueue(user: User? = null): List<DownloadQueueEntry> {
        return queueMutex.withLock {
            downloadQueue.filter { user == null || it.byUser == user.id }
        }
    }

    suspend fun finishedDownloads(user: User? = null): List<FinishedDownloadQueueEntry> {
        return finishedMutex.withLock {
            finishedDownloads.toList().filter { user == null || it.downloadQueueEntry.byUser == user.id }
        }
    }

    suspend fun clearErrors() {
        finishedMutex.withLock {
            finishedDownloads.removeIf {
                !it.result.successful()
            }
        }
    }

    suspend fun retryErrors() {
        val errors = finishedMutex.withLock {
            finishedDownloads.filter { it.result.failed() }.map { it.downloadQueueEntry }
        }

        addToQueue(*errors.toTypedArray())

        clearErrors()
    }

    private suspend fun download(aliveCheck: suspend () -> Boolean) {
        if (!active.compareAndSet(expectedValue = false, newValue = true)) return

        while (queueMutex.withLock { downloadQueue.isNotEmpty() }) {
            val entry = queueMutex.withLock { downloadQueue.removeFirst() }

            val logs = mutableListOf<String>()

            currentlyDownloading = FinishedDownloadQueueEntry(
                downloadQueueEntry = entry,
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
                is UrlDownloadQueueEntry -> downloaderProxy.downloadContent(
                    urls = entry.urls,
                    maxRetries = entry.maxRetries,
                    aliveCheck = aliveCheck,
                    userId = entry.byUser,
                    onLiveOutput = logUnit
                )

                is FavouriteDownloadQueueEntry -> downloaderProxy.downloadFavoriteCollection(
                    type = entry.favoriteType,
                    maxRetries = entry.maxRetries,
                    aliveCheck = aliveCheck,
                    userId = entry.byUser,
                    onLiveOutput = logUnit
                )
            }

            internalLog.emit(null)

            currentlyDownloading?.let { currentlyDownloading ->
                currentlyDownloading.result = result
                if (result.successful()) currentlyDownloading.downloadQueueEntry.callback()

                finishedMutex.withLock {
                    finishedDownloads.add(currentlyDownloading)
                    if (finishedDownloads.count { it.result.successful() } > 100) finishedDownloads.removeIf {
                        it.result.successful()
                    }
                }
            }

        }

        currentlyDownloading = null
        active.store(false)
    }

    fun getAllDownloadServices(): List<DownloadBackend> =
        pluginManager.getAllDownloaders().filter { it.enabled }.map { DownloadBackend(it.id) }

    suspend fun syncFavouritesAvailable(call: ApplicationCall): Boolean {
        val user = call.getUser() ?: throw IllegalStateException("No user")
        return !(syncMap[user.id]?.load() ?: false)
    }

    suspend fun downloadIds(
        ids: Flow<String>,
        type: Type,
        user: User,
        downloaderId: String? = null,
        callback: suspend (List<String>) -> Unit = {}
    ): Pair<Boolean, List<UserSong>> {
        var contentToDownload = false
        val allResultSongs = mutableListOf<UserSong>()

        ids.toList().chunked(250).forEach { idChunk ->
            val dl = if (downloaderId != null) {
                pluginManager.getDownloader(downloaderId)
            } else {
                pluginManager.getDownloader(downloaderProxy.defaultService.id)
            }

            if (dl != null) {
                val result = dl.downloadIds(idChunk, type, user, callback)
                if (result.first) contentToDownload = true
                allResultSongs.addAll(result.second)
            }
        }
        return Pair(contentToDownload, allResultSongs)
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
            val (_, songsToLike) = downloadIds(
                ids = idsToFetch,
                type = Type.SONG,
                user = user
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
    ): List<DownloadSong> {
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
            DownloadSong(
                id = it.id,
                title = it.title,
                artists = it.artists,
                cover = it.images.associate { image -> image.width to image.url },
            )
        }
    }
}
