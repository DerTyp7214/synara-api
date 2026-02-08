package dev.dertyp.services

import dev.dertyp.core.getTotalSize
import io.ktor.server.application.*
import java.io.File

class StorageService(environment: ApplicationEnvironment): IStorageService {
    val tracksPath = environment.config.propertyOrNull("audio.tracks")?.getString()?.removeSuffix("/")
    val albumsPath = environment.config.propertyOrNull("audio.albums")?.getString()?.removeSuffix("/")
    val playlistsPath = environment.config.propertyOrNull("audio.playlists")?.getString()?.removeSuffix("/")
    val customAudioPath = environment.config.property("audio.custom").getString().removeSuffix("/")
    val imagesPath = environment.config.property("data.images").getString().removeSuffix("/")
    val secondaryTracksPaths = try {
        environment.config.propertyOrNull("audio.secondary-tracks")?.getList()?.map {
            it.removeSuffix("/")
        } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

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