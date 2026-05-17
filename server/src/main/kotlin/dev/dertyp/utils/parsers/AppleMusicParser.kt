package dev.dertyp.utils.parsers

import dev.dertyp.services.import.Type

class AppleMusicParser : UrlParser() {
    override val name: String = "apple"
    override val alternativeNames: List<String> = listOf("itunes", "applemusic")

    override fun canHandle(url: String): Boolean {
        if (handlePrefix(url) != null) return true
        val host = getUri(url)?.host?.lowercase() ?: ""
        return host == "music.apple.com" || host == "itunes.apple.com" || host == "geo.music.apple.com"
    }

    override suspend fun parse(url: String): Pair<String, Type>? {
        if (!canHandle(url)) return null
        handlePrefix(url)?.let { return it to Type.SONG }

        val uri = getUri(url) ?: return null
        val queryParams = uri.query?.split("&")?.associate {
            val parts = it.split("=")
            parts[0] to parts.getOrNull(1)
        } ?: emptyMap()

        queryParams["i"]?.let { return it to Type.SONG }

        val pathParts = uri.path.trim('/').split("/")
        if (pathParts.isEmpty()) return null

        val type = when {
            pathParts.contains("album") -> Type.ALBUM
            pathParts.contains("song") -> Type.SONG
            pathParts.contains("music-video") -> Type.VIDEO
            pathParts.contains("artist") -> Type.ARTIST
            pathParts.contains("playlist") -> Type.PLAYLIST
            else -> null
        }

        val id = pathParts.lastOrNull()?.removePrefix("id")
        if (id != null && id.all { it.isDigit() }) {
            return id to (type ?: Type.ALBUM)
        }

        return null
    }
}
