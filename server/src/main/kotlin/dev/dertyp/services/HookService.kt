package dev.dertyp.services

import dev.dertyp.plugins.HookBus
import dev.dertyp.plugins.HookEvent
import dev.dertyp.plugins.HookGroup
import dev.dertyp.plugins.HookRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.Key
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

class HookService : Service(), HookBus {
    override val scopeDispatcher: CoroutineDispatcher get() = Dispatchers.Default
    private val handlers = ConcurrentHashMap<KClass<out HookEvent>, CopyOnWriteArrayList<suspend (HookEvent) -> Unit>>()
    private val writeSubscribers = CopyOnWriteArrayList<EntityWriteSubscriber>()
    private val pendingKey = Key<PendingHookEvents>()
    private val pluginHooks = ConcurrentHashMap<String, PluginHooks>()

    internal val inTransactionSubscribers: List<EntityWriteSubscriber> get() = writeSubscribers

    override fun <E : HookEvent> on(type: KClass<E>, handler: suspend (E) -> Unit): HookRegistration {
        @Suppress("UNCHECKED_CAST")
        val erased = handler as suspend (HookEvent) -> Unit
        val list = handlers.getOrPut(type) { CopyOnWriteArrayList() }
        list.add(erased)
        return HookRegistration { list.remove(erased) }
    }

    fun inTransaction(subscriber: EntityWriteSubscriber): HookRegistration {
        writeSubscribers.add(subscriber)
        return HookRegistration { writeSubscribers.remove(subscriber) }
    }

    fun forPlugin(pluginId: String, groups: Set<HookGroup>): HookBus {
        val facade = PluginHooks(pluginId, groups, this)
        pluginHooks.put(pluginId, facade)?.close()
        return facade
    }

    fun pluginRegistrations(pluginId: String): List<KClass<out HookEvent>> =
        pluginHooks[pluginId]?.subscribedEvents.orEmpty()

    fun removePlugin(pluginId: String) {
        pluginHooks.remove(pluginId)?.close()
    }

    override suspend fun emit(event: HookEvent) {
        val list = handlers[event::class] ?: return
        for (handler in list) {
            scope.launch { deliver(handler, event) }
        }
    }

    fun publish(event: HookEvent) {
        val transaction = TransactionManager.currentOrNull()
        if (transaction == null) {
            dispatch(listOf(event))
            return
        }
        val pending = transaction.getUserData(pendingKey) ?: PendingHookEvents(pendingKey, ::dispatchCommitted).also {
            transaction.putUserData(pendingKey, it)
            transaction.registerInterceptor(it)
        }
        pending.add(event)
    }

    private fun dispatchCommitted(events: List<HookEvent>) {
        try {
            dispatch(events)
        } catch (e: Exception) {
            logger.error("Dispatching hook events after commit failed", e)
        }
    }

    private fun dispatch(events: List<HookEvent>) {
        val deliveries = LinkedHashMap<suspend (HookEvent) -> Unit, MutableList<HookEvent>>()
        for (event in events) {
            handlers[event::class]?.forEach { handler -> deliveries.getOrPut(handler) { mutableListOf() } += event }
        }
        for ((handler, received) in deliveries) {
            scope.launch {
                for (event in received) deliver(handler, event)
            }
        }
    }

    private suspend fun deliver(handler: suspend (HookEvent) -> Unit, event: HookEvent) {
        try {
            handler(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Hook handler failed for ${event::class.simpleName}", e)
        }
    }
}
