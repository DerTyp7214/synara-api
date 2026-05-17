package dev.dertyp.utils.parsers

import dev.dertyp.services.import.Type

class BeatportParser : UrlParser() {
    override val name: String = "beatport"

    override fun canHandle(url: String): Boolean {
        if (handlePrefix(url) != null) return true
        val host = getUri(url)?.host?.lowercase() ?: ""
        return host == "beatport.com" || host.endsWith(".beatport.com")
    }

    override suspend fun parse(url: String): Pair<String, Type>? {
        if (!canHandle(url)) return null
        handlePrefix(url)?.let { return it to Type.SONG }

        val uri = getUri(url) ?: return null
        val pathParts = uri.path.trim('/').split("/")

        val type = when {
            pathParts.contains("release") -> Type.ALBUM
            pathParts.contains("track") -> Type.SONG
            pathParts.contains("artist") -> Type.ARTIST
            pathParts.contains("label") -> Type.ARTIST
            else -> null
        }

        val id = pathParts.lastOrNull()
        if (id != null && id.all { it.isDigit() }) {
            return id to (type ?: Type.ALBUM)
        }

        return null
    }
}
