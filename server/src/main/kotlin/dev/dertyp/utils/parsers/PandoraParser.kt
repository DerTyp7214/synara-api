package dev.dertyp.utils.parsers

import dev.dertyp.services.import.Type

class PandoraParser : UrlParser() {
    override val name: String = "pandora"

    override fun canHandle(url: String): Boolean {
        if (handlePrefix(url) != null) return true
        val host = getUri(url)?.host?.lowercase() ?: ""
        return host == "pandora.com" || host.endsWith(".pandora.com")
    }

    override suspend fun parse(url: String): Pair<String, Type>? {
        if (!canHandle(url)) return null
        handlePrefix(url)?.let { return it to Type.SONG }

        val uri = getUri(url) ?: return null
        val pathParts = uri.path.trim('/').split("/")

        val id = pathParts.lastOrNull() ?: return null
        return when {
            id.startsWith("AL:") -> id to Type.ALBUM
            id.startsWith("TR:") -> id to Type.SONG
            id.startsWith("AR:") -> id to Type.ARTIST
            id.startsWith("PL:") -> id to Type.PLAYLIST
            else -> null
        }
    }
}
