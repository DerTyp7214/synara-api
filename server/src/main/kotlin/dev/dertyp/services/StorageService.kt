package dev.dertyp.services

import dev.dertyp.core.getTotalSize
import dev.dertyp.plugins.IServerStorageService
import dev.dertyp.services.download.DownloadBackend
import io.ktor.server.application.ApplicationEnvironment
import java.io.File

class StorageService(environment: ApplicationEnvironment): IStorageService, IServerStorageService {
    override val tracksPath = environment.config.propertyOrNull("audio.tracks")?.getString()?.removeSuffix("/")
    override val albumsPath = environment.config.propertyOrNull("audio.albums")?.getString()?.removeSuffix("/")
    override val playlistsPath = environment.config.propertyOrNull("audio.playlists")?.getString()?.removeSuffix("/")
    override val customAudioPath = environment.config.property("audio.custom").getString().removeSuffix("/")
    override val imagesPath = environment.config.property("data.images").getString().removeSuffix("/")
    override val secondaryTracksPaths = try {
        environment.config.propertyOrNull("audio.secondary-tracks")?.getList()?.map {
            it.removeSuffix("/")
        } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    override fun forDownloader(backend: DownloadBackend): IServerStorageService = DownloaderStorageService(this, backend)

    override suspend fun getTotalStorage(): Long {
        if (tracksPath == null || albumsPath == null || playlistsPath == null) return 0

        val tracks = File(tracksPath).getTotalSize()
        val albums = File(albumsPath).getTotalSize()
        val playlists = File(playlistsPath).getTotalSize()
        val secondaryTracks = secondaryTracksPaths.sumOf { File(it).getTotalSize() }
        val customAudio = File(customAudioPath).getTotalSize()

        return tracks + albums + playlists + secondaryTracks + customAudio
    }
}

class DownloaderStorageService(
    private val delegate: IServerStorageService,
    private val backend: DownloadBackend
) : IServerStorageService {
    private fun pluginPath(path: String?): String? {
        if (path == null) return null
        val file = File(path)
        val parent = file.parentFile ?: return null
        return File(parent, "${backend.id}/${file.name}").absolutePath
    }

    override val tracksPath: String? get() = pluginPath(delegate.tracksPath)
    override val albumsPath: String? get() = pluginPath(delegate.albumsPath)
    override val playlistsPath: String? get() = pluginPath(delegate.playlistsPath)
    override val customAudioPath: String get() = delegate.customAudioPath
    override val imagesPath: String get() = delegate.imagesPath
    override val secondaryTracksPaths: List<String> get() = delegate.secondaryTracksPaths

    override fun forDownloader(backend: DownloadBackend): IServerStorageService = delegate.forDownloader(backend)
}
