package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.*
import dev.dertyp.randomPlatformUUID
import dev.dertyp.rpc.BaseRpcServiceManager
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.rpc.krpc.ktor.client.Krpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.serialization.ExperimentalSerializationApi
import org.koin.core.component.inject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.outputStream
import kotlin.time.Duration.Companion.seconds

class RemoteMirrorRpcService(
    private val user: User,
    private val remoteMirrorService: RemoteMirrorService
) : IRemoteMirrorService {
    private fun ensureAdmin() {
        if (!user.isAdmin) throw IllegalStateException("Only admins can perform mirror operations")
    }

    override suspend fun getRemoteStats(config: RemoteServerConfig): ServerStats {
        return remoteMirrorService.getRemoteStats(config)
    }

    override suspend fun startMirror(config: RemoteServerConfig) {
        ensureAdmin()
        remoteMirrorService.startMirror(config)
    }

    override suspend fun stopMirror() {
        ensureAdmin()
        remoteMirrorService.stopMirror()
    }

    override suspend fun resetMirror() {
        ensureAdmin()
        remoteMirrorService.resetMirror()
    }

    override fun getActiveMirrorProgress(): Flow<MirrorProgress> {
        ensureAdmin()
        return remoteMirrorService.getActiveMirrorProgress()
    }

    override suspend fun getRemoteUsers(config: RemoteServerConfig): List<User> {
        ensureAdmin()
        return remoteMirrorService.getRemoteUsers(config)
    }

    override suspend fun getRemotePlaylists(config: RemoteServerConfig): List<Playlist> {
        ensureAdmin()
        return remoteMirrorService.getRemotePlaylists(config)
    }

    override suspend fun getRemoteUserPlaylists(config: RemoteServerConfig): List<UserPlaylist> {
        ensureAdmin()
        return remoteMirrorService.getRemoteUserPlaylists(config)
    }

    override suspend fun getProxyInstances(config: RemoteServerConfig): List<ProxyInstanceInfo> {
        ensureAdmin()
        return remoteMirrorService.getProxyInstances(config)
    }

    override suspend fun getRemoteImageData(
        config: RemoteServerConfig,
        imageId: PlatformUUID,
        size: Int
    ): ByteArray? {
        return remoteMirrorService.getRemoteImageData(config, imageId, size)
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
    private val userService by inject<UserService>()

    @OptIn(ExperimentalSerializationApi::class)
    private val httpClient = HttpClient(CIO) {
        install(UserAgent) {
            agent = "Synara/Mirror"
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60000
            connectTimeoutMillis = 20000
            socketTimeoutMillis = 60000
        }
        install(WebSockets) {
            pingInterval = 15.seconds
            maxFrameSize = Long.MAX_VALUE
        }
        install(ContentNegotiation) {
            json(ApplicationScope.json)
        }
        install(Krpc) {
            serialization {
                cbor(ApplicationScope.cbor)
            }
        }
    }

    private val managers = ConcurrentHashMap<String, RemoteMirrorRpcManager>()

    private fun getManager(config: RemoteServerConfig): RemoteMirrorRpcManager {
        val key = "${config.host}:${config.port}:${config.username}:${config.useProxy}:${config.proxyInstanceId}"
        return managers.getOrPut(key) { RemoteMirrorRpcManager(config) }
    }

    private suspend fun getAuthenticatedManager(config: RemoteServerConfig): RemoteMirrorRpcManager {
        val manager = getManager(config)
        manager.ensureAuthenticated()
        return manager
    }

    private val _activeProgress = MutableStateFlow<MirrorProgress?>(null)
    var isMirroring = false
    private var mirrorJob: Job? = null

    suspend fun getRemoteStats(config: RemoteServerConfig): ServerStats {
        return getManager(config).getServerStatsService().getStats()
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
        mirrorJob?.cancel()
        if (mirrorJob != null) logger.info("Stopping active mirror job...")
    }

    fun resetMirror() {
        if (isMirroring) throw IllegalStateException("Cannot reset while mirroring is in progress")
        logger.info("Resetting remote mirror state")
        _activeProgress.value = null
    }

    fun getActiveMirrorProgress(): Flow<MirrorProgress> {
        if (!isMirroring && (_activeProgress.value == null || _activeProgress.value?.isFinished == false)) return emptyFlow()
        return _activeProgress.filterNotNull()
    }

    suspend fun getRemoteUsers(config: RemoteServerConfig): List<User> =
        getAuthenticatedManager(config).getService<IMirrorService>().getUsers().toList()

    suspend fun getRemotePlaylists(config: RemoteServerConfig): List<Playlist> =
        getAuthenticatedManager(config).getService<IMirrorService>().getPlaylists().toList()

    suspend fun getRemoteUserPlaylists(config: RemoteServerConfig): List<UserPlaylist> =
        getAuthenticatedManager(config).getService<IMirrorService>().getUserPlaylists().toList()

    @Suppress("HttpUrlsUsage")
    suspend fun getProxyInstances(config: RemoteServerConfig): List<ProxyInstanceInfo> {
        val protocol = if (config.secure) "https" else "http"
        val cleanHost = config.host.removePrefix("http://").removePrefix("https://").removeSuffix("/")
        val url = "$protocol://$cleanHost:${config.port}/instances"
        return httpClient.get(url).body<List<ProxyInstanceInfo>>()
    }

    suspend fun getRemoteImageData(config: RemoteServerConfig, imageId: PlatformUUID, size: Int = 0): ByteArray? =
        getAuthenticatedManager(config).getService<IImageService>().getImageData(imageId, size)

    @OptIn(ExperimentalSerializationApi::class, ExperimentalCoroutinesApi::class)
    private suspend fun performMirror(config: RemoteServerConfig) {
        val manager = getAuthenticatedManager(config)
        logger.info("Initializing mirror session with ${config.host}:${config.port} (Quality: ${config.quality}, Import: ${config.isImport})")
        
        val statsService = manager.getServerStatsService()
        logger.info("Fetching remote stats...")
        val remoteStats = statsService.getStats()
        logger.info("Remote library summary: ${remoteStats.songCount} songs, ${remoteStats.albumCount} albums, ${remoteStats.artistCount} artists, ${remoteStats.imagesCount} images")

        val mirrorService = manager.getService<IMirrorService>()
        
        logger.info("Fetching remote server paths...")
        val remotePaths = mirrorService.getServerPaths()
        logger.info("Remote server paths received: $remotePaths")

        val session = MirrorSession(
            config = config,
            mirrorService = mirrorService,
            remoteImageService = manager.getService<IImageService>(),
            remoteSongService = manager.getService<ISongService>(),
            remotePaths = remotePaths
        )
        
        if (session.isFiltered) analyzeSelection(session)
        else session.updateProgress("Initializing", 0, 0, newStatus = "Starting full library synchronization...")

        if (config.importUsers) syncUsers(session)
        syncImages(session, remoteStats)
        syncArtists(session, remoteStats)
        syncArtistAliases(session)
        syncAlbums(session, remoteStats)
        syncSongs(session, remoteStats)
        syncPlaylists(session, remoteStats)
        syncUserPlaylists(session)
        syncUserPreferences(session)

        val finalStatus = if (session.syncedErrors > 0) "Mirror operation finished with ${session.syncedErrors} errors."
        else "Mirror operation successfully completed."
        
        logger.info("Mirror complete! Summary: Songs: ${session.syncedSongs} new / ${session.existingSongs} existing, Albums: ${session.syncedAlbums} new / ${session.existingAlbums} existing, Artists: ${session.syncedArtists} new / ${session.existingArtists} existing, Images: ${session.syncedImages} new / ${session.existingImages} existing, Playlists: ${session.syncedPlaylists} new / ${session.existingPlaylists} existing, User Playlists: ${session.syncedUserPlaylists} new / ${session.existingUserPlaylists} existing, Errors: ${session.syncedErrors}")

        session.updateProgress(
            "Mirror complete",
            if (session.isFiltered) session.requiredSongIds.size else remoteStats.songCount,
            if (session.isFiltered) session.requiredSongIds.size else remoteStats.songCount,
            isFinished = true,
            newStatus = finalStatus,
            error = if (session.syncedErrors > 0) "Completed with ${session.syncedErrors} errors." else null
        )
    }

    // --- Sub-tasks ---

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun analyzeSelection(session: MirrorSession) {
        logger.info("Stage: Analyzing selection for selective sync...")
        session.updateProgress("Analyzing Selection", 0, 0, newStatus = "Identifying required metadata...")

        fun addRequiredArtist(artist: Artist) {
            if (session.requiredArtistIds.add(artist.id)) {
                artist.imageId?.let { session.requiredImageIds.add(it) }
                artist.artists.forEach { addRequiredArtist(it) }
            }
        }

        val songFlows = mutableListOf<Flow<Song>>()
        session.config.playlistIds?.forEach {
            logger.info("Including songs from playlist: $it")
            songFlows.add(session.mirrorService.getSongsByPlaylist(it))
            yield()
        }
        session.config.userPlaylistIds?.forEach {
            logger.info("Including songs from user playlist: $it")
            songFlows.add(session.mirrorService.getSongsByUserPlaylist(it))
            yield()
        }
        session.config.likedByUserIds?.forEach {
            logger.info("Including liked songs from user: $it")
            songFlows.add(session.mirrorService.getLikedSongs(it))
            yield()
        }

        var count = 0
        songFlows.asFlow().flattenMerge(concurrency = 4).buffer(128).collect { song ->
            if (session.requiredSongIds.add(song.id)) {
                song.artists.forEach { addRequiredArtist(it) }
                song.album?.let { a ->
                    if (session.requiredAlbumIds.add(a.id)) {
                        a.artists.forEach { addRequiredArtist(it) }
                        a.coverId?.let { session.requiredImageIds.add(it) }
                    }
                }
                song.coverId?.let { session.requiredImageIds.add(it) }
            }
            count++
            if (count % 100 == 0) logger.info("Analyzed $count songs...")
            if (count % 10 == 0) session.updateProgress("Analyzing Selection", count, 0, song.title).also { yield() }
        }

        session.config.playlistIds?.let { ids -> session.mirrorService.getPlaylists().filter { it.id in ids }.collect { it.imageId?.let { id -> session.requiredImageIds.add(id) } } }
        session.config.userPlaylistIds?.let { ids -> session.mirrorService.getUserPlaylists().filter { it.id in ids }.collect { it.imageId?.let { id -> session.requiredImageIds.add(id) } } }
        
        logger.info("Analysis complete. Identified ${session.requiredSongIds.size} songs, ${session.requiredArtistIds.size} artists, ${session.requiredAlbumIds.size} albums, and ${session.requiredImageIds.size} images to sync.")
    }

    private suspend fun syncImages(session: MirrorSession, remoteStats: ServerStats) {
        val total = if (session.isFiltered) session.requiredImageIds.size else remoteStats.imagesCount
        logger.info("Stage: Mirroring $total images...")
        session.updateProgress("Mirroring Images", 0, total, newStatus = "Mirroring $total images...")

        val flow = if (session.isFiltered) session.mirrorService.getImageMetadata().filter { it.id in session.requiredImageIds } else session.mirrorService.getImageMetadata()

        var count = 0
        @OptIn(ExperimentalCoroutinesApi::class)
        flow.chunked(100).flatMapConcat { images ->
            flow {
                val existing = if (session.config.isImport) imageService.getCoverHashes(images.map { it.imageHash })
                else imageService.byIds(images.map { it.id }).associate { it.imageHash to it.id }
                images.forEach { emit(it to existing[it.imageHash]) }
            }
        }.collect { (remote, localId) ->
            try {
                if (localId != null) {
                    session.imageIdMap[remote.id] = localId
                    session.existingImages++
                } else session.remoteImageService.getImageData(remote.id, 0)?.let { data ->
                    val newId = if (session.config.isImport) randomPlatformUUID() else remote.id
                    imageService.upsertImage(remote.copy(id = newId), data)
                    session.imageIdMap[remote.id] = newId
                    session.syncedImages++
                }
            } catch (e: Exception) { session.recordError("Image ${remote.imageHash}", e) }
            count++
            if (count % 50 == 0) logger.info("Mirrored $count/$total images...")
            if (count % 10 == 0) session.updateProgress("Mirroring Images", count, total, "Image ${remote.imageHash}").also { yield() }
        }
        logger.info("Completed mirroring images. New: ${session.syncedImages}, Existing: ${session.existingImages}")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun syncArtists(session: MirrorSession, remoteStats: ServerStats) {
        val total = if (session.isFiltered) session.requiredArtistIds.size else remoteStats.artistCount
        logger.info("Stage: Mirroring $total artists...")
        session.updateProgress("Mirroring Artists", 0, total, newStatus = "Mirroring $total artists...")

        val flow = if (session.isFiltered) session.mirrorService.getArtists().filter { it.id in session.requiredArtistIds } else session.mirrorService.getArtists()

        var count = 0
        flow.chunked(250).collect { batch ->
            if (session.config.isImport) {
                val result = artistService.getOrBulkCreateWithResult(batch.map { it.name }.distinct())
                batch.forEach { artist ->
                    session.artistIdMap[artist.id] = result.nameToIds[artist.name]?.firstOrNull() ?: randomPlatformUUID()
                    if (artist.name in result.newlyCreated) session.syncedArtists++ else session.existingArtists++
                }
            } else batch.forEach {
                session.artistIdMap[it.id] = it.id
                session.syncedArtists++
            }

            batch.forEach { artist ->
                try {
                    artistService.upsertArtist(artist.copy(
                        id = session.artistIdMap[artist.id]!!,
                        imageId = artist.imageId?.let { session.imageIdMap[it] },
                        artists = artist.artists.mapNotNull { sub -> session.artistIdMap[sub.id]?.let { sub.copy(id = it) } }
                    ))
                } catch (e: Exception) { session.recordError("Artist ${artist.name}", e) }
                count++
                if (count % 100 == 0) logger.info("Mirrored $count/$total artists...")
                if (count % 10 == 0) session.updateProgress("Mirroring Artists", count, total, artist.name).also { yield() }
            }
        }
        logger.info("Completed mirroring artists. New: ${session.syncedArtists}, Existing: ${session.existingArtists}")
    }

    private suspend fun syncArtistAliases(session: MirrorSession) {
        session.updateProgress("Mirroring Artist Aliases", 0, 0)
        val flow = if (session.isFiltered) session.mirrorService.getArtistAliases().filter { it.artistId in session.requiredArtistIds } else session.mirrorService.getArtistAliases()
        var count = 0
        flow.collect { alias ->
            try {
                val artistId = session.artistIdMap[alias.artistId] ?: if (session.config.isImport) return@collect else alias.artistId
                artistService.upsertArtistAlias(alias.copy(artistId = artistId))
            } catch (e: Exception) { session.recordError("Alias ${alias.name}", e) }
            if (++count % 50 == 0) session.updateProgress("Mirroring Artist Aliases", count, 0, alias.name).also { yield() }
        }

        session.updateProgress("Mirroring Artist Split Aliases", 0, 0)
        val splitFlow = if (session.isFiltered) session.mirrorService.getArtistSplitAliases().filter { it.artistId in session.requiredArtistIds } else session.mirrorService.getArtistSplitAliases()
        count = 0
        splitFlow.collect { alias ->
            try {
                val artistId = session.artistIdMap[alias.artistId] ?: if (session.config.isImport) return@collect else alias.artistId
                artistService.upsertArtistSplitAlias(alias.copy(artistId = artistId))
            } catch (e: Exception) { session.recordError("Split Alias ${alias.name}", e) }
            if (++count % 50 == 0) session.updateProgress("Mirroring Artist Split Aliases", count, 0, alias.name).also { yield() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun syncAlbums(session: MirrorSession, remoteStats: ServerStats) {
        val total = if (session.isFiltered) session.requiredAlbumIds.size else remoteStats.albumCount
        logger.info("Stage: Mirroring $total albums...")
        session.updateProgress("Mirroring Albums", 0, total, newStatus = "Mirroring $total albums...")

        val flow = if (session.isFiltered) session.mirrorService.getAlbums().filter { it.id in session.requiredAlbumIds } else session.mirrorService.getAlbums()

        var count = 0
        flow.chunked(250).collect { batch ->
            if (session.config.isImport) {
                val insertable = batch.map { InsertableAlbum(name = it.name, artists = it.artists.map { a -> a.name }, releaseDate = it.releaseDate, songCount = it.songCount, coverHash = null, originalId = it.originalId) }
                val result = albumService.getOrBulkCreateWithResult(insertable)
                batch.forEach { album ->
                    val key = InsertableAlbum(name = album.name, artists = album.artists.map { it.name }, releaseDate = album.releaseDate, songCount = album.songCount, coverHash = null, originalId = album.originalId)
                    session.albumIdMap[album.id] = result.albumToIds[key] ?: randomPlatformUUID()
                    if (key in result.newlyCreated) session.syncedAlbums++ else session.existingAlbums++
                }
            } else batch.forEach {
                session.albumIdMap[it.id] = it.id
                session.syncedAlbums++
            }

            batch.forEach { album ->
                try {
                    albumService.upsertAlbum(album.copy(
                        id = session.albumIdMap[album.id]!!,
                        coverId = album.coverId?.let { session.imageIdMap[it] },
                        artists = album.artists.mapNotNull { sub -> session.artistIdMap[sub.id]?.let { sub.copy(id = it) } }
                    ))
                } catch (e: Exception) { session.recordError("Album ${album.name}", e) }
                count++
                if (count % 100 == 0) logger.info("Mirrored $count/$total albums...")
                if (count % 10 == 0) session.updateProgress("Mirroring Albums", count, total, album.name).also { yield() }
            }
        }
        logger.info("Completed mirroring albums. New: ${session.syncedAlbums}, Existing: ${session.existingAlbums}")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun syncSongs(session: MirrorSession, remoteStats: ServerStats) {
        val total = if (session.isFiltered) session.requiredSongIds.size else remoteStats.songCount
        logger.info("Stage: Mirroring $total songs...")
        session.updateProgress("Mirroring Songs", 0, total, newStatus = "Downloading songs...")

        val flow = if (session.isFiltered) session.mirrorService.getSongs().filter { it.id in session.requiredSongIds } else session.mirrorService.getSongs()

        flow.flatMapMerge(16) { song ->
            flow {
                try {
                    val size = if (session.config.quality == -1) song.audio?.fileSize ?: 0L
                    else session.remoteSongService.getDownloadSize(song.id, session.config.quality)
                    emit(song to size)
                } catch (e: Exception) {
                    val displayName = "${song.artists.firstOrNull()?.name} - ${song.title}"
                    session.recordError(displayName, e)
                    session.progressMutex.withLock {
                        session.songCount++
                        session.updateProgress("Mirroring Songs", session.songCount, total, displayName, 1.0f, session.totalBytesSynced)
                    }
                }
            }
        }.buffer(2048).flatMapMerge(3) { (song, size) ->
            flow {
                val displayName = "${song.artists.firstOrNull()?.name} - ${song.title}"
                try {
                    val localAlbumId = song.album?.id?.let { session.albumIdMap[it] } ?: if (session.config.isImport) return@flow else song.album?.id
                    val newId = if (session.config.isImport) songService.findSongIdByMetadata(song.title, localAlbumId!!, song.trackNumber, song.discNumber, song.explicit) ?: randomPlatformUUID() else song.id
                    val localPathString = resolveLocalPath(song.path, newId.toString(), session.config.quality, session.remotePaths)
                    val base = localPathString.substringBeforeLast('.')
                    val existing = listOf("flac", "wav", "aiff", "aif", "ogg").map { File("$base.$it") }.firstOrNull { it.exists() && it.length() > 0 }
                    val isComplete = existing != null && (existing.absolutePath != File(localPathString).absolutePath || (size > 0 && existing.length() == size))
                    val targetPath = existing?.absolutePath ?: localPathString

                    if (!isComplete) {
                        logger.info("Downloading song: $displayName")
                        Path(localPathString).also { it.parent.toFile().mkdirs() }.outputStream().use { output ->
                            var downloaded = 0L
                            session.mirrorService.getSongData(song.id, session.config.quality, 64 * 1024, force = false).collect { chunk ->
                                withContext(Dispatchers.IO) { output.write(chunk) }
                                downloaded += chunk.size
                                session.progressMutex.withLock {
                                    session.totalBytesSynced += chunk.size
                                    if (downloaded % (256 * 1024) > chunk.size) {
                                        session.updateProgress("Mirroring Songs", session.songCount, total, displayName, if (size > 0) downloaded.toFloat() / size else null, session.totalBytesSynced)
                                    }
                                }
                                yield()
                            }
                        }
                        session.progressMutex.withLock { session.syncedSongs++ }
                        storageService.invalidate(StorageCategory.TOTAL)
                    } else {
                        session.progressMutex.withLock { session.existingSongs++ }
                    }

                    songService.upsertSong(song.copy(id = newId, path = targetPath, album = song.album?.copy(id = localAlbumId!!),
                        artists = song.artists.mapNotNull { s -> session.artistIdMap[s.id]?.let { s.copy(id = it) } }, coverId = song.coverId?.let { session.imageIdMap[it] }))
                    session.songIdMap[song.id] = newId
                    session.progressMutex.withLock {
                        session.songCount++
                        if (session.songCount % 50 == 0) logger.info("Mirrored ${session.songCount}/$total songs...")
                        session.updateProgress("Mirroring Songs", session.songCount, total, displayName, 1.0f, session.totalBytesSynced)
                    }
                } catch (e: Exception) {
                    session.recordError(displayName, e)
                    session.progressMutex.withLock { session.songCount++ ; session.updateProgress("Mirroring Songs", session.songCount, total, displayName, 1.0f, session.totalBytesSynced) }
                }
                emit(Unit)
            }
        }.collect()
        logger.info("Completed mirroring songs. New: ${session.syncedSongs}, Existing: ${session.existingSongs}")
    }

    private suspend fun syncPlaylists(session: MirrorSession, remoteStats: ServerStats) {
        val flow = if (session.isFiltered) {
            if (session.config.playlistIds.isNullOrEmpty()) emptyFlow() else session.mirrorService.getPlaylists().filter { it.id in session.config.playlistIds!! }
        } else session.mirrorService.getPlaylists()

        val total = if (session.isFiltered) session.config.playlistIds?.size ?: 0 else remoteStats.playlistCount
        logger.info("Stage: Mirroring $total playlists...")
        session.updateProgress("Mirroring Playlists", 0, total, newStatus = "Mirroring playlists...")

        var count = 0
        flow.collect { playlist ->
            try {
                val existing = if (session.config.isImport) playlistService.byName(playlist.name) else null
                val finalId = if (existing != null) {
                    val songs = playlist.songs.mapNotNull { session.songIdMap[it] }.filter { it !in existing.songs }
                    if (songs.isNotEmpty()) playlistService.upsertPlaylist(existing.copy(songs = existing.songs + songs))
                    session.existingPlaylists++
                    existing.id
                } else {
                    val id = if (session.config.isImport) randomPlatformUUID() else playlist.id
                    playlistService.upsertPlaylist(playlist.copy(id = id, imageId = playlist.imageId?.let { session.imageIdMap[it] }, songs = playlist.songs.mapNotNull { session.songIdMap[it] }))
                    session.syncedPlaylists++
                    id
                }
                session.playlistIdMap[playlist.id] = finalId
            } catch (e: Exception) { session.recordError("Playlist ${playlist.name}", e) }
            count++
            if (count % 10 == 0) logger.info("Mirrored $count/$total playlists...")
            session.updateProgress("Mirroring Playlists", count, total, playlist.name)
        }
        logger.info("Completed mirroring playlists. New: ${session.syncedPlaylists}, Existing: ${session.existingPlaylists}")
    }

    private suspend fun syncUsers(session: MirrorSession) {
        logger.info("Stage: Syncing users...")
        session.updateProgress("Syncing Users", 0, 0, newStatus = "Mirroring user accounts...")
        var count = 0
        session.mirrorService.getUsers().collect { user ->
            try {
                userService.upsertUser(user)
                count++
            } catch (e: Exception) {
                session.recordError("User ${user.username}", e)
            }
        }
        logger.info("Completed mirroring $count user accounts")
        session.updateProgress("Syncing Users", count, count, item = "Completed mirroring $count user accounts")
    }

    private suspend fun syncUserPlaylists(session: MirrorSession) {
        val flow = if (session.isFiltered) {
            if (session.config.userPlaylistIds.isNullOrEmpty()) emptyFlow() else session.mirrorService.getUserPlaylists().filter { it.id in session.config.userPlaylistIds!! }
        } else session.mirrorService.getUserPlaylists()

        val total = if (session.isFiltered) session.config.userPlaylistIds?.size ?: 0 else 0
        if (total == 0) return
        
        logger.info("Stage: Mirroring $total user playlists...")
        session.updateProgress("Mirroring User Playlists", 0, total, newStatus = "Mirroring user playlists...")

        var count = 0
        flow.collect { playlist ->
            try {
                val existing = if (session.config.isImport && session.config.targetUserId != null) userPlaylistService.byName(playlist.name, session.config.targetUserId!!) else null
                val finalId = if (existing != null) {
                    val songs = playlist.songs.mapNotNull { session.songIdMap[it] }.filter { it !in existing.songs }
                    if (songs.isNotEmpty()) userPlaylistService.addToPlaylist(existing.id, songs.map { System.currentTimeMillis() to it })
                    session.existingUserPlaylists++
                    existing.id
                } else {
                    val id = if (session.config.isImport) randomPlatformUUID() else playlist.id
                    userPlaylistService.upsertUserPlaylist(
                        playlist.copy(
                            id = id,
                            imageId = playlist.imageId?.let { session.imageIdMap[it] },
                            songs = playlist.songs.mapNotNull { session.songIdMap[it] },
                            songEntries = playlist.songEntries?.mapNotNull { entry ->
                                session.songIdMap[entry.songId]?.let { entry.copy(songId = it) }
                            }
                        ),
                        session.config.targetUserId
                    )
                    session.syncedUserPlaylists++
                    id
                }
                session.userPlaylistIdMap[playlist.id] = finalId
            } catch (e: Exception) { session.recordError("User Playlist ${playlist.name}", e) }
            count++
            if (count % 10 == 0) logger.info("Mirrored $count/$total user playlists...")
            session.updateProgress("Mirroring User Playlists", count, total, playlist.name)
        }
        logger.info("Completed mirroring user playlists. New: ${session.syncedUserPlaylists}, Existing: ${session.existingUserPlaylists}")
    }

    private suspend fun syncUserPreferences(session: MirrorSession) {
        if (session.config.targetUserId == null || session.config.likedByUserIds.isNullOrEmpty()) return
        val total = session.config.likedByUserIds!!.size
        logger.info("Stage: Syncing user preferences for $total users...")
        session.updateProgress("Syncing User Preferences", 0, total, newStatus = "Mapping liked songs...")
        
        val remoteUserNames = mutableMapOf<PlatformUUID, String>()
        try { session.mirrorService.getUsers().collect { remoteUserNames[it.id] = it.displayName ?: it.username } } catch (_: Exception) {}

        session.config.likedByUserIds!!.forEachIndexed { index, userId ->
            val name = remoteUserNames[userId] ?: userId.toString()
            logger.info("Syncing liked songs for user: $name")
            var count = 0
            session.mirrorService.getLikedSongs(userId).collect { song ->
                songService.setLiked(session.songIdMap[song.id] ?: song.id, session.config.targetUserId!!, true)
                count++
                if (count % 50 == 0) {
                    logger.info("Synced $count liked songs for $name...")
                    session.updateProgress("Syncing User Preferences", index, total, item = "User: $name ($count likes...)")
                }
            }
            logger.info("Completed syncing $count liked songs for $name")
            session.updateProgress("Syncing User Preferences", index + 1, total, item = "Completed: $name ($count likes)")
        }
        logger.info("Completed syncing user preferences")
    }

    // --- Helpers ---

    private fun formatBytes(bytes: Long): String {
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) { size /= 1024 ; unitIndex++ }
        return "%.2f %s".format(size, units[unitIndex])
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds < 60) return "${maxOf(0, seconds)}s"
        val mins = seconds / 60
        if (mins < 60) return "${mins}m ${seconds % 60}s"
        return "${mins / 60}h ${mins % 60}m"
    }

    private fun resolveLocalPath(remotePath: String, id: String, quality: Int, remotePaths: RemoteServerPaths): String {
        val ext = if (quality == -1) remotePath.substringAfterLast('.', "flac") else "ogg"
        fun String.fix() = if (quality == -1) this else if (contains('.')) substringBeforeLast('.') + ".ogg" else "$this.ogg"
        fun resolve(remote: String?, local: String?) = if (remote != null && local != null && remotePath.startsWith(remote)) Path(local, remotePath.removePrefix(remote).trimStart('/', '\\').fix()).absolutePathString() else null

        return resolve(remotePaths.customAudioPath, storageService.customAudioPath)
            ?: resolve(remotePaths.tracksPath, storageService.tracksPath)
            ?: remotePaths.secondaryTracksPaths.firstNotNullOfOrNull { resolve(it, storageService.tracksPath) }
            ?: Path(storageService.tracksPath!!, "$id.$ext").absolutePathString()
    }

    private inner class RemoteMirrorRpcManager(
        private var config: RemoteServerConfig
    ) : BaseRpcServiceManager(httpClient) {
        private var token: String? = null

        @Suppress("HttpUrlsUsage")
        override suspend fun getRpcUrl(): String {
            val protocol = if (config.secure) "wss" else "ws"
            val cleanHost = config.host.removePrefix("http://").removePrefix("https://").removeSuffix("/")
            val base = "$protocol://$cleanHost:${config.port}"
            val proxyPath = if (config.useProxy && !config.proxyInstanceId.isNullOrEmpty()) "/${config.proxyInstanceId!!.removePrefix("/")}" else ""
            return "$base$proxyPath"
        }

        override suspend fun setRpcUrl(host: String, port: Int, ssl: Boolean, path: String) {
            config = config.copy(
                host = host,
                port = port,
                secure = ssl,
                proxyInstanceId = if (path != "/") path.removePrefix("/") else null,
                useProxy = path != "/"
            )
        }

        override fun getAuthToken(): String? = token
        override fun getRefreshToken(): String? = null
        override fun isTokenExpired(): Boolean = token == null
        override fun isAuthenticated(): Boolean = token != null

        override suspend fun updateAuth(response: AuthenticationResponse) {
            token = response.token
        }

        override suspend fun handleAuthFailure() {
            token = null
        }

        suspend fun ensureAuthenticated() {
            if (token != null) return
            mutex.withLock {
                if (token != null) return@withLock
                logger.info("Authenticating with remote server at ${getRpcUrl()}/rpc/auth")
                val authService = getAuthService()
                val response = authService.authenticate(config.username, config.password)
                updateAuth(response)
                logger.info("Successfully authenticated as ${config.username}")
            }
        }
    }

    private inner class MirrorSession(
        val config: RemoteServerConfig,
        val mirrorService: IMirrorService,
        val remoteImageService: IImageService,
        val remoteSongService: ISongService,
        val remotePaths: RemoteServerPaths,
    ) {
        val stageStartTime = System.currentTimeMillis()
        var lastTask: String? = null
        val progressHistory = mutableListOf<Triple<Long, Double, Long?>>()
        var statusMessage: String? = null
        var syncedSongs = 0 ; var existingSongs = 0 ; var syncedArtists = 0 ; var existingArtists = 0 ; var syncedAlbums = 0 ; var existingAlbums = 0 ; var syncedImages = 0 ; var existingImages = 0
        var syncedPlaylists = 0 ; var existingPlaylists = 0 ; var syncedUserPlaylists = 0 ; var existingUserPlaylists = 0 ; var syncedErrors = 0
        val failedItemNames = mutableListOf<String>()
        val progressMutex = Mutex()
        val imageIdMap = mutableMapOf<PlatformUUID, PlatformUUID>()
        val artistIdMap = mutableMapOf<PlatformUUID, PlatformUUID>()
        val albumIdMap = mutableMapOf<PlatformUUID, PlatformUUID>()
        val songIdMap = mutableMapOf<PlatformUUID, PlatformUUID>()
        val playlistIdMap = mutableMapOf<PlatformUUID, PlatformUUID>()
        val userPlaylistIdMap = mutableMapOf<PlatformUUID, PlatformUUID>()
        val requiredSongIds = mutableSetOf<PlatformUUID>()
        val requiredArtistIds = mutableSetOf<PlatformUUID>()
        val requiredAlbumIds = mutableSetOf<PlatformUUID>()
        val requiredImageIds = mutableSetOf<PlatformUUID>()
        val isFiltered = !config.playlistIds.isNullOrEmpty() || !config.userPlaylistIds.isNullOrEmpty() || !config.likedByUserIds.isNullOrEmpty()
        var totalBytesSynced = 0L ; var songCount = 0

        fun updateProgress(task: String, processed: Int, total: Int, item: String? = null, itemProgress: Float? = null, byteCount: Long? = null, newStatus: String? = null, isFinished: Boolean = false, error: String? = null) {
            val now = System.currentTimeMillis()
            if (lastTask != task) { progressHistory.clear() ; lastTask = task }
            if (newStatus != null) statusMessage = newStatus
            val currentTotalProgress = processed.toDouble() + (itemProgress ?: 0f).toDouble()
            progressHistory.add(Triple(now, currentTotalProgress, byteCount))
            while (progressHistory.size > 1 && progressHistory.first().first < now - 5000) progressHistory.removeAt(0)

            val elapsed = (now - stageStartTime) / 1000.0
            val oldest = progressHistory.first()
            val windowElapsed = (now - oldest.first) / 1000.0

            val speedStr = if (windowElapsed > 0.5) {
                if (byteCount != null && oldest.third != null) "${formatBytes(((byteCount - oldest.third!!) / windowElapsed).toLong())}/s"
                else "%.1f items/s".format((currentTotalProgress - oldest.second) / windowElapsed)
            } else if (elapsed > 0.1) {
                if (byteCount != null) "${formatBytes((byteCount / elapsed).toLong())}/s"
                else "%.1f items/s".format(processed / elapsed)
            } else null

            val etaStr = run {
                if (windowElapsed > 1.0 && total > currentTotalProgress) {
                    val diff = currentTotalProgress - oldest.second
                    if (diff > 0) return@run formatDuration(((total - currentTotalProgress) / (diff / windowElapsed)).toLong())
                }
                if (elapsed > 1.0 && total > currentTotalProgress && currentTotalProgress > 0) {
                    val rem = (elapsed / (currentTotalProgress / total)) - elapsed
                    if (rem > 0) return@run formatDuration(rem.toLong())
                }
                null
            }

            _activeProgress.value = MirrorProgress(task, processed, total, isFinished, error, item, itemProgress, speedStr, etaStr, statusMessage,
                if (isFinished) SyncBreakdown(
                    songs = syncedSongs, existingSongs = existingSongs,
                    artists = syncedArtists, existingArtists = existingArtists,
                    albums = syncedAlbums, existingAlbums = existingAlbums,
                    images = syncedImages, existingImages = existingImages,
                    playlists = syncedPlaylists, existingPlaylists = existingPlaylists,
                    userPlaylists = syncedUserPlaylists, existingUserPlaylists = existingUserPlaylists,
                    errors = syncedErrors, failedItems = failedItemNames.toList()
                ) else null
            )
        }
        
        suspend fun recordError(itemName: String, e: Exception) {
            logger.error("Failed to mirror $itemName: ${e.message}", e)
            progressMutex.withLock { syncedErrors++ ; failedItemNames.add(itemName) }
        }
    }
}
