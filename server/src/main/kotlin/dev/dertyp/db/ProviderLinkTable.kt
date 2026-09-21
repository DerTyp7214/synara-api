package dev.dertyp.db

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import java.time.Instant

object ProviderLinkTable : UUIDTable("provider_link") {
    val provider = varchar("provider", 64)
    val externalId = text("externalId").default("")
    val type = varchar("type", 32).nullable()
    val rawUrl = text("rawUrl")
    val addedAt = long("addedAt").clientDefault { Instant.now().toEpochMilli() }

    init {
        uniqueIndex(provider, externalId)
    }
}
