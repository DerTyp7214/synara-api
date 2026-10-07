package dev.dertyp.services

import dev.dertyp.config.VersionGroupConfig
import dev.dertyp.data.EntityType
import dev.dertyp.plugins.HookEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.koin.core.component.inject

class VersionGroupTrigger(private val config: VersionGroupConfig) : Service(), HookSubscriber {
    private val albumService by inject<AlbumService>()
    private val timers = Any()
    private var quiet: Job? = null
    private var deadline: Job? = null

    override fun subscribe(hooks: HookService) {
        val handler: suspend (HookEvent) -> Unit = ::handle
        hooks.on(HookEvent.EntitiesDeleted::class, handler)
        hooks.on(HookEvent.EntitiesMerged::class, handler)
        hooks.on(HookEvent.EntitiesCreated::class, handler)
        hooks.on(HookEvent.EntitiesUpdated::class, handler)
        hooks.on(HookEvent.AlbumsLinkedToMusicBrainz::class, handler)
    }

    fun requestRebuild(): Unit = synchronized(timers) {
        quiet?.cancel()
        quiet = scope.launch {
            delay(config.rebuildQuietPeriod)
            rebuildWhenDue(coroutineContext.job)
        }
        if (deadline == null) {
            deadline = scope.launch {
                delay(config.rebuildMaxWait)
                rebuildWhenDue(coroutineContext.job)
            }
        }
    }

    private suspend fun handle(event: HookEvent) {
        val albumsChanged = when (event) {
            is HookEvent.EntitiesDeleted -> event.type == EntityType.ALBUM
            is HookEvent.EntitiesMerged -> event.type == EntityType.ALBUM
            is HookEvent.EntitiesCreated -> event.type == EntityType.ALBUM
            is HookEvent.EntitiesUpdated -> event.type == EntityType.ALBUM
            is HookEvent.AlbumsLinkedToMusicBrainz -> true
            else -> false
        }
        if (albumsChanged) requestRebuild()
    }

    private fun rebuildWhenDue(timer: Job) {
        synchronized(timers) {
            if (timer !== quiet && timer !== deadline) return
            if (timer !== quiet) quiet?.cancel()
            if (timer !== deadline) deadline?.cancel()
            quiet = null
            deadline = null
        }
        scope.launch { albumService.rebuildVersionGroups() }
    }
}
