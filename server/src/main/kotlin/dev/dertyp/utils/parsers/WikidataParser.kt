package dev.dertyp.utils.parsers

import dev.dertyp.services.import.Type

class WikidataParser : UrlParser() {
    override val name: String = "wikidata"

    override fun canHandle(url: String): Boolean {
        if (handlePrefix(url) != null) return true
        val host = getUri(url)?.host?.lowercase() ?: ""
        return host == "wikidata.org" || host == "www.wikidata.org"
    }

    override suspend fun parse(url: String): Pair<String, Type>? {
        if (!canHandle(url)) return null
        handlePrefix(url)?.let { return it to Type.ALBUM }

        val uri = getUri(url) ?: return null
        val pathParts = uri.path.trim('/').split("/")

        val id = pathParts.lastOrNull()
        if (id != null && id.startsWith("Q") && id.drop(1).all { it.isDigit() }) {
            return id to Type.ALBUM
        }

        return null
    }
}
