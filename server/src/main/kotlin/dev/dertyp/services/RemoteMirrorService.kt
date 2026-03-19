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
        return withRemoteClient { client ->
            val statsService =
                client.rpc(config.toFullUrl("/rpc")).withService<IServerStatsService>()
            statsService.getStats()
        }
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
        withAuthenticatedMirrorService(config) { it.getUsers().toList() }

    suspend fun getRemotePlaylists(config: RemoteServerConfig): List<Playlist> =
        withAuthenticatedMirrorService(config) { it.getPlaylists().toList() }

    suspend fun getRemoteUserPlaylists(config: RemoteServerConfig): List<UserPlaylist> =
        withAuthenticatedMirrorService(config) { it.getUserPlaylists().toList() }

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
    private suspend fun <T> withAuthenticatedMirrorService(
        config: RemoteServerConfig,
        block: suspend (IMirrorService) -> T
    ): T {
        return withRemoteClient { client ->
            val authService = client.rpc(config.toFullUrl("/rpc/auth")).withService<IAuthService>()
            val authResponse = authService.authenticate(config.username, config.password)
            val token = authResponse.token

            val authenticatedClient = HttpClient(CIO) {
                install(WebSockets)
                install(Krpc) {
                    serialization {
                        cbor(ApplicationScope.cbor)
                    }
                }
            }

            authenticatedClient.use { authClient ->
                val mirrorService = authClient.rpc(config.toFullUrl("/rpc/services")) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }.withService<IMirrorService>()
                block(mirrorService)
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class, ExperimentalCoroutinesApi::class)
    private suspend fun performMirror(config: RemoteServerConfig) {
        withRemoteClient { client ->
            logger.info("Authenticating with remote server at ${config.toFullUrl("/rpc/auth")}")
            val authService = client.rpc(config.toFullUrl("/rpc/auth")).withService<IAuthService>()

            val authResponse = authService.authenticate(config.username, config.password)
            val token = authResponse.token
            logger.info("Successfully authenticated as ${config.username}")

            val authenticatedClient = HttpClient(CIO) {
                install(WebSockets)
                install(Krpc) {
                    serialization {
                        cbor(ApplicationScope.cbor)
                    }
                }
            }

            authenticatedClient.use { authClient ->
                val mirrorService =
                    authClient.rpc(config.toFullUrl("/rpc/services")) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.withService<IMirrorService>()

                val remoteImageService =
                    authClient.rpc(config.toFullUrl("/rpc/services")) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.withService<IImageService>()

                val remoteSongService =
                    authClient.rpc(config.toFullUrl("/rpc/services")) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.withService<ISongService>()

                val statsService = authClient.rpc(config.toFullUrl("/rpc")) {
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

                fun updateProgress(
                    task: String,
                    processed: Int,
                    total: Int,
                    item: String? = null,
                    itemProgress: Float? = null,
                    byteCount: Long? = null,
                    newStatus: String? = null,
                    isFinished: Boolean = false
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
                            null,
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
                                userPlaylists = syncedUserPlaylists
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
                    val songFlows = mutableListOf<Flow<Song>>()
                    config.playlistIds?.forEach { songFlows.add(mirrorService.getSongsByPlaylist(it)) }
                    config.userPlaylistIds?.forEach {
                        songFlows.add(
                            mirrorService.getSongsByUserPlaylist(
                                it
                            )
                        )
                    }
                    config.likedByUserIds?.forEach { songFlows.add(mirrorService.getLikedSongs(it)) }

                    songFlows.merge().collect { song ->
                        if (requiredSongIds.add(song.id)) {
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
                    }

                    if (!config.playlistIds.isNullOrEmpty()) {
                        mirrorService.getPlaylists()
                            .filter { it.id in config.playlistIds!! }
                            .collect { it.imageId?.let { id -> requiredImageIds.add(id) } }
                    }

                    if (!config.userPlaylistIds.isNullOrEmpty()) {
                        mirrorService.getUserPlaylists()
                            .filter { it.id in config.userPlaylistIds!! }
                            .collect { it.imageId?.let { id -> requiredImageIds.add(id) } }
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
                    artistService.upsertArtist(artist)
                    artistCount++
                    syncedArtists++
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
                    artistService.upsertArtistAlias(alias)
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
                    artistService.upsertArtistSplitAlias(alias)
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
                    albumService.upsertAlbum(album)
                    albumCount++
                    syncedAlbums++
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
                }.buffer(1024).collect { (song, expectedSize) ->
                    val localPathString =
                        resolveLocalPath(song.path, song.id.toString(), config.quality)
                    val localPath = Path(localPathString)
                    val songDisplayName =
                        "${song.artists.firstOrNull()?.name ?: "Unknown"} - ${song.title}"

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
                                    totalBytesSynced += chunk.size

                                    if (downloadedInSong % (256 * 1024) > chunk.size) {
                                        val itemProgress = if (expectedSize > 0) {
                                            downloadedInSong.toFloat() / expectedSize
                                        } else null

                                        delay(1.nanoseconds)
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
                        syncedSongs++
                    }

                    songService.upsertSong(song.copy(path = localPathString))

                    songCount++
                    updateProgress(
                        "Mirroring Songs",
                        songCount,
                        totalSongs,
                        songDisplayName,
                        1.0f,
                        totalBytesSynced
                    )
                    delay(1.nanoseconds)
                    yield()
                }
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
                    playlistService.upsertPlaylist(playlist)
                    playlistCount++
                    syncedPlaylists++
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
                    userPlaylistService.upsertUserPlaylist(playlist, config.targetUserId)
                    userPlaylistCount++
                    syncedUserPlaylists++
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
                        newStatus = "Mapping remote liked songs to local user..."
                    )
                    config.likedByUserIds!!.forEachIndexed { index, userId ->
                        mirrorService.getLikedSongs(userId).collect { song ->
                            songService.setLiked(song.id, config.targetUserId!!, true)
                        }
                        updateProgress(
                            "Syncing User Preferences",
                            index + 1,
                            config.likedByUserIds!!.size
                        )
                        delay(1.nanoseconds)
                        yield()
                    }
                }

                updateProgress(
                    "Mirror complete",
                    totalSongs,
                    totalSongs,
                    isFinished = true,
                    newStatus = "Mirror operation from ${config.host} successfully completed."
                )
                logger.info("Mirror operation from ${config.host} successfully completed.")
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T> withRemoteClient(block: suspend (HttpClient) -> T): T {
        val client = HttpClient(CIO) {
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
        return client.use {
            block(it)
        }
    }
}
