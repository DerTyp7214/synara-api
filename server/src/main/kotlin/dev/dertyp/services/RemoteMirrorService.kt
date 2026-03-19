package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.rpc.krpc.ktor.client.Krpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.rpc.withService
import kotlinx.serialization.ExperimentalSerializationApi
import org.koin.core.component.inject
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.outputStream
import kotlin.time.Duration.Companion.nanoseconds

class RemoteMirrorRpcService(
    private val user: User,
    private val remoteMirrorService: RemoteMirrorService
) : IRemoteMirrorService {
    override suspend fun getRemoteStats(config: RemoteServerConfig): ServerStats {
        if (!user.isAdmin) throw IllegalStateException("Only admins can initiate mirrors")
        return remoteMirrorService.getRemoteStats(config)
    }

    override suspend fun startMirror(config: RemoteServerConfig) {
        if (!user.isAdmin) throw IllegalStateException("Only admins can initiate mirrors")
        remoteMirrorService.startMirror(config)
    }

    override suspend fun stopMirror() {
        if (!user.isAdmin) throw IllegalStateException("Only admins can stop mirrors")
        remoteMirrorService.stopMirror()
    }

    override suspend fun resetMirror() {
        if (!user.isAdmin) throw IllegalStateException("Only admins can reset mirrors")
        remoteMirrorService.resetMirror()
    }

    override fun getActiveMirrorProgress(): Flow<MirrorProgress>? {
        if (!user.isAdmin) throw IllegalStateException("Only admins can initiate mirrors")
        return remoteMirrorService.getActiveMirrorProgress()
    }

    override suspend fun getRemoteUsers(config: RemoteServerConfig): List<User> {
        if (!user.isAdmin) throw IllegalStateException("Only admins can initiate mirrors")
        return remoteMirrorService.getRemoteUsers(config)
    }

    override suspend fun getRemotePlaylists(config: RemoteServerConfig): List<Playlist> {
        if (!user.isAdmin) throw IllegalStateException("Only admins can initiate mirrors")
        return remoteMirrorService.getRemotePlaylists(config)
    }

    override suspend fun getRemoteUserPlaylists(config: RemoteServerConfig): List<UserPlaylist> {
        if (!user.isAdmin) throw IllegalStateException("Only admins can initiate mirrors")
        return remoteMirrorService.getRemoteUserPlaylists(config)
    }

    override suspend fun getProxyInstances(config: RemoteServerConfig): List<ProxyInstanceInfo> {
        if (!user.isAdmin) throw IllegalStateException("Only admins can initiate mirrors")
        return remoteMirrorService.getProxyInstances(config)
    }
}

class RemoteMirrorService : Service() {
    private val songService by inject<SongService>()
    private val artistService by inject<ArtistService>()
    private val albumService by inject<AlbumService>()
    private val imageService by inject<ImageService>()
    private val storageService by inject<StorageService>()
    private val playlistService by inject<PlaylistService>()
    private val userPlaylistService by inject<UserPlaylistService>()

    @OptIn(ExperimentalSerializationApi::class)
    private val httpClient = HttpClient(CIO) {
        install(WebSockets)
        install(ContentNegotiation) {
            json(ApplicationScope.json)
        }
        install(Krpc) {
            serialization {
                cbor(ApplicationScope.cbor)
            }
        }
    }

    private val tokenCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private class RemoteConnection(
        val token: String,
        val mirrorService: IMirrorService,
        val imageService: IImageService,
    )

    private val connectionCache = java.util.concurrent.ConcurrentHashMap<String, RemoteConnection>()
    private val statsServiceCache = java.util.concurrent.ConcurrentHashMap<String, IServerStatsService>()

    private val _activeProgress = MutableStateFlow<MirrorProgress?>(null)
    private var isMirroring = false
    private var mirrorJob: Job? = null

    @Suppress("HttpUrlsUsage")
    private fun RemoteServerConfig.toFullUrl(path: String): String {
        val protocol = if (secure) "wss" else "ws"
        val cleanHost = host.removePrefix("http://").removePrefix("https://").removeSuffix("/")
        val base = "$protocol://$cleanHost:$port"
        val targetPath = if (useProxy && !proxyInstanceId.isNullOrEmpty()) {
            "/${proxyInstanceId!!.removePrefix("/")}/${path.removePrefix("/")}"
        } else {
            "/${path.removePrefix("/")}"
        }
        return "$base$targetPath"
    }

    suspend fun getRemoteStats(config: RemoteServerConfig): ServerStats {
        val key = "${config.host}:${config.port}"
        val statsService = statsServiceCache.getOrPut(key) {
            httpClient.rpc(config.toFullUrl("/rpc")).withService<IServerStatsService>()
        }
        return statsService.getStats()
    }

