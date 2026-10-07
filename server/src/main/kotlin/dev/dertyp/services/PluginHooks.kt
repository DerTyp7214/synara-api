package dev.dertyp.services

import dev.dertyp.data.EntityType
import dev.dertyp.plugins.HookBus
import dev.dertyp.plugins.HookEvent
import dev.dertyp.plugins.HookGroup
import dev.dertyp.plugins.HookRegistration
import dev.dertyp.plugins.hookGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

internal class PluginHooks(
    val pluginId: String,
    val groups: Set<HookGroup>,
    private val hooks: HookService,
) : HookBus {
    private class Subscription(val type: KClass<out HookEvent>) {
        @Volatile
        var registration: HookRegistration? = null
    }

    private val subscriptions = CopyOnWriteArrayList<Subscription>()

    @Volatile
    private var closed = false

    val subscribedEvents: List<KClass<out HookEvent>> get() = subscriptions.map { it.type }

    override fun <E : HookEvent> on(type: KClass<E>, handler: suspend (E) -> Unit): HookRegistration {
        check(!closed) { "Plugin $pluginId was removed and can no longer subscribe to hooks" }
        val possible = HOOK_GROUPS_BY_EVENT[type]
        requireNotNull(possible) { "Plugin $pluginId subscribes to ${type.simpleName}, which is not a hook event" }
        require(possible.isNotEmpty()) {
            "Plugin $pluginId subscribes to ${type.simpleName}, which is not available to plugins"
        }
        require(possible.any { it in groups }) {
            "Plugin $pluginId subscribes to ${type.simpleName} without declaring hook group ${possible.joinToString(" or ")}"
        }
        val subscription = Subscription(type)
        subscriptions += subscription
        subscription.registration = hooks.on(type) { event ->
            if (subscription in subscriptions && event.hookGroup() in groups) deliver(handler, event)
        }
        if (closed) cancel(subscription)
        return HookRegistration { cancel(subscription) }
    }

    override suspend fun emit(event: HookEvent): Unit =
        throw UnsupportedOperationException("Plugin $pluginId cannot emit ${event::class.simpleName}, hook events are announced by the server only")

    fun close() {
        closed = true
        subscriptions.forEach(::cancel)
    }

    private fun cancel(subscription: Subscription) {
        subscriptions.remove(subscription)
        subscription.registration?.cancel()
    }

    private suspend fun <E : HookEvent> deliver(handler: suspend (E) -> Unit, event: E) {
        try {
            withContext(Dispatchers.IO) { handler(event) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hooks.logger.error("Hook handler of plugin $pluginId failed for ${event::class.simpleName}", e)
        }
    }

    companion object {
        private val NO_ID = UUID(0, 0)

        private val SAMPLES: List<HookEvent> = EntityType.entries.flatMap { type ->
            listOf(
                HookEvent.EntitiesCreated(type, emptySet()),
                HookEvent.EntitiesUpdated(type, emptySet()),
                HookEvent.EntityMembersChanged(type, emptySet()),
                HookEvent.EntitiesDeleted(type, emptySet()),
                HookEvent.EntitiesMerged(type, NO_ID, emptySet()),
            )
        } + listOf(
            HookEvent.AlbumsLinkedToMusicBrainz(emptySet()),
            HookEvent.LibraryIndexed,
            HookEvent.PlaylistChanged(NO_ID),
            HookEvent.CollectionChanged(NO_ID),
            HookEvent.LikesChanged(NO_ID, EntityType.SONG, emptySet()),
            HookEvent.TimecodesChanged(NO_ID, emptySet()),
            HookEvent.NowPlayingChanged(NO_ID, null, 0, 0),
            HookEvent.ListenIngested(NO_ID, 0),
        )

        val HOOK_GROUPS_BY_EVENT: Map<KClass<out HookEvent>, Set<HookGroup>> = SAMPLES
            .groupBy({ it::class }, { it.hookGroup() })
            .mapValues { (_, groups) -> groups.filterNotNull().toSet() }
    }
}
