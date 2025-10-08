package dev.dertyp.db

import org.jetbrains.exposed.sql.Table

object SongArtistTable : Table("songArtist") {
    val songId = reference("songId", SongTable.id)
    val artistId = reference("artistId", ArtistTable.id)

    override val primaryKey = PrimaryKey(songId, artistId)
}