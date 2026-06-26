package dev.dertyp.db

import org.jetbrains.exposed.v1.core.Table
import java.util.UUID

object MBRelationProviderTable : Table("mb_relation_provider") {
    val ownerId = varchar("ownerId", 36)
        .transform({ UUID.fromString(it) }, { it.toString() })
        .index()
    val provider = varchar("provider", 64)
    val externalId = text("externalId").default("")
    val type = varchar("type", 32).nullable()
    val rawUrl = text("rawUrl")

    override val primaryKey = PrimaryKey(ownerId, provider, externalId)
}
