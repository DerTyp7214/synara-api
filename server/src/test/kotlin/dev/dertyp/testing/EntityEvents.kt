package dev.dertyp.testing

import dev.dertyp.plugins.HookBus
import dev.dertyp.services.DuplicateAlbumMergeTrigger
import dev.dertyp.services.EntityChangeRecorder
import dev.dertyp.services.EntityEventPublisher
import dev.dertyp.services.HookService
import dev.dertyp.services.HookSubscriber
import dev.dertyp.services.SearchIndexRemover
import dev.dertyp.services.Service
import dev.dertyp.services.VersionGroupTrigger
import kotlinx.coroutines.runBlocking
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.dsl.onClose

class RecordedEntityEvents(val hooks: HookService = HookService()) {
    val recorder = EntityChangeRecorder().also { it.subscribe(hooks) }
    val publisher = EntityEventPublisher(hooks)
    val versionGroupTrigger = VersionGroupTrigger()
    private val reactions = mutableListOf<Service>(versionGroupTrigger)

    fun <T> subscribe(reaction: T): T where T : Service, T : HookSubscriber {
        reaction.subscribe(hooks)
        if (reaction !in reactions) reactions += reaction
        return reaction
    }

    fun subscribeLibraryReactions(): RecordedEntityEvents {
        subscribe(versionGroupTrigger)
        subscribe(DuplicateAlbumMergeTrigger())
        subscribe(SearchIndexRemover())
        return this
    }

    suspend fun stop() {
        reactions.forEach { it.stopService() }
        hooks.stopService()
    }
}

fun entityEventsModule(events: RecordedEntityEvents = RecordedEntityEvents()): Module = module {
    single { events.hooks } bind HookBus::class
    single { events.recorder }
    single { events.publisher }
    single { events.versionGroupTrigger } onClose { runBlocking { it?.stopService() } }
}
