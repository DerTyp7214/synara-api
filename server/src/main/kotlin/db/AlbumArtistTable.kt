package dev.dertyp.db

import org.jetbrains.exposed.sql.Table

object AlbumArtistTable : Table("albumArtist") {
    val albumId = reference("albumId", AlbumTable.id)
    val artistId = reference("artistId", ArtistTable.id)

    override val primaryKey = PrimaryKey(albumId, artistId)
}