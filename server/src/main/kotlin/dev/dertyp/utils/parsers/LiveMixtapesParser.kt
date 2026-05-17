package dev.dertyp.utils.parsers

import dev.dertyp.services.import.Type

class LiveMixtapesParser : UrlParser() {
    override val name: String = "livemixtapes"

    override fun canHandle(url: String): Boolean {
        if (handlePrefix(url) != null) return true
        val host = getUri(url)?.host?.lowercase() ?: ""
        return host.contains("livemixtapes.com")
    }

    override suspend fun parse(url: String): Pair<String, Type>? {
        if (!canHandle(url)) return null
        handlePrefix(url)?.let { return it to Type.ALBUM }

        val uri = getUri(url) ?: return null
        val pathParts = uri.path.trim('/').split("/")

        if (pathParts.contains("mixtapes") || pathParts.contains("download")) {
            val index = if (pathParts.contains("mixtapes")) pathParts.indexOf("mixtapes") else pathParts.indexOf("download")
            if (index + 1 < pathParts.size) {
                val id = pathParts[index + 1]
                if (id.all { it.isDigit() }) return id to Type.ALBUM
            }
        }

        return null
    }
}
