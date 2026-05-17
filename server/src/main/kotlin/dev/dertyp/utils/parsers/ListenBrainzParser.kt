package dev.dertyp.utils.parsers

import dev.dertyp.services.import.Type

class ListenBrainzParser : UrlParser() {
    override val name: String = "listenbrainz"

    override fun canHandle(url: String): Boolean {
        if (handlePrefix(url) != null) return true
        val host = getUri(url)?.host?.lowercase() ?: ""
        return host == "listenbrainz.org" || host.endsWith(".listenbrainz.org")
    }

    override suspend fun parse(url: String): Pair<String, Type>? {
        handlePrefix(url)?.let { return it to Type.ARTIST }

        val uri = getUri(url) ?: return null
        val host = uri.host?.lowercase() ?: ""

        if (host != "listenbrainz.org" && !host.endsWith(".listenbrainz.org")) return null

        val parts = uri.path.trim('/').split("/")
        if (parts.size < 2) return null

        val type = when (parts[0]) {
            "artist" -> Type.ARTIST
            "release-group" -> Type.ALBUM
            "playlist" -> Type.PLAYLIST
            else -> return null
        }

        val id = parts[1]

        return id to type
    }
}