    fun startMirror(config: RemoteServerConfig) {
        if (isMirroring) {
            logger.warn("Mirror already in progress, ignoring start request")
            return
        }

        logger.info("Starting mirror from remote server: ${config.host}:${config.port} (Quality: ${config.quality})")
        mirrorJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                isMirroring = true
                performMirror(config)
                logger.info("Mirror operation from ${config.host} completed successfully")
            } catch (_: CancellationException) {
                logger.warn("Mirror operation from ${config.host} was stopped by user")
                _activeProgress.value =
                    MirrorProgress("Mirror stopped by user", 0, 0, true, "Stopped")
            } catch (e: Exception) {
                logger.error("Mirror from ${config.host} failed: ${e.message}", e)
                _activeProgress.value = MirrorProgress("Error during mirror", 0, 0, true, e.message)
            } finally {
                isMirroring = false
                mirrorJob = null
            }
        }
    }

    fun stopMirror() {
        if (mirrorJob != null) {
            logger.info("Stopping active mirror job...")
            mirrorJob?.cancel()
        }
    }

    fun resetMirror() {
        if (isMirroring) {
            logger.error("Attempted to reset mirror while operation is active")
            throw IllegalStateException("Cannot reset while mirroring is in progress")
        }
        logger.info("Resetting remote mirror state")
        _activeProgress.value = null
    }

    fun getActiveMirrorProgress(): Flow<MirrorProgress>? {
        if (!isMirroring && (_activeProgress.value == null || _activeProgress.value?.isFinished == false)) return null
        return _activeProgress.filterNotNull()
    }

    suspend fun getRemoteUsers(config: RemoteServerConfig): List<User> =
        getAuthenticatedConnection(config).mirrorService.getUsers().toList()

    suspend fun getRemotePlaylists(config: RemoteServerConfig): List<Playlist> =
        getAuthenticatedConnection(config).mirrorService.getPlaylists().toList()

    suspend fun getRemoteUserPlaylists(config: RemoteServerConfig): List<UserPlaylist> =
        getAuthenticatedConnection(config).mirrorService.getUserPlaylists().toList()

    @Suppress("HttpUrlsUsage")
    suspend fun getProxyInstances(config: RemoteServerConfig): List<ProxyInstanceInfo> {
        val protocol = if (config.secure) "https" else "http"
        val cleanHost =
            config.host.removePrefix("http://").removePrefix("https://").removeSuffix("/")
        val url = "$protocol://$cleanHost:${config.port}/instances"

        return withRemoteClient { client ->
            client.get(url).body<List<ProxyInstanceInfo>>()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun getAuthenticatedConnection(config: RemoteServerConfig): RemoteConnection {
        val key = "${config.host}:${config.port}:${config.username}"
        val token = getOrFetchToken(config)

        connectionCache[key]?.let {
            if (it.token == token) return it
        }

        val rpcClient = httpClient.rpc(config.toFullUrl("/rpc/services")) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        val connection = RemoteConnection(
            token,
            rpcClient.withService<IMirrorService>(),
            rpcClient.withService<IImageService>()
        )
        connectionCache[key] = connection
        return connection
    }

    private suspend fun getOrFetchToken(config: RemoteServerConfig): String {
        val key = "${config.host}:${config.port}:${config.username}"
        tokenCache[key]?.let { return it }

        val authService = httpClient.rpc(config.toFullUrl("/rpc/auth")).withService<IAuthService>()
        val authResponse = authService.authenticate(config.username, config.password)
        tokenCache[key] = authResponse.token
        return authResponse.token
    }

    suspend fun getRemoteImageData(config: RemoteServerConfig, imageId: PlatformUUID, size: Int = 0): ByteArray? {
        return getAuthenticatedConnection(config).imageService.getImageData(imageId, size)
    }

    @OptIn(ExperimentalSerializationApi::class, ExperimentalCoroutinesApi::class)
    private suspend fun performMirror(config: RemoteServerConfig) {
        val client = httpClient
        logger.info("Authenticating with remote server at ${config.toFullUrl("/rpc/auth")}")
        val token = getOrFetchToken(config)
        logger.info("Successfully authenticated as ${config.username}")

        val mirrorService =
            client.rpc(config.toFullUrl("/rpc/services")) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.withService<IMirrorService>()

        val remoteImageService =
            client.rpc(config.toFullUrl("/rpc/services")) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.withService<IImageService>()

        val remoteSongService =
            client.rpc(config.toFullUrl("/rpc/services")) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.withService<ISongService>()

        val statsService = client.rpc(config.toFullUrl("/rpc")) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.withService<IServerStatsService>()

                logger.info("Fetching remote statistics...")
                val remoteStats = statsService.getStats()
                logger.info("Remote library summary: ${remoteStats.songCount} songs, ${remoteStats.albumCount} albums, ${remoteStats.artistCount} artists, ${remoteStats.imagesCount} images")

                val remotePaths = mirrorService.getServerPaths()

                fun formatBytes(bytes: Long): String {
                    val units = listOf("B", "KB", "MB", "GB", "TB")
                    var size = bytes.toDouble()
                    var unitIndex = 0
                    while (size >= 1024 && unitIndex < units.size - 1) {
                        size /= 1024
                        unitIndex++
                    }
                    return "%.2f %s".format(size, units[unitIndex])
                }

                fun formatDuration(seconds: Long): String {
                    if (seconds < 0) return "0s"
                    if (seconds < 60) return "${seconds}s"
                    val mins = seconds / 60
                    val secs = seconds % 60
                    if (mins < 60) return "${mins}m ${secs}s"
                    val hours = mins / 60
                    val remainingMins = mins % 60
                    return "${hours}h ${remainingMins}m"
                }

                fun resolveLocalPath(remotePath: String, id: String, quality: Int): String {
                    val extension =
                        if (quality == -1) remotePath.substringAfterLast('.', "flac") else "ogg"

                    fun String.fixExtension(): String {
                        if (quality == -1) return this
                        return if (this.contains('.')) {
                            this.substringBeforeLast('.') + ".ogg"
                        } else {
                            "$this.ogg"
                        }
                    }

                    if (remotePaths.customAudioPath != null && remotePath.startsWith(remotePaths.customAudioPath!!)) {
                        val relative = remotePath.removePrefix(remotePaths.customAudioPath!!)
                            .trimStart('/', '\\')
                        return Path(
                            storageService.customAudioPath,
                            relative.fixExtension()
                        ).absolutePathString()
                    }

                    if (remotePaths.tracksPath != null && remotePath.startsWith(remotePaths.tracksPath!!)) {
                        val relative =
                            remotePath.removePrefix(remotePaths.tracksPath!!).trimStart('/', '\\')
                        return Path(
                            storageService.tracksPath!!,
                            relative.fixExtension()
                        ).absolutePathString()
                    }

                    for (secondaryRemote in remotePaths.secondaryTracksPaths) {
                        if (remotePath.startsWith(secondaryRemote)) {
                            val relative =
                                remotePath.removePrefix(secondaryRemote).trimStart('/', '\\')
                            return Path(
                                storageService.tracksPath!!,
                                relative.fixExtension()
                            ).absolutePathString()
                        }
                    }

                    return Path(storageService.tracksPath!!, "$id.$extension").absolutePathString()
                }

                var stageStartTime = System.currentTimeMillis()
                var lastTask: String? = null
                val progressHistory = mutableListOf<Triple<Long, Double, Long?>>()
                var statusMessage: String? = null

                var syncedSongs = 0
                var syncedArtists = 0
                var syncedAlbums = 0
                var syncedImages = 0
                var syncedPlaylists = 0
                var syncedUserPlaylists = 0
                var syncedErrors = 0
                val failedItemNames = mutableListOf<String>()
                val progressMutex = Mutex()

                fun updateProgress(
                    task: String,
                    processed: Int,
                    total: Int,
                    item: String? = null,
                    itemProgress: Float? = null,
                    byteCount: Long? = null,
                    newStatus: String? = null,
                    isFinished: Boolean = false,
                    error: String? = null
                ) {
                    val now = System.currentTimeMillis()
                    if (lastTask != task) {
                        progressHistory.clear()
                        lastTask = task
                    }
                    if (newStatus != null) statusMessage = newStatus

                    val currentTotalProgress =
                        processed.toDouble() + (itemProgress ?: 0f).toDouble()
                    progressHistory.add(Triple(now, currentTotalProgress, byteCount))
                    while (progressHistory.size > 1 && progressHistory.first().first < now - 5000) {
                        progressHistory.removeAt(0)
                    }

                    val elapsed = (now - stageStartTime) / 1000.0
                    val oldest = progressHistory.first()
                    val windowElapsed = (now - oldest.first) / 1000.0

                    val speedStr = if (windowElapsed > 0.5) {
                        if (byteCount != null && oldest.third != null) {
                            val bytesDiff = byteCount - oldest.third!!
                            "${formatBytes((bytesDiff / windowElapsed).toLong())}/s"
                        } else {
                            val itemsDiff = currentTotalProgress - oldest.second
                            "%.1f items/s".format(itemsDiff / windowElapsed)
                        }
                    } else if (elapsed > 0.1) {
                        if (byteCount != null) {
                            "${formatBytes((byteCount / elapsed).toLong())}/s"
                        } else {
                            val itemsDiff = processed / elapsed
                            "%.1f items/s".format(itemsDiff)
                        }
                    } else null

                    val etaStr = run {
                        if (windowElapsed > 1.0 && total > currentTotalProgress) {
                            val itemsDiff = currentTotalProgress - oldest.second
                            if (itemsDiff > 0) {
                                val itemsPerSecond = itemsDiff / windowElapsed
                                val remainingItems = total - currentTotalProgress
                                return@run formatDuration((remainingItems / itemsPerSecond).toLong())
                            }
                        }

                        if (elapsed > 1.0 && total > currentTotalProgress && currentTotalProgress > 0) {
                            val itemsProgress = currentTotalProgress / total
                            val remainingSeconds = (elapsed / itemsProgress) - elapsed
                            if (remainingSeconds > 0) return@run formatDuration(remainingSeconds.toLong())
                        }
                        null
                    }

                    val progress =
                        MirrorProgress(
                            task,
                            processed,
                            total,
                            isFinished,
                            error,
                            item,
                            itemProgress,
                            speedStr,
                            etaStr,
                            statusMessage,
                            if (isFinished) SyncBreakdown(
                                songs = syncedSongs,
                                artists = syncedArtists,
                                albums = syncedAlbums,
                                images = syncedImages,
                                playlists = syncedPlaylists,
                                userPlaylists = syncedUserPlaylists,
                                errors = syncedErrors,
                                failedItems = failedItemNames.toList()
                            ) else null
                        )
                    _activeProgress.value = progress
                }

                val isFiltered = !config.playlistIds.isNullOrEmpty() ||
                        !config.userPlaylistIds.isNullOrEmpty() ||
                        !config.likedByUserIds.isNullOrEmpty()

                val requiredSongIds = mutableSetOf<PlatformUUID>()
                val requiredArtistIds = mutableSetOf<PlatformUUID>()
                val requiredAlbumIds = mutableSetOf<PlatformUUID>()
                val requiredImageIds = mutableSetOf<PlatformUUID>()

                if (isFiltered) {
                    logger.info("Applying filters to mirror operation")
                    updateProgress("Analyzing Selection", 0, 0, newStatus = "Identifying required songs and metadata based on selection...")
                    val songFlows = mutableListOf<Flow<Song>>()
                    config.playlistIds?.forEach {
                        logger.info("Adding songs from playlist: $it")
                        songFlows.add(mirrorService.getSongsByPlaylist(it))
                        delay(1.nanoseconds)
                        yield()
                    }
                    config.userPlaylistIds?.forEach {
                        logger.info("Adding songs from user playlist: $it")
                        songFlows.add(
                            mirrorService.getSongsByUserPlaylist(
                                it
                            )
                        )
                        delay(1.nanoseconds)
                        yield()
                    }
                    config.likedByUserIds?.forEach {
                        logger.info("Adding liked songs from user: $it")
                        songFlows.add(mirrorService.getLikedSongs(it))
                        delay(1.nanoseconds)
                        yield()
                    }

                    logger.info("Collecting and deduplicating required songs and metadata from ${songFlows.size} sources...")
                    updateProgress("Analyzing Selection", 0, 0, newStatus = "Collecting songs from ${songFlows.size} remote sources...")
                    var identifyingCount = 0
                    songFlows.asFlow().flattenMerge(concurrency = 4).buffer(128).collect { song ->
                        val isNew = requiredSongIds.add(song.id)
                        if (isNew) {
                            song.artists.forEach { a ->
                                if (requiredArtistIds.add(a.id)) {
                                    a.imageId?.let { requiredImageIds.add(it) }
                                }
                            }
                            song.album?.let { a ->
                                if (requiredAlbumIds.add(a.id)) {
                                    a.coverId?.let { requiredImageIds.add(it) }
                                }
                            }
                            song.coverId?.let { requiredImageIds.add(it) }
                        }
                        
                        identifyingCount++
                        if (identifyingCount == 1 || identifyingCount % 10 == 0) {
                            logger.info("Analyzed $identifyingCount songs... (Current: ${song.title})")
                            updateProgress("Analyzing Selection", identifyingCount, 0, song.title)
                            yield()
                        }
                    }
                    logger.info("Metadata analysis complete. Found ${requiredSongIds.size} unique songs.")
                    delay(1.nanoseconds)
                    yield()

                    if (!config.playlistIds.isNullOrEmpty()) {
                        logger.info("Fetching cover images for selected playlists...")
                        updateProgress("Analyzing Selection", identifyingCount, 0, newStatus = "Identifying playlist cover images...")
                        mirrorService.getPlaylists()
                            .filter { it.id in config.playlistIds!! }
                            .collect { it.imageId?.let { id -> requiredImageIds.add(id) } }
                        delay(1.nanoseconds)
                        yield()
                    }

                    if (!config.userPlaylistIds.isNullOrEmpty()) {
                        logger.info("Fetching cover images for selected user playlists...")
                        updateProgress("Analyzing Selection", identifyingCount, 0, newStatus = "Identifying user playlist cover images...")
                        mirrorService.getUserPlaylists()
                            .filter { it.id in config.userPlaylistIds!! }
                            .collect { it.imageId?.let { id -> requiredImageIds.add(id) } }
                        delay(1.nanoseconds)
                        yield()
                    }

                    val filterLog =
                        "Selective sync active: Identified ${requiredSongIds.size} songs, ${requiredArtistIds.size} artists, and ${requiredAlbumIds.size} albums to synchronize based on your selection."
                    logger.info("Filtered mirror will sync: ${requiredSongIds.size} songs, ${requiredArtistIds.size} artists, ${requiredAlbumIds.size} albums, ${requiredImageIds.size} images")
                    updateProgress("Initializing", 0, 0, newStatus = filterLog)
                } else {
                    updateProgress(
                        "Initializing",
                        0,
                        0,
                        newStatus = "Starting full library synchronization..."
                    )
                }

                logger.info("Starting image synchronization stage...")
                stageStartTime = System.currentTimeMillis()
                val totalImages = if (isFiltered) requiredImageIds.size else remoteStats.imagesCount
                updateProgress(
                    "Mirroring Images",
                    0,
                    totalImages,
                    newStatus = "Mirroring $totalImages images..."
                )
                var imageCount = 0
                val imagesFlow = if (isFiltered) mirrorService.getImageMetadata()
                    .filter { it.id in requiredImageIds } else mirrorService.getImageMetadata()

                imagesFlow.chunked(100).flatMapConcat { images ->
                    flow {
                        val existingImages =
                            imageService.byIds(images.map { it.id }).map { it.id to it.imageHash }
                        images.forEach {
                            val exists = (it.id to it.imageHash) in existingImages
                            emit(it to exists)
                        }
                    }
                }.collect { (remoteImage: Image, exists: Boolean) ->
                    try {
                        if (!exists) {
                            logger.debug(
                                "Downloading image {} (Hash: {})",
                                remoteImage.id,
                                remoteImage.imageHash
                            )
                            val imageData = remoteImageService.getImageData(remoteImage.id, 0)
                            yield()
                            if (imageData != null) {
                                imageService.upsertImage(remoteImage, imageData)
                                syncedImages++
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to mirror image ${remoteImage.id}: ${e.message}", e)
                        progressMutex.withLock {
                            syncedErrors++
                            failedItemNames.add("Image ${remoteImage.imageHash} (${remoteImage.id})")
                        }
                    }
                    imageCount++
                    if (imageCount % 10 == 0) {
                        updateProgress(
                            "Mirroring Images",
                            imageCount,
                            totalImages,
                            "Image ${remoteImage.imageHash}"
                        )
                        delay(1.nanoseconds)
                        yield()
                    }
                }
                logger.info("Image synchronization finished (Processed: $imageCount)")
                updateProgress("Mirroring Images", totalImages, totalImages)

                logger.info("Starting artist synchronization stage...")
                stageStartTime = System.currentTimeMillis()
                val totalArtists =
                    if (isFiltered) requiredArtistIds.size else remoteStats.artistCount
                updateProgress(
                    "Mirroring Artists",
                    0,
                    totalArtists,
                    newStatus = "Mirroring $totalArtists artists..."
                )
                var artistCount = 0
                val artistsFlow = if (isFiltered) mirrorService.getArtists()
                    .filter { it.id in requiredArtistIds } else mirrorService.getArtists()
                artistsFlow.collect { artist: Artist ->
                    try {
                        artistService.upsertArtist(artist)
                        syncedArtists++
                    } catch (e: Exception) {
                        logger.error("Failed to mirror artist ${artist.name}: ${e.message}", e)
                        progressMutex.withLock {
                            syncedErrors++
                            failedItemNames.add("Artist ${artist.name}")
                        }
                    }
                    artistCount++
                    if (artistCount % 10 == 0) {
                        updateProgress(
                            "Mirroring Artists",
                            artistCount,
                            totalArtists,
                            artist.name
                        )
                        delay(1.nanoseconds)
                        yield()
                    }
                }
                logger.info("Artist synchronization finished (Processed: $artistCount)")
                updateProgress(
                    "Mirroring Artists",
                    totalArtists,
                    totalArtists
                )

                logger.info("Starting artist alias synchronization stage...")
                stageStartTime = System.currentTimeMillis()
                updateProgress("Mirroring Artist Aliases", 0, 0)
                var aliasCount = 0
                val artistAliasesFlow = if (isFiltered) mirrorService.getArtistAliases()
                    .filter { it.artistId in requiredArtistIds } else mirrorService.getArtistAliases()
                artistAliasesFlow.collect { alias: ArtistAlias ->
                    try {
                        artistService.upsertArtistAlias(alias)
                    } catch (e: Exception) {
                        logger.error("Failed to mirror artist alias ${alias.name}: ${e.message}", e)
                        progressMutex.withLock {
                            syncedErrors++
                            failedItemNames.add("Artist Alias ${alias.name}")
                        }
                    }
                    aliasCount++
                    if (aliasCount % 50 == 0) {
                        updateProgress(
                            "Mirroring Artist Aliases",
                            aliasCount,
                            0,
                            alias.name
                        )
                        delay(1.nanoseconds)
                        yield()
                    }
                }
                logger.info("Artist alias synchronization finished (Processed: $aliasCount)")

                logger.info("Starting artist split alias synchronization stage...")
                stageStartTime = System.currentTimeMillis()
                updateProgress("Mirroring Artist Split Aliases", 0, 0)
                var splitAliasCount = 0
                val artistSplitAliasesFlow = if (isFiltered) mirrorService.getArtistSplitAliases()
                    .filter { it.artistId in requiredArtistIds } else mirrorService.getArtistSplitAliases()
                artistSplitAliasesFlow.collect { alias: ArtistSplitAlias ->
                    try {
                        artistService.upsertArtistSplitAlias(alias)
                    } catch (e: Exception) {
                        logger.error("Failed to mirror artist split alias ${alias.name}: ${e.message}", e)
                        progressMutex.withLock {
                            syncedErrors++
                            failedItemNames.add("Artist Split Alias ${alias.name}")
                        }
                    }
                    splitAliasCount++
                    if (splitAliasCount % 50 == 0) {
                        updateProgress(
                            "Mirroring Artist Split Aliases",
                            splitAliasCount,
                            0,
                            alias.name
                        )
                        delay(1.nanoseconds)
                        yield()
                    }
                }
                logger.info("Artist split alias synchronization finished (Processed: $splitAliasCount)")

                logger.info("Starting album synchronization stage...")
                stageStartTime = System.currentTimeMillis()
                val totalAlbums = if (isFiltered) requiredAlbumIds.size else remoteStats.albumCount
                updateProgress("Mirroring Albums", 0, totalAlbums)
                var albumCount = 0
                val albumsFlow = if (isFiltered) mirrorService.getAlbums()
                    .filter { it.id in requiredAlbumIds } else mirrorService.getAlbums()
                albumsFlow.collect { album: Album ->
                    try {
                        albumService.upsertAlbum(album)
                        syncedAlbums++
                    } catch (e: Exception) {
                        logger.error("Failed to mirror album ${album.name}: ${e.message}", e)
                        progressMutex.withLock {
                            syncedErrors++
                            failedItemNames.add("Album ${album.name}")
                        }
                    }
                    albumCount++
                    if (albumCount % 10 == 0) {
                        updateProgress(
                            "Mirroring Albums",
                            albumCount,
                            totalAlbums,
                            album.name
                        )
                        delay(1.nanoseconds)
                        yield()
                    }
                }
                logger.info("Album synchronization finished (Processed: $albumCount)")
                updateProgress("Mirroring Albums", totalAlbums, totalAlbums)

                logger.info("Starting song synchronization stage...")
                stageStartTime = System.currentTimeMillis()
                val totalSongs = if (isFiltered) requiredSongIds.size else remoteStats.songCount
                updateProgress(
                    "Mirroring Songs",
                    0,
                    totalSongs,
                    newStatus = "Downloading $totalSongs songs..."
                )
                var songCount = 0
                var totalBytesSynced = 0L

                val songsFlow = if (isFiltered) mirrorService.getSongs()
                    .filter { it.id in requiredSongIds } else mirrorService.getSongs()

                songsFlow.flatMapMerge(concurrency = 16) { song ->
                    flow {
                        val expectedSize = if (config.quality == -1) song.fileSize
                        else remoteSongService.getDownloadSize(song.id, config.quality)
                        emit(song to expectedSize)
                    }
                }.buffer(2048).flatMapMerge(concurrency = 5) { (song, expectedSize) ->
                    flow {
                        val songDisplayName =
                            "${song.artists.firstOrNull()?.name ?: "Unknown"} - ${song.title}"
                        try {
                            val localPathString =
                                resolveLocalPath(song.path, song.id.toString(), config.quality)
                            val localPath = Path(localPathString)

                            logger.debug(
                                "Syncing song: {} (ID: {}, Target: {}, Expected size: {} bytes)",
                                songDisplayName,
                                song.id,
                                localPathString,
                                expectedSize
                            )

                            val localFile = File(localPathString)
                            val isAlreadyComplete =
                                localFile.exists() && expectedSize > 0 && localFile.length() == expectedSize

                            if (isAlreadyComplete) {
                                logger.info(
                                    "Song already exists and is complete, skipping download: {}",
                                    songDisplayName
                                )
                            } else {
                                localPath.parent.toFile().mkdirs()
                                localPath.outputStream().use { output ->
                                    var downloadedInSong = 0L
                                    logger.info("Downloading {}", songDisplayName)
                                    mirrorService.getSongData(song.id, config.quality, 64 * 1024)
                                        .collect { chunk ->
                                            withContext(Dispatchers.IO) {
                                                output.write(chunk)
                                                delay(1.nanoseconds)
                                                yield()
                                            }
                                            downloadedInSong += chunk.size

                                            val needsProgressUpdate = progressMutex.withLock {
                                                totalBytesSynced += chunk.size
                                                downloadedInSong % (256 * 1024) > chunk.size
                                            }

                                            if (needsProgressUpdate) {
                                                val itemProgress = if (expectedSize > 0) {
                                                    downloadedInSong.toFloat() / expectedSize
                                                } else null

                                                delay(1.nanoseconds)
                                                progressMutex.withLock {
                                                    updateProgress(
                                                        "Mirroring Songs",
                                                        songCount,
                                                        totalSongs,
                                                        songDisplayName,
                                                        itemProgress,
                                                        totalBytesSynced
                                                    )
                                                }
                                            }
                                        }
                                }
                                progressMutex.withLock { syncedSongs++ }
                            }

                            songService.upsertSong(song.copy(path = localPathString))

                            progressMutex.withLock {
                                songCount++
                                updateProgress(
                                    "Mirroring Songs",
                                    songCount,
                                    totalSongs,
                                    songDisplayName,
                                    1.0f,
                                    totalBytesSynced
                                )
                            }
                        } catch (e: Exception) {
                            logger.error("Failed to mirror song \"$songDisplayName\": ${e.message}", e)
                            progressMutex.withLock {
                                syncedErrors++
                                failedItemNames.add(songDisplayName)
                                songCount++
                                updateProgress(
                                    "Mirroring Songs",
                                    songCount,
                                    totalSongs,
                                    songDisplayName,
                                    1.0f,
                                    totalBytesSynced
                                )
                            }
                        }
                        delay(1.nanoseconds)
                        yield()
                        emit(Unit)
                    }
                }.collect()
                logger.info("Song synchronization finished (Processed: $songCount, Total bytes: $totalBytesSynced)")
                updateProgress("Mirroring Songs", totalSongs, totalSongs)

                logger.info("Starting playlist synchronization stage...")
                stageStartTime = System.currentTimeMillis()
                val playlistsToSyncFlow = if (isFiltered && !config.playlistIds.isNullOrEmpty()) {
                    mirrorService.getPlaylists().filter { it.id in config.playlistIds!! }
                } else if (isFiltered) {
                    emptyFlow()
                } else {
                    mirrorService.getPlaylists()
                }
                val playlistsToSyncCount =
                    if (isFiltered) config.playlistIds?.size ?: 0 else remoteStats.playlistCount
                updateProgress(
                    "Mirroring Playlists",
                    0,
                    playlistsToSyncCount,
                    newStatus = "Mirroring $playlistsToSyncCount playlists..."
                )
                var playlistCount = 0
                playlistsToSyncFlow.collect { playlist: Playlist ->
                    try {
                        playlistService.upsertPlaylist(playlist)
                        syncedPlaylists++
                    } catch (e: Exception) {
                        logger.error("Failed to mirror playlist ${playlist.name}: ${e.message}", e)
                        progressMutex.withLock {
                            syncedErrors++
                            failedItemNames.add("Playlist ${playlist.name}")
                        }
                    }
                    playlistCount++
                    updateProgress(
                        "Mirroring Playlists",
                        playlistCount,
                        playlistsToSyncCount,
                        playlist.name
                    )
                    delay(1.nanoseconds)
                    yield()
                }
                logger.info("Playlist synchronization finished (Processed: $playlistCount)")

                logger.info("Starting user playlist synchronization stage...")
                stageStartTime = System.currentTimeMillis()
                val userPlaylistsToSyncFlow =
                    if (isFiltered && !config.userPlaylistIds.isNullOrEmpty()) {
                        mirrorService.getUserPlaylists()
                            .filter { it.id in config.userPlaylistIds!! }
                    } else if (isFiltered) {
                        emptyFlow()
                    } else {
                        mirrorService.getUserPlaylists()
                    }
                val userPlaylistsToSyncCount =
                    if (isFiltered) config.userPlaylistIds?.size ?: 0 else 0
                updateProgress(
                    "Mirroring User Playlists",
                    0,
                    userPlaylistsToSyncCount,
                    newStatus = "Mirroring $userPlaylistsToSyncCount user playlists..."
                )
                var userPlaylistCount = 0
                userPlaylistsToSyncFlow.collect { playlist: UserPlaylist ->
                    try {
                        userPlaylistService.upsertUserPlaylist(playlist, config.targetUserId)
                        syncedUserPlaylists++
                    } catch (e: Exception) {
                        logger.error("Failed to mirror user playlist ${playlist.name}: ${e.message}", e)
                        progressMutex.withLock {
                            syncedErrors++
                            failedItemNames.add("User Playlist ${playlist.name}")
                        }
                    }
                    userPlaylistCount++
                    updateProgress(
                        "Mirroring User Playlists",
                        userPlaylistCount,
                        userPlaylistsToSyncCount,
                        playlist.name
                    )
                    delay(1.nanoseconds)
                    yield()
                }
                logger.info("User playlist synchronization finished (Processed: $userPlaylistCount)")

                if (config.targetUserId != null && !config.likedByUserIds.isNullOrEmpty()) {
                    logger.info("Starting user preference synchronization stage...")
                    updateProgress(
                        "Syncing User Preferences",
                        0,
                        config.likedByUserIds!!.size,
                        newStatus = "Fetching remote user information..."
                    )

                    val remoteUserMap = try {
                        mirrorService.getUsers().toList().associate { it.id to (it.displayName ?: it.username) }
                    } catch (e: Exception) {
                        logger.warn("Failed to fetch remote user list for display names: ${e.message}")
                        emptyMap()
                    }

                    config.likedByUserIds!!.forEachIndexed { index, userId ->
                        val remoteUserName = remoteUserMap[userId] ?: userId.toString()
                        logger.info("Syncing liked songs for remote user: $remoteUserName")
                        
                        updateProgress(
                            "Syncing User Preferences",
                            index,
                            config.likedByUserIds!!.size,
                            newStatus = "Mapping remote liked songs for $remoteUserName...",
                            item = "User: $remoteUserName"
                        )

                        var likedCount = 0
                        mirrorService.getLikedSongs(userId).collect { song ->
                            songService.setLiked(song.id, config.targetUserId!!, true)
                            likedCount++
                            if (likedCount % 50 == 0) {
                                updateProgress(
                                    "Syncing User Preferences",
                                    index,
                                    config.likedByUserIds!!.size,
                                    item = "User: $remoteUserName ($likedCount likes...)"
                                )
                                yield()
                            }
                        }
                        logger.info("Successfully synced $likedCount liked songs for $remoteUserName")
                        
                        updateProgress(
                            "Syncing User Preferences",
                            index + 1,
                            config.likedByUserIds!!.size,
                            item = "Completed: $remoteUserName ($likedCount likes)"
                        )
                        delay(1.nanoseconds)
                        yield()
                    }
                }

                val finalStatus = if (syncedErrors > 0) {
                    "Mirror operation from ${config.host} finished with $syncedErrors errors."
                } else {
                    "Mirror operation from ${config.host} successfully completed."
                }
                
                val finalError = if (syncedErrors > 0) {
                    "Completed with $syncedErrors errors. Failed items: ${failedItemNames.take(3).joinToString(", ")}${if (failedItemNames.size > 3) "..." else ""}"
                } else null

                updateProgress(
                    "Mirror complete",
                    totalSongs,
                    totalSongs,
                    isFinished = true,
                    newStatus = finalStatus,
                    error = finalError
                )
                logger.info(finalStatus)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T> withRemoteClient(block: suspend (HttpClient) -> T): T {
        return block(httpClient)
    }
}
