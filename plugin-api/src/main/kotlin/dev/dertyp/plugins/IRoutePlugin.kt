package dev.dertyp.plugins

import io.ktor.server.routing.Route

interface IRoutePlugin {
    fun registerRoutes(route: Route)
}
