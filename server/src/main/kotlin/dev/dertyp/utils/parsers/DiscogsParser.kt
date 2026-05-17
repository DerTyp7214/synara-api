package dev.dertyp.utils.parsers

import dev.dertyp.services.import.Type

class DiscogsParser : UrlParser() {
    override val name: String = "discogs"

    override fun canHandle(url: String): Boolean {
        if (handlePrefix(url) != null) return true
        val host = getUri(url)?.host?.lowercase() ?: ""
        return host == "discogs.com" || host.endsWith(".discogs.com")
    }

    override suspend fun parse(url: String): Pair<String, Type>? {
        if (!canHandle(url)) return null
        handlePrefix(url)?.let { return it to Type.ALBUM }

        val uri = getUri(url) ?: return null
        val pathParts = uri.path.trim('/').split("/")

        val releaseIndex = pathParts.indexOf("release")
        if (releaseIndex != -1 && releaseIndex + 1 < pathParts.size) {
            val id = pathParts[releaseIndex + 1].substringBefore("-")
            if (id.all { it.isDigit() }) return id to Type.ALBUM
        }

        val masterIndex = pathParts.indexOf("master")
        if (masterIndex != -1 && masterIndex + 1 < pathParts.size) {
            val id = pathParts[masterIndex + 1].substringBefore("-")
            if (id.all { it.isDigit() }) return id to Type.ALBUM
        }

        return null
    }
}
