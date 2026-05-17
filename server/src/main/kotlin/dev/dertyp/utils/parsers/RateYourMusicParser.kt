package dev.dertyp.utils.parsers

import dev.dertyp.services.import.Type

class RateYourMusicParser : UrlParser() {
    override val name: String = "rateyourmusic"
    override val alternativeNames: List<String> = listOf("rym")

    override fun canHandle(url: String): Boolean {
        if (handlePrefix(url) != null) return true
        val host = getUri(url)?.host?.lowercase() ?: ""
        return host == "rateyourmusic.com" || host == "sonemic.com"
    }

    override suspend fun parse(url: String): Pair<String, Type>? {
        if (!canHandle(url)) return null
        handlePrefix(url)?.let { return it to Type.ALBUM }

        val uri = getUri(url) ?: return null
        val pathParts = uri.path.trim('/').split("/")

        if (pathParts.size >= 4 && pathParts[0] == "release") {
            val type = when (pathParts[1]) {
                "album", "ep", "comp", "mixtape", "single" -> Type.ALBUM
                "video" -> Type.VIDEO
                else -> Type.ALBUM
            }
            return pathParts.subList(1, 4).joinToString("/") to type
        }

        return null
    }
}
