package dev.dertyp.db

import dev.dertyp.data.ReleaseType
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object RecentReleaseTable : Table("recent_release") {
    val releaseId = reference("releaseId", MBReleaseGroupTable.id, onDelete = ReferenceOption.CASCADE)
    val artistId = reference("artistId", ArtistTable.id, onDelete = ReferenceOption.CASCADE)
    val artistName = text("artistName").default("Unknown Artist")
    val title = text("title")
    val releaseDate = long("releaseDate").nullable()
    val type = enumerationByName<ReleaseType>("type", 50).default(ReleaseType.Unknown)
    val imageId = reference("imageId", ImageTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val links = text("links").default("[]")
    val albumId = reference("albumId", AlbumTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val lastImageFetch = long("last_image_fetch").nullable()

    override val primaryKey = PrimaryKey(releaseId)
}
