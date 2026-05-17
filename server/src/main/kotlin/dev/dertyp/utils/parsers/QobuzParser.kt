package dev.dertyp.utils.parsers

import dev.dertyp.services.import.Type

class QobuzParser : UrlParser() {
    override val name: String = "qobuz"

    override fun canHandle(url: String): Boolean {
        if (handlePrefix(url) != null) return true
        val host = getUri(url)?.host?.lowercase() ?: ""
        return host.contains("qobuz.com")
    }

    override suspend fun parse(url: String): Pair<String, Type>? {
        if (!canHandle(url)) return null
        handlePrefix(url)?.let { return it to Type.ALBUM }

        val uri = getUri(url) ?: return null
        val pathParts = uri.path.trim('/').split("/")

        if (pathParts.contains("album")) {
            val albumIndex = pathParts.indexOf("album")
            if (albumIndex + 1 < pathParts.size) {
                return pathParts.last() to Type.ALBUM
            }
        }

        if (pathParts.contains("track")) {
            val trackIndex = pathParts.indexOf("track")
            if (trackIndex + 1 < pathParts.size) {
                return pathParts.last() to Type.SONG
            }
        }

        return null
    }
}
