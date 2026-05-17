package dev.dertyp.utils.parsers

import dev.dertyp.services.import.Type

class MusikSammlerParser : UrlParser() {
    override val name: String = "musiksammler"

    override fun canHandle(url: String): Boolean {
        if (handlePrefix(url) != null) return true
        val host = getUri(url)?.host?.lowercase() ?: ""
        return host.contains("musik-sammler.de")
    }

    override suspend fun parse(url: String): Pair<String, Type>? {
        if (!canHandle(url)) return null
        handlePrefix(url)?.let { return it to Type.ALBUM }

        val uri = getUri(url) ?: return null
        val pathParts = uri.path.trim('/').split("/")

        if (pathParts.contains("album")) {
            val albumIndex = pathParts.indexOf("album")
            if (albumIndex + 1 < pathParts.size) {
                val id = pathParts[albumIndex + 1]
                if (id.all { it.isDigit() }) return id to Type.ALBUM
            }
        }

        return null
    }
}
