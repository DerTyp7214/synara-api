package dev.dertyp.utils.parsers

import dev.dertyp.services.import.Type

class YoutubeParser : UrlParser() {
    override val name: String = "youtube"

    override fun canHandle(url: String): Boolean {
        if (handlePrefix(url) != null) return true
        val host = getUri(url)?.host?.lowercase() ?: ""
        return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    }

    override suspend fun parse(url: String): Pair<String, Type>? {
        handlePrefix(url)?.let { return it to Type.SONG }

        val uri = getUri(url) ?: return null
        val host = uri.host?.lowercase() ?: ""
        val query = uri.query ?: ""

        if (host == "youtu.be") {
            val id = uri.path.trim('/')
            if (id.isEmpty()) return null
            return id to Type.SONG
        }

        if (host == "youtube.com" || host.endsWith(".youtube.com")) {
            val params = query.split("&").associate {
                val parts = it.split("=")
                parts[0] to parts.getOrNull(1)
            }

            params["v"]?.let { return it to Type.SONG }
            params["list"]?.let { return it to Type.PLAYLIST }

            if (uri.path.startsWith("/shorts/")) {
                return uri.path.removePrefix("/shorts/").trim('/') to Type.SONG
            }

            if (uri.path.startsWith("/channel/") || uri.path.startsWith("/user/") || uri.path.startsWith("/@")) {
                return uri.path.trim('/') to Type.ARTIST
            }
        }

        return null
    }
}
