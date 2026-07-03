package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object SongArtistTable : Table("songArtist") {
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val artistId = reference("artistId", ArtistTable.id, onDelete = ReferenceOption.CASCADE)
    val creditedAliasId = reference("creditedAliasId", ArtistAliasTable.id, onDelete = ReferenceOption.SET_NULL).nullable()

    override val primaryKey = PrimaryKey(songId, artistId)
}