package dev.dertyp.utils.parsers

import dev.dertyp.services.import.Type

class TidalParser : UrlParser() {
    override val name: String = "tidal"
    override val alternativeNames: List<String> = listOf("tiddl", "tdn")

    override fun canHandle(url: String): Boolean {
        if (handlePrefix(url) != null) return true
        val host = getUri(url)?.host?.lowercase() ?: ""
        return host.contains("tidal.com")
    }

    override suspend fun parse(url: String): Pair<String, Type>? {
        handlePrefix(url)?.let { return it.removeSuffix("/u") to Type.SONG }

        val uri = getUri(url) ?: return null
        val host = uri.host?.lowercase() ?: ""
        var path = uri.path ?: ""

        if (!host.contains("tidal.com")) return null

        if (path.startsWith("/browse")) {
            path = path.removePrefix("/browse")
        }

        val parts = path.trim('/').split("/")
        if (parts.size < 2) return null

        val type = when (parts[0]) {
            "track" -> Type.SONG
            "album" -> Type.ALBUM
            "playlist" -> Type.PLAYLIST
            "artist" -> Type.ARTIST
            "video" -> Type.VIDEO
            else -> return null
        }

        val id = parts[1].removeSuffix("/u")

        return id to type
    }
}
