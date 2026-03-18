package dev.dertyp.services

import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    suspend fun getRemoteStats(config: RemoteServerConfig): ServerStats {
        return withRemoteClient(config) { client ->
            val statsService = client.rpc("/rpc").withService<IServerStatsService>()
            statsService.getStats()
        }
    }

    fun startMirror(config: RemoteServerConfig) {
        if (isMirroring) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                isMirroring = true
                performMirror(config)
            } catch (e: Exception) {
                logger.error("Mirror failed: ${e.message}", e)
                _activeProgress.value = MirrorProgress("Error during mirror", 0, 0, true, e.message)
            } finally {
                isMirroring = false
                _activeProgress.value = null
            }
        }
    }

    fun getActiveMirrorProgress(): Flow<MirrorProgress>? {
        if (!isMirroring) return null
        return _activeProgress.filterNotNull()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun performMirror(config: RemoteServerConfig) {
        withRemoteClient(config) { client ->
            val authService = client.rpc("/rpc/auth").withService<IAuthService>()

            val authResponse = authService.authenticate(config.username, config.password)
            val token = authResponse.token

            val authenticatedClient = HttpClient(CIO) {
                install(WebSockets)
                install(Krpc) {
                    serialization {
                        cbor(ApplicationScope.cbor)
                    }
                }
                defaultRequest {
                    host = config.host
                    port = config.port
                    url {
                        protocol = if (config.secure) URLProtocol.WSS else URLProtocol.WS
                    }
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            }

            val mirrorService =
                authenticatedClient.rpc("/rpc/services").withService<IMirrorService>()
            val statsService = authenticatedClient.rpc("/rpc").withService<IServerStatsService>()

            val remoteStats = statsService.getStats()

            fun updateProgress(task: String, processed: Int, total: Int) {
                val progress = MirrorProgress(task, processed, total)
                _activeProgress.value = progress
            }

            updateProgress("Mirroring Images", 0, remoteStats.imagesCount)
            var imageCount = 0
            mirrorService.getImages().collect { item: ImageStreamItem ->
                imageService.createBatch(
                    listOf(
                        InsertableImage(
                            item.data,
                            item.id.toString(),
                            "Mirror"
                        )
                    )
                )
                imageCount++
                if (imageCount % 10 == 0) updateProgress(
                    "Mirroring Images",
                    imageCount,
                    remoteStats.imagesCount
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
                    remoteStats.artistCount
                )
            }
            updateProgress("Mirroring Artists", remoteStats.artistCount, remoteStats.artistCount)

            updateProgress("Mirroring Artist Aliases", 0, 0)
            mirrorService.getArtistAliases().collect { alias: ArtistAlias ->
                artistService.upsertArtistAlias(alias)
            }

            updateProgress("Mirroring Artist Split Aliases", 0, 0)
            mirrorService.getArtistSplitAliases().collect { alias: ArtistSplitAlias ->
                artistService.upsertArtistSplitAlias(alias)
            }

            updateProgress("Mirroring Albums", 0, remoteStats.albumCount)
            var albumCount = 0
            mirrorService.getAlbums().collect { album: Album ->
                albumService.upsertAlbum(album)
                albumCount++
                if (albumCount % 10 == 0) updateProgress(
                    "Mirroring Albums",
                    albumCount,
                    remoteStats.albumCount
                )
            }
            updateProgress("Mirroring Albums", remoteStats.albumCount, remoteStats.albumCount)

            updateProgress("Mirroring Songs", 0, remoteStats.songCount)
            var songCount = 0
            mirrorService.getSongs().collect { song: Song ->
                val extension = if (config.quality == -1) song.path.substringAfterLast('.', "flac") else "ogg"
                val localPath = Path(storageService.tracksPath!!, "${song.id}.$extension")
                localPath.parent.toFile().mkdirs()

                localPath.outputStream().use { output ->
                    mirrorService.getSongData(song.id, config.quality).collect { chunk ->
                        withContext(Dispatchers.IO) {
                            output.write(chunk)
                        }
                    }
                }

                songService.upsertSong(song.copy(path = localPath.absolutePathString()))

                songCount++
                if (songCount % 5 == 0) updateProgress(
                    "Mirroring Songs",
                    songCount,
                    remoteStats.songCount
                )
            }
            updateProgress("Mirroring Songs", remoteStats.songCount, remoteStats.songCount)

            updateProgress("Mirroring Playlists", 0, remoteStats.playlistCount)
            var playlistCount = 0
            mirrorService.getPlaylists().collect { playlist: Playlist ->
                playlistService.upsertPlaylist(playlist)
                playlistCount++
                updateProgress("Mirroring Playlists", playlistCount, remoteStats.playlistCount)
            }

            updateProgress("Mirroring User Playlists", 0, 0)
            mirrorService.getUserPlaylists().collect { playlist: UserPlaylist ->
                userPlaylistService.upsertUserPlaylist(playlist)
            }

            _activeProgress.value = MirrorProgress(
                "Mirror complete",
                remoteStats.songCount,
                remoteStats.songCount,
                true
            )
            authenticatedClient.close()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T> withRemoteClient(
        config: RemoteServerConfig,
        block: suspend (HttpClient) -> T
    ): T {
        val client = HttpClient(CIO) {
            install(WebSockets)
            install(Krpc) {
                serialization {
                    cbor(ApplicationScope.cbor)
                }
            }
            defaultRequest {
                host = config.host
                port = config.port
                url {
                    protocol = if (config.secure) URLProtocol.WSS else URLProtocol.WS
                }
            }
        }
        return client.use {
            block(it)
        }
    }
}
