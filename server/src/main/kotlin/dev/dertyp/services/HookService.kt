package dev.dertyp.services

import dev.dertyp.plugins.HookBus
import dev.dertyp.plugins.HookEvent
import dev.dertyp.plugins.HookRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

class HookService : Service(), HookBus {
    override val scopeDispatcher: CoroutineDispatcher get() = Dispatchers.Default
    private val handlers = ConcurrentHashMap<KClass<out HookEvent>, CopyOnWriteArrayList<suspend (HookEvent) -> Unit>>()

    override fun <E : HookEvent> on(type: KClass<E>, handler: suspend (E) -> Unit): HookRegistration {
        @Suppress("UNCHECKED_CAST")
        val erased = handler as suspend (HookEvent) -> Unit
        val list = handlers.getOrPut(type) { CopyOnWriteArrayList() }
        list.add(erased)
        return HookRegistration { list.remove(erased) }
    }

    override suspend fun emit(event: HookEvent) {
        val list = handlers[event::class] ?: return
        for (handler in list) {
            scope.launch {
                try {
                    handler(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Hook handler failed for ${event::class.simpleName}", e)
                }
            }
        }
    }
}
