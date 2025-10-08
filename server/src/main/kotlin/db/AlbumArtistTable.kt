package dev.dertyp.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

object AlbumArtistTable : Table("albumArtist") {
    val albumId = reference("albumId", AlbumTable.id, onDelete = ReferenceOption.CASCADE)
    val artistId = reference("artistId", ArtistTable.id, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(albumId, artistId)
}