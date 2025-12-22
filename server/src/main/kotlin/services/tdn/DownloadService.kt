package dev.dertyp.services.tdn

import dev.dertyp.core.*
import dev.dertyp.data.User
import dev.dertyp.data.UserSong
import dev.dertyp.serializers.UUIDSerializer
import dev.dertyp.services.FavSyncService
import dev.dertyp.services.Service
import dev.dertyp.services.SongService
import dev.dertyp.services.metadata.MetadataService
import dev.dertyp.services.sync.SyncService
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class DownloadService(
    val tdnService: TdnService,
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
    suspend fun startService() {
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

    fun stopService() {
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
                    is UrlDownloadQueueEntry -> existingUrls.addAll(it.downloadQueueEntry.urls)
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
                is UrlDownloadQueueEntry -> tdnService.downloadContent(
                    entry.urls,
                    entry.maxRetries,
                    aliveCheck,
                    logUnit
                )

                is FavouriteDownloadQueueEntry -> tdnService.downloadFavoriteCollection(
                    entry.tdnFavoriteType,
                    entry.maxRetries,
                    aliveCheck,
                    logUnit
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

    suspend fun syncFavouritesAvailable(call: RoutingCall): Boolean {
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
        call: RoutingCall,
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
    suspend fun syncFavourites(call: RoutingCall): CompletableJob {
        val user = call.getUser() ?: throw IllegalStateException("No user")
        if (call.parameters["service"] != SyncService.SyncServiceType.tidal.name) throw IllegalStateException("Only tidal")
        val service = SyncService.getInstance(call, user.username)

        if (service.getAccessToken() == null) throw IllegalStateException("Tidal not authenticated")

        syncMutex.withLock {
            if (!syncMap.computeIfAbsent(user.id) { AtomicBoolean(false) }.compareAndSet(
                    expectedValue = false,
                    newValue = true
                )
            ) throw IllegalStateException("Sync service has already been started")
        }

        return ApplicationScope.scope.async {
            val latestFavSync = favSyncService.getLatestFavSync(user, SyncService.SyncServiceType.tidal)

            val idMap = ConcurrentHashMap<String, SyncService.LikedSong>()

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

            favSyncService.insertFavSync(user, SyncService.SyncServiceType.tidal, Date.from(Instant.now()))

            logger.info("[${user.username}] Sync favourite songs finished.")
        }.launchOnCancellation {
            syncMutex.withLock {
                syncMap[user.id]?.store(false)
            }

            logger.info("[${user.username}] Sync favourite songs cancelled.")
        }
    }
}

data class IdsWrapper(
    val type: Type,
    val idGroups: Flow<IdsGroup>
) {
    suspend fun size() = getIds().toList().size

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getIds(): Flow<String> = idGroups.flatMapConcat { it.ids.map { entry -> entry.second } }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun filter(predicate: (entry: Pair<Long, String>) -> Boolean) =
        idGroups.map { it.filter(predicate) }.flattenConcat()

    fun applyFilter(predicate: (entry: Pair<Long, String>) -> Boolean) =
        copy(
            idGroups = idGroups.map { it.copy(ids = it.ids.filter(predicate)) }.filter { it.ids.toList().isNotEmpty() }
        )

    fun fetchExistingSongs() = when (type) {
        Type.SONG -> true
        else -> false
    }

    fun logSize() = when (type) {
        Type.SONG, Type.ARTIST -> true
        else -> false
    }

    companion object {
        fun from(type: Type, ids: Map<Long, String>): IdsWrapper {
            return IdsWrapper(
                type = type,
                idGroups = listOf(
                    IdsGroup(
                        ids = ids.toList().asFlow()
                    )
                ).asFlow()
            )
        }
    }
}

data class IdsGroup(
    val id: String = UUID.randomUUID().toString(),
    val ids: Flow<Pair<Long, String>>,
    val metadata: MetadataService.BaseMetadata? = null
) {
    fun filter(predicate: (entry: Pair<Long, String>) -> Boolean) = ids.filter(predicate)
}

@Serializable
sealed class DownloadQueueEntry {
    abstract val type: Type?
    abstract val maxRetries: Int
    abstract val byUser: UUID?
    abstract val callback: suspend () -> Unit

    open fun type(): Type? {
        return type
    }
}

@Serializable
data class UrlDownloadQueueEntry(
    val urls: MutableList<String>,
    val ids: Collection<String> = emptyList(),
    @Serializable(with = UUIDSerializer::class)
    override val byUser: UUID? = null,
    override val type: Type? = null,
    @Transient
    override val maxRetries: Int = 5,
    @Transient
    override val callback: suspend () -> Unit = {}
) : DownloadQueueEntry() {
    override fun type(): Type? {
        if (type != null) return type

        val url = urls.first()

        return when {
            url.contains("/track/") -> Type.SONG
            url.contains("/album/") -> Type.ALBUM
            url.contains("/artist/") -> Type.ARTIST
            url.contains("/playlist/") -> Type.PLAYLIST
            else -> null
        }
    }
}

@Serializable
data class FavouriteDownloadQueueEntry(
    val tdnFavoriteType: TdnFavoriteType,
    @Serializable(with = UUIDSerializer::class)
    override val byUser: UUID? = null,
    override val type: Type? = null,
    @Transient
    override val maxRetries: Int = 5,
    @Transient
    override val callback: suspend () -> Unit = {}
) : DownloadQueueEntry()

@Serializable
data class FinishedDownloadQueueEntry(
    val downloadQueueEntry: DownloadQueueEntry,
    var result: ProcessExecutionResult,
    val logs: List<String>,
)

data class LogLine(
    val queueEntry: DownloadQueueEntry,
    val line: String?,
)

@Serializable
enum class Type(val value: String) {
    @SerialName("track")
    SONG("track"),

    @SerialName("album")
    ALBUM("album"),

    @SerialName("playlist")
    PLAYLIST("playlist"),

    @SerialName("artist")
    ARTIST("artist");

    companion object {
        fun fromValue(value: String): Type? {
            return entries.find { it.value == value }
        }
    }
}