package dev.dertyp.routing.rest

enum class RestParamSource { PATH, QUERY, BODY }

data class RestParamInfo(val name: String, val source: RestParamSource, val optional: Boolean)

enum class RestResponseKind { JSON, UNIT, BYTES, BYTE_FLOW, SSE, FILE }

data class RestRouteInfo(
    val servicePrefix: String,
    val method: String,
    val localPath: String,
    val interfaceName: String,
    val functionName: String,
    val public: Boolean,
    val params: List<RestParamInfo>,
    val response: RestResponseKind,
) {
    val canonicalPath: String
        get() = "/" + servicePrefix + (if (localPath.isEmpty()) "" else "/$localPath")

    fun render(): String {
        val renderedParams = params.joinToString(" ") {
            "${it.source.name.first().lowercase()}:${it.name}${if (it.optional) "?" else ""}"
        }
        val visibility = if (public) " PUBLIC" else ""
        return "$method $canonicalPath ${interfaceName.substringAfterLast('.')}.$functionName [$renderedParams] ${response.name}$visibility"
    }
}
