package dev.dertyp.utils.parsers

import dev.dertyp.services.import.Type
import java.net.URI

abstract class UrlParser {
    abstract val name: String
    open val alternativeNames: List<String> = emptyList()

    abstract fun canHandle(url: String): Boolean
    abstract suspend fun parse(url: String): Pair<String, Type>?

    protected fun getUri(url: String): URI? {
        return try {
            URI(url)
        } catch (_: Exception) {
            null
        }
    }

    protected fun handlePrefix(url: String): String? {
        val prefixes = (listOf(name) + alternativeNames).map { "$it:" }
        prefixes.forEach { if (url.startsWith(it, ignoreCase = true)) return url.removePrefix(it) }
        return null
    }
}
