package dev.dertyp.services

import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
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
        return "$protocol://$cleanHost:$port/${path.removePrefix("/")}"
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
                _activeProgress.value = MirrorProgress("Mirror stopped by user", 0, 0, true, "Stopped")
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
                    val extension = if (quality == -1) remotePath.substringAfterLast('.', "flac") else "ogg"

                    fun String.fixExtension(): String {
                        if (quality == -1) return this
                        return if (this.contains('.')) {
                            this.substringBeforeLast('.') + ".ogg"
                        } else {
                            "$this.ogg"
                        }
                    }

                    if (remotePaths.customAudioPath != null && remotePath.startsWith(remotePaths.customAudioPath!!)) {
                        val relative = remotePath.removePrefix(remotePaths.customAudioPath!!).trimStart('/', '\\')
                        return Path(storageService.customAudioPath, relative.fixExtension()).absolutePathString()
                    }

                    if (remotePaths.tracksPath != null && remotePath.startsWith(remotePaths.tracksPath!!)) {
                        val relative = remotePath.removePrefix(remotePaths.tracksPath!!).trimStart('/', '\\')
                        return Path(storageService.tracksPath!!, relative.fixExtension()).absolutePathString()
                    }

                    for (secondaryRemote in remotePaths.secondaryTracksPaths) {
                        if (remotePath.startsWith(secondaryRemote)) {
                            val relative = remotePath.removePrefix(secondaryRemote).trimStart('/', '\\')
                            return Path(storageService.tracksPath!!, relative.fixExtension()).absolutePathString()
                        }
                    }

                    return Path(storageService.tracksPath!!, "$id.$extension").absolutePathString()
                }

                var stageStartTime = System.currentTimeMillis()

                fun updateProgress(
                    task: String,
                    processed: Int,
                    total: Int,
                    item: String? = null,
                    itemProgress: Float? = null,
                    byteCount: Long? = null
                ) {
                    val now = System.currentTimeMillis()
                    val elapsed = (now - stageStartTime) / 1000.0

                    val speedStr = if (elapsed > 0.1) {
                        if (byteCount != null) {
                            "${formatBytes((byteCount / elapsed).toLong())}/s"
                        } else {
                            "%.1f items/s".format(processed / elapsed)
                        }
                    } else null

                    val etaStr = if (elapsed > 1.0 && processed > 0 && total > processed) {
                        val itemsProgress = (processed.toDouble() + (itemProgress ?: 0f)) / total
                        val remainingSeconds = if (itemsProgress > 0) (elapsed / itemsProgress) - elapsed else null
                        if (remainingSeconds != null && remainingSeconds > 0) {
                            formatDuration(remainingSeconds.toLong())
                        } else null
                    } else null

                    val progress =
                        MirrorProgress(task, processed, total, false, null, item, itemProgress, speedStr, etaStr)
                    _activeProgress.value = progress
                }

                logger.info("Starting image synchronization stage...")
                stageStartTime = System.currentTimeMillis()
                updateProgress("Mirroring Images", 0, remoteStats.imagesCount)
                var imageCount = 0
                mirrorService.getImageMetadata().collect { remoteImage: Image ->
                    val localImage = imageService.byId(remoteImage.id)
                    if (localImage == null || localImage.imageHash != remoteImage.imageHash) {
                        logger.debug(
                            "Downloading image {} (Hash: {})",
                            remoteImage.id,
                            remoteImage.imageHash
                        )
                        val imageData = remoteImageService.getImageData(remoteImage.id, 0)
                        yield()
                        if (imageData != null) {
                            imageService.createBatch(
                                listOf(
                                    InsertableImage(
                                        imageData,
                                        remoteImage.id.toString(),
                                        "Mirror"
                                    )
                                )
                            )
                        }
                    }
                    imageCount++
                    if (imageCount % 10 == 0) updateProgress(
                        "Mirroring Images",
                        imageCount,
                        remoteStats.imagesCount,
                        "Image ${remoteImage.imageHash}"
                    )
                }
                logger.info("Image synchronization finished (Processed: $imageCount)")
                updateProgress("Mirroring Images", remoteStats.imagesCount, remoteStats.imagesCount)

                logger.info("Starting artist synchronization stage...")
                stageStartTime = System.currentTimeMillis()
                updateProgress("Mirroring Artists", 0, remoteStats.artistCount)
                var artistCount = 0
                mirrorService.getArtists().collect { artist: Artist ->
                    artistService.upsertArtist(artist)
                    artistCount++
                    if (artistCount % 10 == 0) updateProgress(
                        "Mirroring Artists",
                        artistCount,
                        remoteStats.artistCount,
                        artist.name
                    )
                }
                logger.info("Artist synchronization finished (Processed: $artistCount)")
                updateProgress(
                    "Mirroring Artists",
                    remoteStats.artistCount,
                    remoteStats.artistCount
                )

                logger.info("Starting artist alias synchronization stage...")
                stageStartTime = System.currentTimeMillis()
                updateProgress("Mirroring Artist Aliases", 0, 0)
                var aliasCount = 0
                mirrorService.getArtistAliases().collect { alias: ArtistAlias ->
                    artistService.upsertArtistAlias(alias)
                    aliasCount++
                    if (aliasCount % 50 == 0) updateProgress(
                        "Mirroring Artist Aliases",
                        aliasCount,
                        0,
                        alias.name
                    )
                }
                logger.info("Artist alias synchronization finished (Processed: $aliasCount)")

                logger.info("Starting artist split alias synchronization stage...")
                stageStartTime = System.currentTimeMillis()
                updateProgress("Mirroring Artist Split Aliases", 0, 0)
                var splitAliasCount = 0
                mirrorService.getArtistSplitAliases().collect { alias: ArtistSplitAlias ->
                    artistService.upsertArtistSplitAlias(alias)
                    splitAliasCount++
                    if (splitAliasCount % 50 == 0) updateProgress(
                        "Mirroring Artist Split Aliases",
                        splitAliasCount,
                        0,
                        alias.name
                    )
                }
                logger.info("Artist split alias synchronization finished (Processed: $splitAliasCount)")

                logger.info("Starting album synchronization stage...")
                stageStartTime = System.currentTimeMillis()
                updateProgress("Mirroring Albums", 0, remoteStats.albumCount)
                var albumCount = 0
                mirrorService.getAlbums().collect { album: Album ->
                    albumService.upsertAlbum(album)
                    albumCount++
                    if (albumCount % 10 == 0) updateProgress(
                        "Mirroring Albums",
                        albumCount,
                        remoteStats.albumCount,
                        album.name
                    )
                }
                logger.info("Album synchronization finished (Processed: $albumCount)")
                updateProgress("Mirroring Albums", remoteStats.albumCount, remoteStats.albumCount)

                logger.info("Starting song synchronization stage...")
                stageStartTime = System.currentTimeMillis()
                updateProgress("Mirroring Songs", 0, remoteStats.songCount)
                var songCount = 0
                var totalBytesSynced = 0L

                mirrorService.getSongs().flatMapMerge(concurrency = 6) { song ->
                    flow {
                        val expectedSize = if (config.quality == -1) song.fileSize
                        else remoteSongService.getDownloadSize(song.id, config.quality)
                        emit(song to expectedSize)
                    }
                }.collect { (song, expectedSize) ->
                    val localPathString = resolveLocalPath(song.path, song.id.toString(), config.quality)
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
                    val isAlreadyComplete = localFile.exists() && expectedSize > 0 && localFile.length() == expectedSize

                    if (isAlreadyComplete) {
                        logger.info("Song already exists and is complete, skipping download: {}", songDisplayName)
                    } else {
                        localPath.parent.toFile().mkdirs()
                        localPath.outputStream().use { output ->
                            var downloadedInSong = 0L
                            logger.info("Downloading {}", songDisplayName)
                            mirrorService.getSongData(song.id, config.quality, 64 * 1024).collect { chunk ->
                                withContext(Dispatchers.IO) {
                                    output.write(chunk)
                                    if (downloadedInSong % (16 * 1024) > chunk.size) {
                                        delay(1.nanoseconds)
                                    }
                                    yield()
                                }
                                downloadedInSong += chunk.size
                                totalBytesSynced += chunk.size

                                if (downloadedInSong % (96 * 1024) > chunk.size) {
                                    val itemProgress = if (expectedSize > 0) {
                                        downloadedInSong.toFloat() / expectedSize
                                    } else null

                                    delay(1.nanoseconds)
                                    updateProgress(
                                        "Mirroring Songs",
                                        songCount,
                                        remoteStats.songCount,
                                        songDisplayName,
                                        itemProgress,
                                        totalBytesSynced
                                    )
                                    yield()
                                }
                            }
                        }
                    }

                    songService.upsertSong(song.copy(path = localPathString))

                    songCount++
                    updateProgress(
                        "Mirroring Songs",
                        songCount,
                        remoteStats.songCount,
                        songDisplayName,
                        1.0f,
                        totalBytesSynced
                    )
                    yield()
                }
                logger.info("Song synchronization finished (Processed: $songCount, Total bytes: $totalBytesSynced)")
                updateProgress("Mirroring Songs", remoteStats.songCount, remoteStats.songCount)

                logger.info("Starting playlist synchronization stage...")
                stageStartTime = System.currentTimeMillis()
                updateProgress("Mirroring Playlists", 0, remoteStats.playlistCount)
                var playlistCount = 0
                mirrorService.getPlaylists().collect { playlist: Playlist ->
                    playlistService.upsertPlaylist(playlist)
                    playlistCount++
                    updateProgress(
                        "Mirroring Playlists",
                        playlistCount,
                        remoteStats.playlistCount,
                        playlist.name
                    )
                }
                logger.info("Playlist synchronization finished (Processed: $playlistCount)")

                logger.info("Starting user playlist synchronization stage...")
                stageStartTime = System.currentTimeMillis()
                updateProgress("Mirroring User Playlists", 0, 0)
                var userPlaylistCount = 0
                mirrorService.getUserPlaylists().collect { playlist: UserPlaylist ->
                    userPlaylistService.upsertUserPlaylist(playlist)
                    userPlaylistCount++
                    updateProgress("Mirroring User Playlists", userPlaylistCount, 0, playlist.name)
                }
                logger.info("User playlist synchronization finished (Processed: $userPlaylistCount)")

                _activeProgress.value = MirrorProgress(
                    "Mirror complete",
                    remoteStats.songCount,
                    remoteStats.songCount,
                    true
                )
                logger.info("Mirror operation from ${config.host} successfully completed.")
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T> withRemoteClient(block: suspend (HttpClient) -> T): T {
        val client = HttpClient(CIO) {
            install(WebSockets)
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
