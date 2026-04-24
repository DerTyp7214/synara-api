package dev.dertyp.plugins

import dev.dertyp.data.User
import dev.dertyp.services.download.DownloadQueueEntry
import dev.dertyp.services.download.FavouriteDownloadQueueEntry
import dev.dertyp.services.download.FinishedDownloadQueueEntry
import dev.dertyp.services.download.ProcessExecutionResult
import dev.dertyp.services.download.Type
import dev.dertyp.services.download.UrlDownloadQueueEntry
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalAtomicApi::class, FlowPreview::class)
abstract class BaseDownloadService(val context: PluginContext) : IPluginDownloadService {
    protected val maxLogLength: Int = 1000

    protected val queueMutex = Mutex()
    protected val finishedMutex = Mutex()

    protected val downloadQueue: MutableList<DownloadQueueEntry> = arrayListOf()
    protected val finishedDownloads: MutableList<FinishedDownloadQueueEntry> = arrayListOf()

    protected val queueUpdateFlow: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)

    protected val active: AtomicBoolean = AtomicBoolean(false)
    protected val stopped: AtomicBoolean = AtomicBoolean(true)

    protected val internalLog: MutableStateFlow<String?> = MutableStateFlow(null)
    val log = internalLog.asSharedFlow()

    var currentlyDownloading: FinishedDownloadQueueEntry? = null
        protected set

    open suspend fun startService() {
        if (!stopped.compareAndSet(expectedValue = true, newValue = false)) return
        context.logger.info("Starting download service")

        coroutineScope {
            launch {
                queueUpdateFlow
                    .onStart { emit(Unit) }
                    .debounce(100.milliseconds)
                    .takeWhile { !stopped.load() }
                    .collect {
                        download { !stopped.load() }
                    }
            }
        }

        context.logger.info("Stopping download service")
        stopped.store(true)
    }

    open suspend fun stopService() {
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

            context.logger.info("Adding ${entries.size} to queue")
            downloadQueue.addAll(entries)
        }
        queueUpdateFlow.tryEmit(Unit)
    }

    protected abstract suspend fun getDownloaderForEntry(entry: DownloadQueueEntry): IDownloader?
    protected abstract suspend fun getAllDownloaders(): Collection<IDownloader>

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
                if (logs.size > maxLogLength) logs.removeFirst()
            }

            val result = when (entry) {
                is UrlDownloadQueueEntry -> {
                    val groups = entry.urls.groupBy { url ->
                        getAllDownloaders().find { it.canHandle(url) }
                    }
                    var lastRes = ProcessExecutionResult.EMPTY
                    for ((downloader, groupUrls) in groups) {
                        if (downloader != null) {
                            lastRes = downloader.downloadContent(groupUrls, entry.maxRetries, aliveCheck, entry.byUser, logUnit)
                        }
                    }
                    lastRes
                }
                is FavouriteDownloadQueueEntry -> {
                    getDownloaderForEntry(entry)?.downloadFavoriteCollection(entry.favoriteType, entry.maxRetries, aliveCheck, entry.byUser, logUnit)
                        ?: ProcessExecutionResult(-1, "No downloader for favorites", "")
                }
            }

            internalLog.emit(null)
            currentlyDownloading?.let { current ->
                current.result = result
                if (result.successful()) current.downloadQueueEntry.callback()
                finishedMutex.withLock {
                    finishedDownloads.add(current)
                    if (finishedDownloads.count { it.result.successful() } > 100) {
                        finishedDownloads.removeIf { it.result.successful() }
                    }
                }
            }
        }
        currentlyDownloading = null
        active.store(false)
    }

    open suspend fun downloadIds(
        ids: Flow<String>,
        type: Type,
        user: User,
        downloaderId: String? = null,
        callback: suspend (List<String>) -> Unit = {}
    ): Boolean {
        var contentToDownload = false
        val allDownloaders = getAllDownloaders()
        ids.collect { id ->
            val downloader = if (downloaderId != null) {
                allDownloaders.find { it.id == downloaderId }
            } else {
                allDownloaders.firstOrNull()
            }
            if (downloader != null) {
                if (downloader.downloadIds(listOf(id), type, user, callback).first) {
                    contentToDownload = true
                }
            }
        }
        return contentToDownload
    }
}
