package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object PlaylistSongTable : Table("playlistSong") {
    val playlistId = reference("playlistId", PlaylistTable.id, onDelete = ReferenceOption.CASCADE)
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val position = integer("position")

    override val primaryKey = PrimaryKey(playlistId, songId)
}