package dev.dertyp.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

enum class ProviderEnrichmentType {
    SONG, ALBUM
}

object ProviderEnrichmentCheckTable : Table("provider_enrichment_check") {
    val entityId = javaUUID("entityId")
    val provider = varchar("provider", 64)
    val type = enumerationByName("type", 16, ProviderEnrichmentType::class)
    val lastCheck = long("lastCheck")

    override val primaryKey = PrimaryKey(entityId, provider, type)
}
