package dev.dertyp.services.import

import dev.dertyp.PrefixedId
import dev.dertyp.core.*
import dev.dertyp.data.User
import dev.dertyp.data.UserSong
import dev.dertyp.getPrefix
import dev.dertyp.killAll
import dev.dertyp.plugins.IImporter
import dev.dertyp.plugins.IPluginImportService
import dev.dertyp.plugins.JobContext
import dev.dertyp.plugins.JobInfo
import dev.dertyp.plugins.JobStatus
import dev.dertyp.services.jobs.JobService
import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.*
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.LinkResolverService
import dev.dertyp.services.sync.SyncService
import dev.dertyp.stripPrefix
import dev.dertyp.utils.LogParam
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.engine.launchOnCancellation
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
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
    private val linkResolver by inject<LinkResolverService>()
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
        importService.logger.info("Processing ${urls.size} import inputs for user ${user.username}")

        val targets = urls.mapNotNull { input ->
            val trimmed = input.trim()
            when (val code = MusicCode.classify(trimmed)) {
                MusicCode.Url -> ImportTarget.Route(input)
                is MusicCode.Isrc -> resolveCode(trimmed, isrc = code.value)
                is MusicCode.Upc -> resolveCode(trimmed, upc = code.value)
            }
        }

        if (targets.isEmpty()) {
            importService.logger.warn("No import inputs could be resolved")
            return
        }

        targets.filterIsInstance<ImportTarget.Ids>().forEach { target ->
            importService.logger.info("Routing ${target.ids.size} items of type ${target.type} to ${target.importerId} (resolved via metadata)")
            importService.importIds(target.ids.asFlow(), target.type, user, target.importerId)
        }

        val urlsToImport = targets.filterIsInstance<ImportTarget.Route>().map { it.url }
        if (urlsToImport.isEmpty()) return

        val resolved = urlsToImport.map { it to importerProxy.resolveImporter(it) }
        val groups = resolved.groupBy({ it.second?.first }) { (originalUrl, match) ->
            match?.second ?: originalUrl
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

                parsed.mapNotNull { it.second }.groupBy { it.second ?: Type.SONG }.forEach { (type, resultPairs) ->
                    val ids = resultPairs.map { it.first }
                    importService.logger.info("Routing ${ids.size} items of type $type to ${importer.id}")
                    importService.importIds(ids.asFlow(), type, user, importer.id)
                }
            }
        }
    }

    private sealed interface ImportTarget {
        data class Route(val url: String) : ImportTarget
        data class Ids(val importerId: String, val ids: List<String>, val type: Type) : ImportTarget
    }

    private suspend fun resolveCode(original: String, isrc: String? = null, upc: String? = null): ImportTarget? {
        val label = if (isrc != null) "ISRC" else "UPC"

        if (linkResolver.enabled) {
            val resolved = importerProxy.resolveImporterByCode(isrc = isrc, upc = upc)
            if (resolved == null) {
                importService.logger.warn("Link resolver could not resolve $label $original")
                return null
            }
            val (importer, url) = resolved
            val parsed = importer.parseUrl(url)
            return if (parsed != null) ImportTarget.Ids(importer.id, listOf(parsed.first), parsed.second ?: Type.SONG)
            else ImportTarget.Route(url)
        }

        val importer = importerProxy.getImporter(importerProxy.defaultService)
        val metadataType = importer.metadataType
        if (metadataType == null) {
            importService.logger.warn("Default importer ${importer.id} has no metadata service to resolve $label $original")
            return null
        }
        val metadata = call.getMetadataProvider(metadataType)
        if (metadata == null) {
            importService.logger.warn("No metadata provider for ${metadataType.value} to resolve $label $original")
            return null
        }

        val id: String?
        val type: Type
        if (isrc != null) {
            id = metadata.getTrackByIsrc(isrc)?.id
            type = Type.SONG
        } else {
            id = metadata.getAlbumByBarcode(upc!!)?.id
            type = Type.ALBUM
        }

        if (id == null) {
            importService.logger.warn("Default importer ${importer.id} metadata could not resolve $label $original")
            return null
        }

        return ImportTarget.Ids(importer.id, listOf(id), type)
    }

    override suspend fun getImporterForUrl(url: String): ImportBackend? {
        return importerProxy.resolveImporter(url)?.let { ImportBackend(it.first.id) }
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

    override suspend fun getImporterCapabilities(): Map<String, Set<ImporterCapability>> =
        importService.pluginManager.getAllImporters().associate { it.id to it.capabilities }

    override suspend fun setImportCredentials(backend: ImportBackend, credentials: ImporterCredentials) {
        val importer = importService.pluginManager.getImporter(backend.id)
            ?: throw IllegalArgumentException("Unknown importer backend: ${backend.id}")
        require(importer.capabilities.contains(ImporterCapability.CREDENTIALS)) {
            "Importer '${backend.id}' does not support credential injection."
        }
        importer.provideCredentials(credentials)
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
    val jobService: JobService,
) : IPluginImportService, Service() {
    private val maxLogLength: Int = 1000

    private val syncMutex = Mutex()
    private val finishedMutex = Mutex()

    private val syncMap = ConcurrentHashMap<UUID, AtomicBoolean>()

    private val finishedImports: MutableList<FinishedImportQueueEntry> = arrayListOf()

    val queueChanges: Flow<Unit> = jobService.changes

    private val stopped: AtomicBoolean = AtomicBoolean(true)

    private val internalLog: MutableStateFlow<String?> = MutableStateFlow(null)
    val log = internalLog.asSharedFlow()

    var currentImport: FinishedImportQueueEntry? = null
        private set

    class ImportJob(val info: JobInfo, val entry: ImportQueueEntry)

    init {
        jobService.pause(JOB_KIND)
    }

    override suspend fun startService() {
        stopped.store(false)
        jobService.resume(JOB_KIND)
    }

    override suspend fun stopService() {
        stopped.store(true)
        jobService.pause(JOB_KIND)
    }

    private fun importJobsRaw(): List<JobService.Job> = jobService.jobsOf(JOB_KIND)

    fun importJobs(user: User? = null): List<ImportJob> = importJobsRaw()
        .filter { user == null || user.isAdmin || it.info.user == user.id }
        .mapNotNull { job -> (job.payload as? ImportQueueEntry)?.let { ImportJob(job.info, it) } }

    private fun pendingEntries(): List<ImportQueueEntry> =
        importJobsRaw().filter { it.info.status == JobStatus.PENDING }.mapNotNull { it.payload as? ImportQueueEntry }

    override suspend fun addToQueue(vararg importEntries: ImportQueueEntry) {
        val pending = pendingEntries()
        val existingUrls = pending.filterIsInstance<UrlImportQueueEntry>().flatMap { it.urls }.toMutableList()
        val existingTypes = pending.filterIsInstance<FavouriteImportQueueEntry>().map { it.favoriteType }.toMutableList()

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

        entries.forEach { entry ->
            jobService.enqueue(JOB_KIND, titleOf(entry), entry.byUser, summaryOf(entry), payload = entry) { runEntry(entry, this) }
        }
    }

    private fun titleOf(entry: ImportQueueEntry): String = when (entry) {
        is UrlImportQueueEntry -> entry.urls.firstOrNull() ?: "${entry.ids.size} ${entry.type?.value ?: "items"}"
        is FavouriteImportQueueEntry -> "Favorites (${entry.favoriteType.name})"
    }

    private fun summaryOf(entry: ImportQueueEntry): String = when (entry) {
        is UrlImportQueueEntry -> entry.urls.joinToString(", ")
        is FavouriteImportQueueEntry -> entry.favoriteType.name
    }

    fun isActive(): Boolean = importJobsRaw().any { it.info.status == JobStatus.RUNNING }

    suspend fun waitForInactive() {
        queueChanges.onStart { emit(Unit) }.first { !isActive() }
    }

    suspend fun waitForActive() {
        queueChanges.onStart { emit(Unit) }.first { isActive() }
    }

    fun isStopped(): Boolean {
        return stopped.load()
    }

    fun queueSize(): Int = pendingEntries().size

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

    suspend fun importQueue(user: User? = null): List<ImportQueueEntry> =
        pendingEntries().filter { user == null || user.isAdmin || it.byUser == user.id }

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

    private suspend fun runEntry(entry: ImportQueueEntry, context: JobContext) {
        val logs = mutableListOf<String>()
        val running = FinishedImportQueueEntry(
            importQueueEntry = entry,
            result = ProcessExecutionResult.EMPTY,
            logs = logs
        )
        currentImport = running

        val aliveCheck: suspend () -> Boolean = { context.isActive() && !stopped.load() }
        val logUnit: suspend (String) -> Unit = { line ->
            internalLog.emit(line)
            logs.add(line)
            context.log(line)

            logger.debug(line)

            if (logs.size > maxLogLength) logs.removeFirst()
        }

        val result = try {
            when (entry) {
                is UrlImportQueueEntry -> importerProxy.importContent(
                    urls = entry.urls,
                    maxRetries = entry.maxRetries,
                    aliveCheck = aliveCheck,
                    userId = entry.byUser,
                    service = entry.importer,
                    metadata = entry.metadata,
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
        } finally {
            internalLog.emit(null)
        }

        running.result = result
        if (result.successful()) entry.callback()

        finishedMutex.withLock {
            finishedImports.add(running)
            if (finishedImports.count { it.result.successful() } > 100) finishedImports.removeIf {
                it.result.successful()
            }
        }

        currentImport = null
        if (result.failed()) throw IllegalStateException(result.error.ifBlank { "Import failed (exit ${result.exitCode})" })
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
            val im = try {
                importerProxy.getImporter(ImportBackend(importerId ?: importerProxy.defaultService.id))
            } catch (_: Exception) {
                null
            }

            if (im != null && im.enabled) {
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

        val handle = Job()
        jobService.enqueue(FAVOURITES_KIND, "Favorites sync", user.id, user.username) {
          try {
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
          } finally {
                syncMutex.withLock {
                    syncMap[user.id]?.store(false)
                }
                handle.complete()
            }
        }
        return handle
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

    companion object {
        const val JOB_KIND = "import"
        const val FAVOURITES_KIND = "favourites"
    }
}
