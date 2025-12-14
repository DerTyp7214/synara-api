package dev.dertyp.services

import dev.dertyp.core.getTotalSize
import io.ktor.server.application.*
import java.io.File

class StorageService(environment: ApplicationEnvironment) {
    val tracksPath = environment.config.propertyOrNull("audio.tracks")?.getString()?.removeSuffix("/")
    val albumsPath = environment.config.propertyOrNull("audio.albums")?.getString()?.removeSuffix("/")
    val playlistsPath = environment.config.propertyOrNull("audio.playlists")?.getString()?.removeSuffix("/")

    fun getTotalStorage(): Long {
        if (tracksPath == null || albumsPath == null || playlistsPath == null) return 0

        val tracks = File(tracksPath).getTotalSize()
        val albums = File(albumsPath).getTotalSize()
        val playlists = File(playlistsPath).getTotalSize()

        return tracks + albums + playlists
    }
}