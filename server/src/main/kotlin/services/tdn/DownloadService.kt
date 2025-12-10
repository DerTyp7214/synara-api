package dev.dertyp.services.tdn

import dev.dertyp.core.removeFirst
import dev.dertyp.core.waitForChange
import dev.dertyp.services.Service
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class DownloadService(
    val tdnService: TdnService
) : Service() {
    private val maxLogLength: Int = 1000

    private val queueMutex = Mutex()

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
                .filter { it is UrlDownloadQueueEntry }
                .map { (it as UrlDownloadQueueEntry).url }
                .toMutableList()
            val existingTypes = downloadQueue
                .filter { it is FavouriteDownloadQueueEntry }
                .map { (it as FavouriteDownloadQueueEntry).type }
                .toMutableList()

            currentlyDownloading?.let {
                when (it.downloadQueueEntry) {
                    is UrlDownloadQueueEntry -> existingUrls.add(it.downloadQueueEntry.url)
                    is FavouriteDownloadQueueEntry -> existingTypes.add(it.downloadQueueEntry.type)
                }
            }

            val entries = downloadEntries.filter {
                when (it) {
                    is UrlDownloadQueueEntry -> !existingUrls.contains(it.url)
                    is FavouriteDownloadQueueEntry -> !existingTypes.contains(it.type)
                }
            }

            logger.info("Adding ${entries.size} to queue")

            downloadQueue.addAll(entries)
        }

        queueUpdateFlow.emit(Unit)
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

    suspend fun finishedDownloads(): List<FinishedDownloadQueueEntry> {
        return queueMutex.withLock {
            finishedDownloads.toList()
        }
    }

    suspend fun clearErrors() {
        queueMutex.withLock {
            finishedDownloads.removeIf {
                !it.result.successful()
            }
        }
    }

    suspend fun retryErrors() {
        val errors = queueMutex.withLock {
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

                if (logs.size > maxLogLength) logs.removeFirst()
            }

            val result = when (entry) {
                is UrlDownloadQueueEntry -> tdnService.downloadContent(
                    entry.url,
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
                queueMutex.withLock {
                    currentlyDownloading.result = result

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
}

@Serializable
sealed class DownloadQueueEntry() {
    abstract val type: Type?
    abstract val maxRetries: Int

    open fun type(): Type? {
        return type
    }
}

@Serializable
data class UrlDownloadQueueEntry(
    val url: String,
    val id: String = "",
    override val type: Type? = null,
    override val maxRetries: Int = 5,
) : DownloadQueueEntry() {
    override fun type(): Type? {
        if (type != null) return type

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
    override val type: Type? = null,
    override val maxRetries: Int = 5,
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
enum class Type {
    SONG,
    ALBUM,
    PLAYLIST,
    ARTIST,
}