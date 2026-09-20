package dev.dertyp.db

import dev.dertyp.data.ReleaseType
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import java.time.Instant

object ProviderReleaseTable : UUIDTable("provider_release") {
    val provider = varchar("provider", 64)
    val externalId = text("externalId")
    val artistId = reference("artistId", ArtistTable.id, onDelete = ReferenceOption.CASCADE)
    val artistName = text("artistName").default("Unknown Artist")
    val title = text("title")
    val releaseDate = long("releaseDate").nullable()
    val type = enumerationByName<ReleaseType>("type", 50).default(ReleaseType.Unknown)
    val single = bool("single").default(false)
    val complete = bool("complete").default(true)
    val compilation = bool("compilation").default(false)
    val trackCount = integer("trackCount").nullable()
    val upc = varchar("upc", 128).nullable()
    val url = text("url").default("")
    val artworkUrl = text("artworkUrl").nullable()
    val imageId = reference("imageId", ImageTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val releaseGroupId = reference("releaseGroupId", MBReleaseGroupTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val albumId = reference("albumId", AlbumTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val lastImageFetch = long("last_image_fetch").nullable()
    val lastUpdate = long("last_update").nullable()
    val addedAt = long("addedAt").clientDefault { Instant.now().toEpochMilli() }

    init {
        uniqueIndex(provider, externalId)
        index(false, artistId, releaseDate)
        index(false, releaseGroupId)
    }
}
