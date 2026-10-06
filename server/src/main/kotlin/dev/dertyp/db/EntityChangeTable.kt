package dev.dertyp.db

import dev.dertyp.data.EntityChangeAspect
import dev.dertyp.data.EntityChangeKind
import dev.dertyp.data.EntityType
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID

abstract class EntityChangeRows(name: String) : UUIDTable(name) {
    val entityType = enumerationByName("entityType", 16, EntityType::class)
    val entityId = javaUUID("entityId")
    val aspect = enumerationByName("aspect", 16, EntityChangeAspect::class)
    val kind = enumerationByName("kind", 16, EntityChangeKind::class)
    val changedAt = long("changedAt")
}

object EntityChangeTable : EntityChangeRows("entity_change") {
    init {
        uniqueIndex(entityType, entityId, aspect)
        index(false, changedAt)
    }
}
