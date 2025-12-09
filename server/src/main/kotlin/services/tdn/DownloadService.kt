package dev.dertyp.services.tdn

import dev.dertyp.core.removeFirst
import dev.dertyp.services.Service
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalAtomicApi::class)
class DownloadService(
    private val tdnService: TdnService
) : Service() {
    private val maxRetries: Int = 25
    private val maxLogLength: Int = 1000

    private val queueMutex = Mutex()

    private val downloadQueue: MutableList<DownloadQueueEntry> = arrayListOf()
    private val finishedDownloads: MutableList<FinishedDownloadQueueEntry> = arrayListOf()

    private val active: AtomicBoolean = AtomicBoolean(false)
    private val stopped: AtomicBoolean = AtomicBoolean(true)

    private val _log: MutableStateFlow<String?> = MutableStateFlow(null)
    val log = _log.asSharedFlow()

    var currentlyDownloading: FinishedDownloadQueueEntry? = null
        private set

    suspend fun startService() {
        if (!stopped.compareAndSet(expectedValue = true, newValue = false)) return

        coroutineScope {
            launch {
                while (!stopped.load()) {
                    if (tdnService.authorized()) download { !stopped.load() }

                    delay(1.hours)
                }
            }
        }

        stopped.store(true)
    }

    fun stopService() {
        stopped.store(true)
    }

    suspend fun addToQueue(vararg downloadEntries: DownloadQueueEntry) {
        queueMutex.withLock {
            downloadQueue.addAll(downloadEntries)
        }
    }

    fun isActive(): Boolean {
        return active.load()
    }

    fun isStopped(): Boolean {
        return stopped.load()
    }

    fun logs(): Flow<String> {
        val oldLogs = currentlyDownloading?.logs ?: emptyList()

        return oldLogs.asFlow().onCompletion { if (it == null) emitAll(log.filterNotNull()) }
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
                    maxRetries,
                    aliveCheck,
                    logUnit
                )

                is FavouriteDownloadQueueEntry -> tdnService.downloadFavoriteCollection(
                    entry.tdnFavoriteType,
                    maxRetries,
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

            currentlyDownloading = null
        }

        active.store(false)
    }
}

@Serializable
sealed class DownloadQueueEntry() {
    abstract val type: Type?

    open fun type(): Type? {
        return type
    }
}

@Serializable
class UrlDownloadQueueEntry(
    val url: String,
    val id: String = "",
    override val type: Type? = null,
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
class FavouriteDownloadQueueEntry(
    val tdnFavoriteType: TdnFavoriteType,
    override val type: Type? = null
) : DownloadQueueEntry()

@Serializable
data class FinishedDownloadQueueEntry(
    val downloadQueueEntry: DownloadQueueEntry,
    var result: ProcessExecutionResult,
    val logs: List<String>,
)

@Serializable
enum class Type {
    SONG,
    ALBUM,
    PLAYLIST,
    ARTIST,
}