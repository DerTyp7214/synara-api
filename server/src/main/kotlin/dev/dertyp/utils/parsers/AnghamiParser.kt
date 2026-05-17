package dev.dertyp.utils.parsers

import dev.dertyp.services.import.Type

class AnghamiParser : UrlParser() {
    override val name: String = "anghami"

    override fun canHandle(url: String): Boolean {
        if (handlePrefix(url) != null) return true
        val host = getUri(url)?.host?.lowercase() ?: ""
        return host.contains("anghami.com")
    }

    override suspend fun parse(url: String): Pair<String, Type>? {
        if (!canHandle(url)) return null
        handlePrefix(url)?.let { return it to Type.SONG }

        val uri = getUri(url) ?: return null
        val pathParts = uri.path.trim('/').split("/")

        val type = when {
            pathParts.contains("album") -> Type.ALBUM
            pathParts.contains("song") -> Type.SONG
            pathParts.contains("artist") -> Type.ARTIST
            pathParts.contains("playlist") -> Type.PLAYLIST
            else -> null
        }

        val id = pathParts.lastOrNull()
        if (id != null && id.all { it.isDigit() }) {
            return id to (type ?: Type.ALBUM)
        }

        return null
    }
}
