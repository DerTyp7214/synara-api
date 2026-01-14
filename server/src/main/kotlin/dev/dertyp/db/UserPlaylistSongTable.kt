package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant
import java.util.*

object UserPlaylistSongTable : Table("userPlaylistSong") {
    val playlistId = reference("playlistId", UserPlaylistTable.id, onDelete = ReferenceOption.CASCADE)
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val addedAt = long("addedAt").clientDefault { Instant.now().toEpochMilli() }
    val id = uuid("id").clientDefault { UUID.randomUUID() }

    override val primaryKey = PrimaryKey(playlistId, songId, addedAt, id)
}