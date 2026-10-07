package dev.dertyp.services

import dev.dertyp.data.EntityType
import dev.dertyp.plugins.HookEvent
import java.util.UUID

class EntityEventPublisher(private val hooks: HookService) {
    fun created(type: EntityType, ids: Collection<UUID>) {
        val entities = ids.toSet()
        if (entities.isEmpty()) return
        for (subscriber in hooks.inTransactionSubscribers) subscriber.created(type, entities)
        hooks.publish(HookEvent.EntitiesCreated(type, entities))
    }

    fun updated(type: EntityType, ids: Collection<UUID>, containersChanged: Boolean = false) {
        val entities = ids.toSet()
        if (entities.isEmpty()) return
        for (subscriber in hooks.inTransactionSubscribers) subscriber.updated(type, entities, containersChanged)
        hooks.publish(HookEvent.EntitiesUpdated(type, entities))
    }

    internal fun relinked(type: EntityType, ids: Collection<UUID>) {
        val entities = ids.toSet()
        if (entities.isEmpty()) return
        for (subscriber in hooks.inTransactionSubscribers) subscriber.relinked(type, entities)
        hooks.publish(HookEvent.EntitiesUpdated(type, entities))
    }

    fun leavingContainers(type: EntityType, ids: Collection<UUID>) {
        val entities = ids.toSet()
        if (entities.isEmpty()) return
        for (subscriber in hooks.inTransactionSubscribers) subscriber.leavingContainers(type, entities)
    }

    fun membersChanged(type: EntityType, ids: Collection<UUID>) {
        val entities = ids.toSet()
        if (entities.isEmpty()) return
        for (subscriber in hooks.inTransactionSubscribers) subscriber.membersChanged(type, entities)
        hooks.publish(HookEvent.EntityMembersChanged(type, entities))
    }

    fun deleting(type: EntityType, ids: Collection<UUID>) {
        val entities = ids.toSet()
        if (entities.isEmpty()) return
        for (subscriber in hooks.inTransactionSubscribers) subscriber.deleting(type, entities)
        hooks.publish(HookEvent.EntitiesDeleted(type, entities))
    }

    fun merging(type: EntityType, keptId: UUID, removedIds: Collection<UUID>) {
        val removed = removedIds.toSet() - keptId
        if (removed.isEmpty()) return
        for (subscriber in hooks.inTransactionSubscribers) subscriber.merging(type, keptId, removed)
        hooks.publish(HookEvent.EntitiesMerged(type, keptId, removed))
    }

    fun likesChanged(userId: UUID, type: EntityType, ids: Collection<UUID>) {
        val entities = ids.toSet()
        if (entities.isEmpty()) return
        for (subscriber in hooks.inTransactionSubscribers) subscriber.likesChanged(userId, type, entities)
        hooks.publish(HookEvent.LikesChanged(userId, type, entities))
    }

    fun timecodesChanged(userId: UUID, songIds: Collection<UUID>) {
        val songs = songIds.toSet()
        if (songs.isEmpty()) return
        for (subscriber in hooks.inTransactionSubscribers) subscriber.timecodesChanged(userId, songs)
        hooks.publish(HookEvent.TimecodesChanged(userId, songs))
    }

    fun albumsLinkedToMusicBrainz(albumIds: Collection<UUID>) {
        val albums = albumIds.toSet()
        if (albums.isEmpty()) return
        hooks.publish(HookEvent.AlbumsLinkedToMusicBrainz(albums))
    }
}
