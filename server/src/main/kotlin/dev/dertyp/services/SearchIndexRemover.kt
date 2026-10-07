package dev.dertyp.services

import dev.dertyp.data.EntityType
import dev.dertyp.db.SearchIndexEntityType
import dev.dertyp.plugins.HookEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.inject
import java.util.UUID

class SearchIndexRemover : Service(), HookSubscriber {
    private val redisSearchService by inject<RedisSearchService>()

    override fun subscribe(hooks: HookService) {
        val handler: suspend (HookEvent) -> Unit = ::handle
        hooks.on(HookEvent.EntitiesDeleted::class, handler)
        hooks.on(HookEvent.EntitiesMerged::class, handler)
    }

    private suspend fun handle(event: HookEvent) {
        when (event) {
            is HookEvent.EntitiesDeleted -> remove(event.type, event.ids)
            is HookEvent.EntitiesMerged -> remove(event.type, event.removedIds)
            else -> {}
        }
    }

    private suspend fun remove(type: EntityType, ids: Set<UUID>) {
        val indexed = when (type) {
            EntityType.SONG -> SearchIndexEntityType.SONG
            EntityType.ALBUM -> SearchIndexEntityType.ALBUM
            EntityType.ARTIST -> SearchIndexEntityType.ARTIST
            else -> return
        }
        if (!redisSearchService.isEnabled()) return
        withContext(Dispatchers.IO) { redisSearchService.remove(indexed, ids.toList()) }
    }
}
