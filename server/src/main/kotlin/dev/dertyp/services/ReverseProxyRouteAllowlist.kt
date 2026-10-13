package dev.dertyp.services

import dev.dertyp.routing.rest.GeneratedRestRoutes
import io.ktor.http.HttpMethod
import io.ktor.http.URLDecodeException
import io.ktor.http.decodeURLPart

object ReverseProxyRouteAllowlist {
    private val parameterSegment = Regex("""\{[^{}?.]+}""")

    private val templatesByMethod: Map<String, Map<Int, List<List<String?>>>> by lazy {
        val generated = GeneratedRestRoutes.manifest.map { it.method to it.canonicalPath }
        val handWritten = JwtService.HTTP_ROUTES.map { (method, path) -> method.value to path }
        (generated + handWritten)
            .groupBy({ it.first }, { templateOf(it.second) })
            .mapValues { (_, templates) -> templates.filterNotNull().distinct().groupBy { it.size } }
    }

    private val templatesOfAnyMethod: Map<Int, List<List<String?>>> by lazy {
        templatesByMethod.values
            .flatMap { bySize -> bySize.values.flatten() }
            .distinct()
            .groupBy { it.size }
    }

    fun isAllowed(method: HttpMethod, path: String): Boolean {
        val segments = segmentsOf(path) ?: return false
        val candidates = if (method == HttpMethod.Options) {
            templatesOfAnyMethod[segments.size]
        } else {
            templatesByMethod[method.value]?.get(segments.size)
        }
        return candidates.orEmpty().any { template ->
            template.indices.all { index -> template[index] == null || template[index] == segments[index] }
        }
    }

    private fun templateOf(path: String): List<String?>? {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.any { ('{' in it || '}' in it) && !parameterSegment.matches(it) }) return null
        return segments.map { if (parameterSegment.matches(it)) null else it }
    }

    private fun segmentsOf(path: String): List<String>? {
        if (!path.startsWith('/') || path.endsWith('/')) return null
        if (path.any { it == '?' || it == '#' }) return null
        val segments = try {
            path.split('/').filter { it.isNotEmpty() }.map { it.decodeURLPart() }
        } catch (_: URLDecodeException) {
            return null
        }
        return segments.takeUnless { decoded -> decoded.any { it == "." || it == ".." } }
    }
}
