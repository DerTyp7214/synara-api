package dev.dertyp.utils.parsers

import dev.dertyp.services.import.Type

class DeezerParser : UrlParser() {
    override val name: String = "deezer"

    override fun canHandle(url: String): Boolean {
        if (handlePrefix(url) != null) return true
        val host = getUri(url)?.host?.lowercase() ?: ""
        return host == "deezer.com" || host.endsWith(".deezer.com")
    }

    override suspend fun parse(url: String): Pair<String, Type>? {
        if (!canHandle(url)) return null
        handlePrefix(url)?.let { return it to Type.SONG }

        val uri = getUri(url) ?: return null
        val pathParts = uri.path.trim('/').split("/")
        
        val albumIndex = pathParts.indexOf("album")
        if (albumIndex != -1 && albumIndex + 1 < pathParts.size) {
            return pathParts[albumIndex + 1] to Type.ALBUM
        }

        val trackIndex = pathParts.indexOf("track")
        if (trackIndex != -1 && trackIndex + 1 < pathParts.size) {
            return pathParts[trackIndex + 1] to Type.SONG
        }

        val artistIndex = pathParts.indexOf("artist")
        if (artistIndex != -1 && artistIndex + 1 < pathParts.size) {
            return pathParts[artistIndex + 1] to Type.ARTIST
        }

        val playlistIndex = pathParts.indexOf("playlist")
        if (playlistIndex != -1 && playlistIndex + 1 < pathParts.size) {
            return pathParts[playlistIndex + 1] to Type.PLAYLIST
        }

        return null
    }
}
