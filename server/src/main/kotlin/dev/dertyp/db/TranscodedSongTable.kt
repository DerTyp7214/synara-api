package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object TranscodedSongTable: Table("transcodedSong") {
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val bitrate = integer("bitrate")
    val path = text("path")

    override val primaryKey = PrimaryKey(songId, bitrate)
}