package dev.dertyp.services.subsonic

import dev.dertyp.plugins.ApiKeyScope
import dev.dertyp.plugins.IRoutePlugin
import dev.dertyp.plugins.ISynaraPlugin
import dev.dertyp.plugins.PluginContext
import io.ktor.server.routing.Route
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

class SubsonicPlugin : ISynaraPlugin, IRoutePlugin {
    override val id = "subsonic"
    override val name = "Subsonic API"

    override fun getKoinModule() = module {
        singleOf(::SubsonicAuthenticator)
        singleOf(::SubsonicQueryService)
    }

    override fun init(context: PluginContext) {
        context.apiKeyScopes.registerScope(SCOPE)
    }

    override fun registerRoutes(route: Route) {
        route.subsonicRouting()
    }

    companion object {
        val SCOPE = ApiKeyScope.Plugin(
            "subsonic",
            "subsonic",
            "Subsonic API",
            "Authenticate Subsonic/OpenSubsonic clients via the apiKey parameter.",
        )
    }
}
