package dev.dertyp.services

import dev.dertyp.core.getTotalSize
import dev.dertyp.plugins.IServerStorageService
import dev.dertyp.services.import.ImportBackend
import io.ktor.server.application.ApplicationEnvironment
import java.io.File

class StorageService(environment: ApplicationEnvironment) : IStorageService, IServerStorageService {
    override val tracksPath =
        environment.config.propertyOrNull("audio.tracks")?.getString()?.removeSuffix("/")
    override val albumsPath =
        environment.config.propertyOrNull("audio.albums")?.getString()?.removeSuffix("/")
    override val playlistsPath =
        environment.config.propertyOrNull("audio.playlists")?.getString()?.removeSuffix("/")
    override val customAudioPath =
        environment.config.property("audio.custom").getString().removeSuffix("/")
    override val imagesPath =
        environment.config.property("data.images").getString().removeSuffix("/")
    override val animatedImagesPath =
        environment.config.property("data.animated-images").getString().removeSuffix("/")
    override val secondaryTracksPaths = try {
        environment.config.propertyOrNull("audio.secondary-tracks")?.getList()?.map {
            it.removeSuffix("/")
        } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    override fun forImporter(backend: ImportBackend): IServerStorageService =
        ImporterStorageService(this, backend)

    override suspend fun getTotalStorage(): Long {
        val pathsToMeasure = (
                listOfNotNull(
                    tracksPath,
                    albumsPath,
                    playlistsPath
                ).map { File(it).parentFile } +
                        secondaryTracksPaths.map { File(it) } +
                        listOf(File(customAudioPath)))
            .filterNotNull()
            .map { it.absoluteFile }
            .distinctBy { it.path }

        val rootPaths = pathsToMeasure.filter { p ->
            pathsToMeasure.none { other ->
                other != p && p.path.startsWith(other.path + File.separator)
            }
        }

        return rootPaths.sumOf { it.getTotalSize() }
    }

    suspend fun getImagesStorage(): Long = File(imagesPath).getTotalSize()

    suspend fun getAnimatedImagesStorage(): Long = File(animatedImagesPath).getTotalSize()
}

class ImporterStorageService(
    private val delegate: IServerStorageService,
    private val backend: ImportBackend
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
    override val animatedImagesPath: String get() = delegate.animatedImagesPath
    override val secondaryTracksPaths: List<String> get() = delegate.secondaryTracksPaths

    override fun forImporter(backend: ImportBackend): IServerStorageService =
        delegate.forImporter(backend)
}
