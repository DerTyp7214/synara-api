package dev.dertyp.services.tdn

import dev.dertyp.core.*
import dev.dertyp.data.User
import dev.dertyp.data.UserSong
import dev.dertyp.killAll
import dev.dertyp.services.FavSyncService
import dev.dertyp.services.ISyncService
import dev.dertyp.services.Service
import dev.dertyp.services.SongService
import dev.dertyp.services.metadata.MetadataService
import dev.dertyp.services.sync.SyncService
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

class DownloadRpcService(
    private val user: User,
    private val call: ApplicationCall,
    private val downloadService: DownloadService,
    private val tidalDownloadService: TidalDownloaderProxy,
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

    override suspend fun downloadTidalIds(ids: List<String>, type: Type) {
        downloadService.downloadTidalIds(
            call = call,
            ids = ids.asFlow(),
            type = type,
            callback = {}
        )
    }

    override suspend fun existsByTidalId(id: String, type: Type): Boolean {
        val metadataService = call.getMetadataProvider(MetadataService.Companion.MetadataType.tidal) ?: return false
        return when (type) {
            Type.SONG -> metadataService.getTrackById(id) != null
            Type.ALBUM -> metadataService.albumExistsById(id)
            else -> false
        }
    }

    override suspend fun getTidalDownloadService(): TidalDownloadService = tidalDownloadService.defaultService
    override suspend fun setTidalDownloadService(service: TidalDownloadService) {
        tidalDownloadService.defaultService = service
    }

    override suspend fun tidalDownloadAuthorized(): Boolean = tidalDownloadService.tokenFileExists()

    override fun tidalDownloadLogin() = channelFlow {
        tidalDownloadService.login(
            aliveCheck = { currentCoroutineContext().isActive },
            onLiveOutput = {
                trySend(it)
                trySend("\n")
                yield()
            }
        )
    }

    override suspend fun tidalSyncAuthorized(): Boolean = syncService.getAccessToken() != null
    override suspend fun getAuthUrl(): String = syncService.buildAuthUrl(call)

    override suspend fun killAllChildProcesses() = killAll()

    override suspend fun searchTidal(
        query: String?,
        title: String?,
        artist: String?,
        count: Int
    ): List<TidalSong> {
        return downloadService.searchTidal(call, query, title, artist, count)
    }
}

