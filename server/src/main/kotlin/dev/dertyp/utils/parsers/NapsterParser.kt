package dev.dertyp.utils.parsers

import dev.dertyp.services.import.Type

class NapsterParser : UrlParser() {
    override val name: String = "napster"

    override fun canHandle(url: String): Boolean {
        if (handlePrefix(url) != null) return true
        val host = getUri(url)?.host?.lowercase() ?: ""
        return host.contains("napster.com")
    }

    override suspend fun parse(url: String): Pair<String, Type>? {
        if (!canHandle(url)) return null
        handlePrefix(url)?.let { return it to Type.SONG }

        val uri = getUri(url) ?: return null
        val pathParts = uri.path.trim('/').split("/")

        val id = pathParts.lastOrNull() ?: return null
        return when {
            id.startsWith("alb.") -> id to Type.ALBUM
            id.startsWith("tra.") -> id to Type.SONG
            id.startsWith("art.") -> id to Type.ARTIST
            id.startsWith("pp.") -> id to Type.PLAYLIST
            else -> null
        }
    }
}
