package dev.dertyp.services

import dev.dertyp.data.EntityType
import dev.dertyp.plugins.HookEvent
import dev.dertyp.plugins.HookEvent.AlbumsLinkedToMusicBrainz
import dev.dertyp.plugins.HookEvent.EntitiesCreated
import dev.dertyp.plugins.HookEvent.EntitiesDeleted
import dev.dertyp.plugins.HookEvent.EntitiesMerged
import dev.dertyp.plugins.HookEvent.EntitiesUpdated
import dev.dertyp.plugins.HookEvent.EntityMembersChanged
import dev.dertyp.plugins.HookEvent.LibraryIndexed
import dev.dertyp.plugins.HookEvent.LikesChanged
import dev.dertyp.plugins.HookEvent.TimecodesChanged
import org.jetbrains.exposed.v1.core.Key
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import java.util.UUID

internal class PendingHookEvents(
    private val key: Key<PendingHookEvents>,
    private val dispatch: (List<HookEvent>) -> Unit,
) : StatementInterceptor {
    private val batches = LinkedHashMap<HookEvent, MutableSet<UUID>>()
    private val vanished = HashMap<EntityType, MutableSet<UUID>>()
    private val absorbed = HashMap<EntityType, MutableSet<UUID>>()
    private val others = LinkedHashSet<HookEvent>()

    fun add(event: HookEvent): Unit = synchronized(this) {
        when (event) {
            is EntitiesCreated -> {
                vanished[event.type]?.removeAll(event.ids)
                ids(event.copy(ids = emptySet())) += event.ids
            }

            is EntitiesUpdated -> ids(event.copy(ids = emptySet())) += event.ids.filterNot {
                isCreated(event.type, it) || isGone(event.type, it)
            }

            is EntityMembersChanged -> ids(event.copy(ids = emptySet())) += event.ids.filterNot {
                isGone(event.type, it)
            }

            is EntitiesDeleted -> {
                val created = batches[EntitiesCreated(event.type, emptySet())]
                val merged = absorbed[event.type].orEmpty()
                val deleted = ids(event.copy(ids = emptySet()))
                for (id in event.ids) {
                    if (created?.remove(id) == true) {
                        vanished.getOrPut(event.type) { mutableSetOf() } += id
                    } else if (id !in merged) {
                        deleted += id
                    }
                }
                forget(event.type, event.ids)
            }

            is EntitiesMerged -> {
                ids(event.copy(removedIds = emptySet())) += event.removedIds
                absorbed.getOrPut(event.type) { mutableSetOf() } += event.removedIds
                batches[EntitiesCreated(event.type, emptySet())]?.removeAll(event.removedIds)
                batches[EntitiesDeleted(event.type, emptySet())]?.removeAll(event.removedIds)
                forget(event.type, event.removedIds)
            }

            is LikesChanged -> ids(event.copy(ids = emptySet())) += event.ids
            is TimecodesChanged -> ids(event.copy(songIds = emptySet())) += event.songIds
            is AlbumsLinkedToMusicBrainz -> ids(event.copy(albumIds = emptySet())) += event.albumIds
            else -> others += event
        }
    }

    override fun afterCommit(transaction: Transaction) {
        val events = synchronized(this) {
            val pending = batches.filterValues { it.isNotEmpty() }.map { (event, ids) -> event.withIds(ids) } + others
            clear()
            pending.sortedBy { event -> DISPATCH_ORDER.indexOf(event::class).takeIf { it >= 0 } ?: DISPATCH_ORDER.size }
        }
        if (events.isNotEmpty()) dispatch(events)
    }

    override fun afterRollback(transaction: Transaction): Unit = synchronized(this) { clear() }

    override fun keepUserDataInTransactionStoreOnCommit(userData: Map<Key<*>, Any?>): Map<Key<*>, Any?> =
        mapOf(key to this)

    private fun clear() {
        batches.clear()
        vanished.clear()
        absorbed.clear()
        others.clear()
    }

    private fun ids(group: HookEvent): MutableSet<UUID> = batches.getOrPut(group) { LinkedHashSet() }

    private fun forget(type: EntityType, ids: Set<UUID>) {
        batches[EntitiesUpdated(type, emptySet())]?.removeAll(ids)
        batches[EntityMembersChanged(type, emptySet())]?.removeAll(ids)
    }

    private fun isCreated(type: EntityType, id: UUID): Boolean =
        batches[EntitiesCreated(type, emptySet())]?.contains(id) == true

    private fun isGone(type: EntityType, id: UUID): Boolean =
        vanished[type]?.contains(id) == true ||
            absorbed[type]?.contains(id) == true ||
            (batches[EntitiesDeleted(type, emptySet())]?.contains(id) == true && !isCreated(type, id))

    private fun HookEvent.withIds(ids: Set<UUID>): HookEvent = when (this) {
        is EntitiesCreated -> copy(ids = ids)
        is EntitiesUpdated -> copy(ids = ids)
        is EntityMembersChanged -> copy(ids = ids)
        is EntitiesDeleted -> copy(ids = ids)
        is EntitiesMerged -> copy(removedIds = ids)
        is LikesChanged -> copy(ids = ids)
        is TimecodesChanged -> copy(songIds = ids)
        is AlbumsLinkedToMusicBrainz -> copy(albumIds = ids)
        else -> this
    }

    private companion object {
        val DISPATCH_ORDER = listOf(
            EntitiesDeleted::class,
            EntitiesMerged::class,
            EntitiesCreated::class,
            EntitiesUpdated::class,
            EntityMembersChanged::class,
            LikesChanged::class,
            TimecodesChanged::class,
            AlbumsLinkedToMusicBrainz::class,
            LibraryIndexed::class,
        )
    }
}
