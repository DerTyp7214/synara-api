package dev.dertyp.services

import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.rpc.krpc.ktor.client.Krpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.rpc.withService
import kotlinx.serialization.ExperimentalSerializationApi
import org.koin.core.component.inject
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.outputStream

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
        if (isMirroring) return

        mirrorJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                isMirroring = true
                performMirror(config)
            } catch (_: CancellationException) {
                _activeProgress.value = MirrorProgress("Mirror stopped by user", 0, 0, true, "Stopped")
            } catch (e: Exception) {
                logger.error("Mirror failed: ${e.message}", e)
                _activeProgress.value = MirrorProgress("Error during mirror", 0, 0, true, e.message)
            } finally {
                isMirroring = false
                mirrorJob = null
            }
        }
    }

    fun stopMirror() {
        mirrorJob?.cancel()
    }

    fun resetMirror() {
        if (isMirroring) throw IllegalStateException("Cannot reset while mirroring is in progress")
        _activeProgress.value = null
    }

    fun getActiveMirrorProgress(): Flow<MirrorProgress>? {
        if (!isMirroring && (_activeProgress.value == null || _activeProgress.value?.isFinished == false)) return null
        return _activeProgress.filterNotNull()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun performMirror(config: RemoteServerConfig) {
        withRemoteClient { client ->
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

                val remoteStats = statsService.getStats()

                fun updateProgress(
                    task: String,
                    processed: Int,
                    total: Int,
                    item: String? = null,
                    itemProgress: Float? = null
                ) {
                    val progress =
                        MirrorProgress(task, processed, total, false, null, item, itemProgress)
                    _activeProgress.value = progress
                }

                updateProgress("Mirroring Images", 0, remoteStats.imagesCount)
                var imageCount = 0
                mirrorService.getImageMetadata().collect { remoteImage: Image ->
                    val localImage = imageService.byId(remoteImage.id)
                    if (localImage == null || localImage.imageHash != remoteImage.imageHash) {
                        val imageData = remoteImageService.getImageData(remoteImage.id, 0)
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
                updateProgress("Mirroring Images", remoteStats.imagesCount, remoteStats.imagesCount)

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
                updateProgress(
                    "Mirroring Artists",
                    remoteStats.artistCount,
                    remoteStats.artistCount
                )

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
                updateProgress("Mirroring Albums", remoteStats.albumCount, remoteStats.albumCount)

                updateProgress("Mirroring Songs", 0, remoteStats.songCount)
                var songCount = 0
                mirrorService.getSongs().collect { song: Song ->
                    val extension = if (config.quality == -1) song.path.substringAfterLast(
                        '.',
                        "flac"
                    ) else "ogg"
                    val localPath = Path(storageService.tracksPath!!, "${song.id}.$extension")
                    val songDisplayName =
                        "${song.artists.firstOrNull()?.name ?: "Unknown"} - ${song.title}"

                    val expectedSize = if (config.quality == -1) song.fileSize
                    else remoteSongService.getDownloadSize(song.id, config.quality)

                    localPath.parent.toFile().mkdirs()
                    localPath.outputStream().use { output ->
                        var downloaded = 0L
                        mirrorService.getSongData(song.id, config.quality).collect { chunk ->
                            withContext(Dispatchers.IO) {
                                output.write(chunk)
                            }
                            downloaded += chunk.size

                            val itemProgress = if (expectedSize > 0) {
                                downloaded.toFloat() / expectedSize
                            } else null

                            updateProgress(
                                "Mirroring Songs",
                                songCount,
                                remoteStats.songCount,
                                songDisplayName,
                                itemProgress
                            )
                        }
                    }

                    songService.upsertSong(song.copy(path = localPath.absolutePathString()))

                    songCount++
                    updateProgress(
                        "Mirroring Songs",
                        songCount,
                        remoteStats.songCount,
                        songDisplayName,
                        1.0f
                    )
                }
                updateProgress("Mirroring Songs", remoteStats.songCount, remoteStats.songCount)

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

                updateProgress("Mirroring User Playlists", 0, 0)
                var userPlaylistCount = 0
                mirrorService.getUserPlaylists().collect { playlist: UserPlaylist ->
                    userPlaylistService.upsertUserPlaylist(playlist)
                    userPlaylistCount++
                    updateProgress("Mirroring User Playlists", userPlaylistCount, 0, playlist.name)
                }

                _activeProgress.value = MirrorProgress(
                    "Mirror complete",
                    remoteStats.songCount,
                    remoteStats.songCount,
                    true
                )

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
