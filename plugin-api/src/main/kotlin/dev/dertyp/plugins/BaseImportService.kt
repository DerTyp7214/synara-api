package dev.dertyp.plugins

import dev.dertyp.data.User
import dev.dertyp.services.import.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalAtomicApi::class, FlowPreview::class)
abstract class BaseImportService(val context: PluginContext) : IPluginImportService {
    protected val maxLogLength: Int = 1000

    protected val queueMutex = Mutex()
    protected val finishedMutex = Mutex()

    protected val importQueue: MutableList<ImportQueueEntry> = arrayListOf()
    protected val finishedImports: MutableList<FinishedImportQueueEntry> = arrayListOf()

    protected val queueUpdateFlow: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)

    protected val active: AtomicBoolean = AtomicBoolean(false)
    protected val stopped: AtomicBoolean = AtomicBoolean(true)

    protected val internalLog: MutableStateFlow<String?> = MutableStateFlow(null)
    val log = internalLog.asSharedFlow()

    var currentlyImporting: FinishedImportQueueEntry? = null
        protected set

    open suspend fun startService() {
        if (!stopped.compareAndSet(expectedValue = true, newValue = false)) return
        context.logger.info("Starting import service")

        coroutineScope {
            launch {
                queueUpdateFlow
                    .onStart { emit(Unit) }
                    .debounce(100.milliseconds)
                    .takeWhile { !stopped.load() }
                    .collect {
                        import { !stopped.load() }
                    }
            }
        }

        context.logger.info("Stopping import service")
        stopped.store(true)
    }

    open suspend fun stopService() {
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

            currentlyImporting?.let {
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

            context.logger.info("Adding ${entries.size} to queue")
            importQueue.addAll(entries)
        }
        queueUpdateFlow.tryEmit(Unit)
    }

    protected abstract suspend fun getImporterForEntry(entry: ImportQueueEntry): IImporter?
    protected abstract suspend fun getAllImporters(): Collection<IImporter>

    private suspend fun import(aliveCheck: suspend () -> Boolean) {
        if (!active.compareAndSet(expectedValue = false, newValue = true)) return

        while (queueMutex.withLock { importQueue.isNotEmpty() }) {
            val entry = queueMutex.withLock { importQueue.removeFirst() }

            val logs = mutableListOf<String>()
            currentlyImporting = FinishedImportQueueEntry(
                importQueueEntry = entry,
                result = ProcessExecutionResult.EMPTY,
                logs = logs
            )

            val logUnit: suspend (String) -> Unit = { line ->
                internalLog.emit(line)
                logs.add(line)
                if (logs.size > maxLogLength) logs.removeFirst()
            }

            val result = when (entry) {
                is UrlImportQueueEntry -> {
                    val defaultImporter = entry.importer?.let { db -> getAllImporters().find { it.id == db.id } }
                    val groups = entry.urls.groupBy { url ->
                        if (defaultImporter?.canHandle(url) == true) defaultImporter
                        else getAllImporters().find { it.canHandle(url) } ?: defaultImporter
                    }
                    var lastRes = ProcessExecutionResult.EMPTY
                    for ((importer, groupUrls) in groups) {
                        if (importer != null) {
                            lastRes = importer.importContent(groupUrls, entry.maxRetries, aliveCheck, entry.byUser, entry.metadata, logUnit)
                        }
                    }
                    lastRes
                }
                is FavouriteImportQueueEntry -> {
                    val importer = entry.importer?.let { db -> getAllImporters().find { it.id == db.id } } ?: getImporterForEntry(entry)
                    importer?.importFavoriteCollection(entry.favoriteType, entry.maxRetries, aliveCheck, entry.byUser, logUnit)
                        ?: ProcessExecutionResult(-1, "No importer for favorites", "")
                }
            }

            internalLog.emit(null)
            currentlyImporting?.let { current ->
                current.result = result
                if (result.successful()) current.importQueueEntry.callback()
                finishedMutex.withLock {
                    finishedImports.add(current)
                    if (finishedImports.count { it.result.successful() } > 100) {
                        finishedImports.removeIf { it.result.successful() }
                    }
                }
            }
        }
        currentlyImporting = null
        active.store(false)
    }

    open suspend fun importIds(
        ids: Flow<String>,
        type: Type,
        user: User,
        importerId: String? = null,
        callback: suspend (List<String>) -> Unit = {}
    ): Boolean {
        var contentToImport = false
        val allImporters = getAllImporters()
        ids.collect { id ->
            val importer = if (importerId != null) {
                allImporters.find { it.id == importerId }
            } else {
                allImporters.firstOrNull()
            }
            if (importer != null) {
                if (importer.importIds(listOf(id), type, user, callback).first) {
                    contentToImport = true
                }
            }
        }
        return contentToImport
    }
}