@OptIn(ExperimentalAtomicApi::class)
class DownloadService(
    val tidalDownloadService: TidalDownloaderProxy,
    val songService: SongService,
    val favSyncService: FavSyncService
) : Service() {
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

    private val _log: MutableStateFlow<String?> = MutableStateFlow(null)
    val log = _log.asSharedFlow()

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
                    .debounce(100)
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

    suspend fun addToQueue(vararg downloadEntries: DownloadQueueEntry) {
        queueMutex.withLock {
            val existingUrls = downloadQueue
                .filterIsInstance<UrlDownloadQueueEntry>()
                .flatMap { it.urls }
                .toMutableList()
            val existingTypes = downloadQueue
                .filterIsInstance<FavouriteDownloadQueueEntry>()
                .map { it.type }
                .toMutableList()

            currentlyDownloading?.let {
                when (it.downloadQueueEntry) {
                    is UrlDownloadQueueEntry -> existingUrls.addAll((it.downloadQueueEntry as UrlDownloadQueueEntry).urls)
                    is FavouriteDownloadQueueEntry -> existingTypes.add(it.downloadQueueEntry.type)
                }
            }

            val entries = downloadEntries.filter {
                when (it) {
                    is UrlDownloadQueueEntry -> {
                        it.urls.removeAll(existingUrls)
                        it.urls.isNotEmpty()
                    }

                    is FavouriteDownloadQueueEntry -> !existingTypes.contains(it.type)
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
            finishedDownloads.filter { it.result.failed() }
        }.map { it.downloadQueueEntry }

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
                _log.emit(line)
                logs.add(line)

                logger.debug(line)

                if (logs.size > maxLogLength) logs.removeFirst()
            }

            val result = when (entry) {
                is UrlDownloadQueueEntry -> tidalDownloadService.downloadContent(
                    urls = entry.urls,
                    maxRetries = entry.maxRetries,
                    aliveCheck = aliveCheck,
                    onLiveOutput = logUnit
                )

                is FavouriteDownloadQueueEntry -> tidalDownloadService.downloadFavoriteCollection(
                    type = entry.tdnFavoriteType,
                    maxRetries = entry.maxRetries,
                    aliveCheck = aliveCheck,
                    onLiveOutput = logUnit
                )
            }

            _log.emit(null)

            currentlyDownloading?.let { currentlyDownloading ->
                currentlyDownloading.result = result
                if (result.successful()) currentlyDownloading.downloadQueueEntry.callback()

                finishedMutex.withLock {
                    finishedDownloads.add(currentlyDownloading)
                    if (finishedDownloads.count { it.result.successful() } > 100) finishedDownloads.removeFirst {
                        it.result.successful()
                    }
                }
            }

        }

        currentlyDownloading = null
        active.store(false)
    }

    suspend fun syncFavouritesAvailable(call: ApplicationCall): Boolean {
        val user = call.getUser() ?: throw IllegalStateException("No user")
        return !(syncMap[user.id]?.load() ?: false)
    }

    /**
     * Downloads and processes Tidal track IDs for the currently authenticated user,
     * ensuring the tracks are added to the download queue if they are not already
     * marked as favorites or present in the user's library.
     *
     * @param call The routing call containing user authentication information and parameters.
     * @param ids A list of track IDs to be processed and downloaded from Tidal.
     * @return A Boolean indicating if something is going to be downloaded, and A list of UUIDs representing the already downloaded songs.
     * @throws IllegalStateException If the user is not authenticated, or if the service type is not Tidal.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun downloadTidalIds(
        call: ApplicationCall,
        ids: Flow<String>,
        type: Type = Type.SONG,
        filter: (UserSong) -> Boolean = { true },
        chunkSize: Int = 250,
        callback: suspend (List<String>) -> Unit = {}
    ): Pair<Boolean, List<UserSong>> {
        val user = call.getUser() ?: throw IllegalStateException("No user")
        val metadataService = call.getMetadataProvider(MetadataService.Companion.MetadataType.tidal)

        val result = mutableListOf<UserSong>()
        var contentToDownload = false

        val downloadStage = mutableListOf<String>()
        val downloadStageMutex = Mutex()

        ids.chunked(chunkSize).buffer(chunkSize * 3).collect { idChunk ->
            val filteredIdChunk = type.getWrapper(metadataService, user, idChunk)

            if (filteredIdChunk.logSize())
                logger.info("[${user.username}] Checking for ${filteredIdChunk.size()} ${type.value}s")

            val existingSongs = if (filteredIdChunk.fetchExistingSongs()) songService.byTidalTrackIds(
                filteredIdChunk.getIds().toList(),
                user.id
            ) else emptyList()

            val existingUrls = existingSongs.map { it.originalUrl }

            result.addAll(existingSongs.filter(filter))

            contentToDownload = type.download(
                downloadService = this,
                wrapper = filteredIdChunk,
                user = user,
                existingUrls = existingUrls,
                downloadStage = downloadStage,
                downloadStageMutex = downloadStageMutex,
                callback = callback
            ) || contentToDownload
        }

        if (downloadStage.isNotEmpty()) {
            addToQueue(
                UrlDownloadQueueEntry(
                    urls = downloadStage.map { "https://tidal.com/${type.value}/${it}" }.toMutableList(),
                    ids = downloadStage,
                    byUser = user.id,
                    type = type
                ) {
                    callback(downloadStage)
                })
        }

        logger.info("[${user.username}] Found ${result.size} existing ${type.value}s")

        return Pair(contentToDownload, result)
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

            val (_, songsToLike) = downloadTidalIds(
                call = call,
                ids = songs.map { it.id },
                type = Type.SONG,
                filter = { !(it.isFavourite ?: false) },
                chunkSize = 25
            ) {
                for (song in songService.byTidalTrackIds(it, user.id)) {
                    if (!(song.isFavourite ?: false)) {
                        val addedAt = idMap[song.originalUrl.tidalId()]?.addedAt?.toInstant()
                        songService.setLiked(song.id, user.id, true, addedAt)
                    }
                }
            }

            logger.info("[${user.username}] Liking existing songs")

            for (song in songsToLike) {
                songService.setLiked(song.id, user.id, true)
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

    suspend fun searchTidal(
        call: ApplicationCall,
        query: String?,
        title: String?,
        artist: String?,
        count: Int
    ): List<TidalSong> {
        val metadataService = call.getMetadataProvider(MetadataService.Companion.MetadataType.tidal)
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
            TidalSong(
                id = it.id,
                title = it.title,
                artists = it.artists,
                cover = it.images.associate { image -> image.width to image.url },
            )
        }
    }
}