package dev.dertyp.services.recommendation

import dev.dertyp.plugins.HookEvent
import dev.dertyp.plugins.ISynaraPlugin
import dev.dertyp.plugins.PluginContext
import dev.dertyp.plugins.on
import dev.dertyp.services.RecommendationService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class RecommendationPlugin : ISynaraPlugin, KoinComponent {
    override val id: String = "recommendation"
    override val name: String = "Recommendation Engine"

    private val recommendationService: RecommendationService by inject()

    override fun init(context: PluginContext) {
        context.hooks.on<HookEvent.ListenIngested> { recommendationService.markDirty() }
        context.hooks.on<HookEvent.PlaylistChanged> { recommendationService.markDirty() }
    }
}
