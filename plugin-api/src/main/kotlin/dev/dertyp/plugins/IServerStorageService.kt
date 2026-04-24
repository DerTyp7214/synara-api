package dev.dertyp.plugins

import dev.dertyp.services.download.DownloadBackend

interface IServerStorageService {
    val tracksPath: String?
    val albumsPath: String?
    val playlistsPath: String?
    val customAudioPath: String
    val imagesPath: String
    val secondaryTracksPaths: List<String>

    fun forDownloader(backend: DownloadBackend): IServerStorageService
}
