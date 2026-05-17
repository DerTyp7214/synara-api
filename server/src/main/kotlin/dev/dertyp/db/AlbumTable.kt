package dev.dertyp.db

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object AlbumTable : UUIDTable("album") {
    val name = text("name")
    val releaseDate = varchar("releaseDate", 128).nullable()
    val songCount = integer("songCount").default(0)
    val cover = reference("cover", ImageTable.id).nullable()
    val originalId = text("originalId").nullable()
    val lastMetadataCheck = long("lastMetadataCheck").default(0L)
    val lastProviderEnrichment = long("lastProviderEnrichment").default(0L)
}