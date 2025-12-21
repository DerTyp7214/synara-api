package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object UserPlaylistSongTable : Table("userPlaylistSong") {
    val playlistId = reference("playlistId", UserPlaylistTable.id, onDelete = ReferenceOption.CASCADE)
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val position = integer("position")

    override val primaryKey = PrimaryKey(playlistId, songId, position)
}