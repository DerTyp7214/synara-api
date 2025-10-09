package dev.dertyp.core

import io.ktor.server.routing.*

fun RoutingCall.paging(): Pair<Int, Int> {
    val page = queryParameters["page"]?.toIntOrNull() ?: 0
    val pageSize = queryParameters["pageSize"]?.toIntOrNull() ?: 150

    return page to pageSize
}