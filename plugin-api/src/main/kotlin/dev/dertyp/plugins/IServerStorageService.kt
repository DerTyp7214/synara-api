package dev.dertyp.plugins

import dev.dertyp.services.import.ImportBackend

interface IServerStorageService {
    val tracksPath: String?
    val albumsPath: String?
    val playlistsPath: String?
    val customAudioPath: String
    val imagesPath: String
    val secondaryTracksPaths: List<String>

    fun forImporter(backend: ImportBackend): IServerStorageService
}
