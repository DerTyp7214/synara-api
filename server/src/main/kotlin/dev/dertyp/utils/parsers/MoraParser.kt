package dev.dertyp.utils.parsers

import dev.dertyp.services.import.Type

class MoraParser : UrlParser() {
    override val name: String = "mora"

    override fun canHandle(url: String): Boolean {
        if (handlePrefix(url) != null) return true
        val host = getUri(url)?.host?.lowercase() ?: ""
        return host == "mora.jp"
    }

    override suspend fun parse(url: String): Pair<String, Type>? {
        if (!canHandle(url)) return null
        handlePrefix(url)?.let { return it to Type.ALBUM }

        val uri = getUri(url) ?: return null
        val pathParts = uri.path.trim('/').split("/")

        if (pathParts.contains("package")) {
            val packageIndex = pathParts.indexOf("package")
            if (packageIndex + 2 < pathParts.size) {
                return pathParts[packageIndex + 2] to Type.ALBUM
            }
        }

        if (pathParts.contains("track")) {
            val trackIndex = pathParts.indexOf("track")
            if (trackIndex + 2 < pathParts.size) {
                return pathParts[trackIndex + 2] to Type.SONG
            }
        }

        return null
    }
}
