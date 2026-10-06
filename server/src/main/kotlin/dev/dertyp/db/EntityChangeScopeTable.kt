package dev.dertyp.db

import dev.dertyp.data.EntityType
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

object EntityChangeScopeTable : Table("entity_change_scope") {
    val changeId = reference("changeId", EntityChangeTable.id, onDelete = ReferenceOption.CASCADE)
    val scopeType = enumerationByName("scopeType", 16, EntityType::class)
    val scopeId = javaUUID("scopeId")

    override val primaryKey = PrimaryKey(changeId, scopeType, scopeId)

    init {
        index(false, scopeType, scopeId)
    }
}
