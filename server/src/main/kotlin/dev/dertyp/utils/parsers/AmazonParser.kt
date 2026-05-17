package dev.dertyp.utils.parsers

import dev.dertyp.services.import.Type

class AmazonParser : UrlParser() {
    override val name: String = "amazon"

    override fun canHandle(url: String): Boolean {
        if (handlePrefix(url) != null) return true
        val host = getUri(url)?.host?.lowercase() ?: ""
        return host.contains("amazon.")
    }

    override suspend fun parse(url: String): Pair<String, Type>? {
        if (!canHandle(url)) return null
        handlePrefix(url)?.let { return it to Type.ALBUM }

        val uri = getUri(url) ?: return null
        val queryParams = uri.query?.split("&")?.associate {
            val parts = it.split("=")
            parts[0] to parts.getOrNull(1)
        } ?: emptyMap()

        queryParams["trackAsin"]?.let { return it to Type.SONG }

        val pathParts = uri.path.trim('/').split("/")

        val dpIndex = pathParts.indexOf("dp")
        if (dpIndex != -1 && dpIndex + 1 < pathParts.size) {
            return pathParts[dpIndex + 1] to Type.ALBUM
        }

        val productIndex = pathParts.indexOf("product")
        if (productIndex != -1 && productIndex + 1 < pathParts.size) {
            return pathParts[productIndex + 1] to Type.ALBUM
        }

        val albumsIndex = pathParts.indexOf("albums")
        if (albumsIndex != -1 && albumsIndex + 1 < pathParts.size) {
            return pathParts[albumsIndex + 1] to Type.ALBUM
        }

        return null
    }
}
