package dev.dertyp.services.cover

import dev.dertyp.data.CoverTarget
import dev.dertyp.data.CoverTargetType
import dev.dertyp.plugins.HookBus
import dev.dertyp.plugins.HookEvent
import dev.dertyp.plugins.on
import dev.dertyp.services.Service
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.koin.core.component.inject
import java.util.concurrent.ConcurrentHashMap

class CoverAutoTrigger(
    private val service: CoverGenerationService,
    private val config: CoverConfig,
) : Service() {
    private val hooks by inject<HookBus>()
    override val scopeDispatcher: CoroutineDispatcher get() = Dispatchers.Default
    private val pending = ConcurrentHashMap<CoverTarget, Job>()

    override suspend fun startService() {
        if (!config.autoGenerate) return
        hooks.on<HookEvent.PlaylistChanged> { schedule(CoverTarget(CoverTargetType.PLAYLIST, it.playlistId)) }
        hooks.on<HookEvent.CollectionChanged> { schedule(CoverTarget(CoverTargetType.COLLECTION, it.collectionId)) }
    }

    fun schedule(target: CoverTarget) {
        pending.compute(target) { _, previous ->
            previous?.cancel()
            scope.launch {
                delay(config.debounce)
                if (!pending.remove(target, coroutineContext.job)) return@launch
                val row = service.row(target) ?: return@launch
                service.enqueueAuto(target, row.name, row.creator)
            }
        }
    }

    fun pendingCount(): Int = pending.size
}
