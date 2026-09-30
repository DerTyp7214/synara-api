package dev.dertyp.db

import org.jetbrains.exposed.v1.core.Table
import java.util.UUID

object MBRelationProviderTable : Table("mb_relation_provider"), ProviderColumns {
    val ownerId = varchar("ownerId", 36)
        .transform({ UUID.fromString(it) }, { it.toString() })
        .index()
    override val provider = varchar("provider", 64)
    override val externalId = text("externalId").default("")
    override val type = varchar("type", 32).nullable()
    override val rawUrl = text("rawUrl")

    override val primaryKey = PrimaryKey(ownerId, provider, externalId)

    init {
        index(false, externalId, provider)
        index(false, rawUrl)
    }
}
