package dev.dertyp.db

import org.jetbrains.exposed.v1.core.Table

object EntityChangeTrackingTable : Table("entity_change_tracking") {
    const val ROW_ID = 1

    val id = integer("id")
    val startedAt = long("startedAt")

    override val primaryKey = PrimaryKey(id)
}
