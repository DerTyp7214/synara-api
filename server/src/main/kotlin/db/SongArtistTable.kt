package dev.dertyp.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

object SongArtistTable : Table("songArtist") {
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val artistId = reference("artistId", ArtistTable.id, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(songId, artistId)
}