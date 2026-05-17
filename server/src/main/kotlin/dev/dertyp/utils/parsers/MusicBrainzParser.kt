package dev.dertyp.utils.parsers

import dev.dertyp.services.import.Type

class MusicBrainzParser : UrlParser() {
    override val name: String = "musicbrainz"

    override fun canHandle(url: String): Boolean {
        if (handlePrefix(url) != null) return true
        val host = getUri(url)?.host?.lowercase() ?: ""
        return host == "musicbrainz.org" || host.endsWith(".musicbrainz.org")
    }

    override suspend fun parse(url: String): Pair<String, Type>? {
        handlePrefix(url)?.let { return it to Type.SONG }

        val uri = getUri(url) ?: return null
        val host = uri.host?.lowercase() ?: ""

        if (host != "musicbrainz.org" && !host.endsWith(".musicbrainz.org")) return null

        val parts = uri.path.trim('/').split("/")
        if (parts.size < 2) return null

        val type = when (parts[0]) {
            "recording" -> Type.SONG
            "release" -> Type.ALBUM
            "release-group" -> Type.ALBUM
            "artist" -> Type.ARTIST
            "series" -> Type.PLAYLIST
            else -> return null
        }

        val id = parts[1]

        return id to type
    }
}
