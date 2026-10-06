package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption

object UserEntityChangeTable : EntityChangeRows("user_entity_change") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)

    init {
        uniqueIndex(userId, entityType, entityId, aspect)
        index(false, userId, changedAt)
        index(false, changedAt)
        index(false, entityType, entityId)
    }
}
