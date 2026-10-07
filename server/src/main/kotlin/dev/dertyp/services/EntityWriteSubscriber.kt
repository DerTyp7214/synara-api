package dev.dertyp.services

import dev.dertyp.data.EntityType
import java.util.UUID

interface EntityWriteSubscriber {
    fun created(type: EntityType, ids: Collection<UUID>) {}

    fun updated(type: EntityType, ids: Collection<UUID>, containersChanged: Boolean = false) {}

    fun relinked(type: EntityType, ids: Collection<UUID>) {}

    fun leavingContainers(type: EntityType, ids: Collection<UUID>) {}

    fun membersChanged(type: EntityType, ids: Collection<UUID>) {}

    fun deleting(type: EntityType, ids: Collection<UUID>) {}

    fun merging(type: EntityType, keptId: UUID, removedIds: Collection<UUID>) {}

    fun likesChanged(userId: UUID, type: EntityType, ids: Collection<UUID>) {}

    fun timecodesChanged(userId: UUID, songIds: Collection<UUID>) {}
}
