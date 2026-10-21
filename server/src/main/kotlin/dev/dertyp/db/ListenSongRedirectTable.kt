package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object ListenSongRedirectTable : UUIDTable("listen_song_redirect") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val fromSongId = reference("fromSongId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val toSongId = reference("toSongId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = long("createdAt")

    init {
        uniqueIndex(userId, fromSongId)
        index(false, toSongId)
    }
}
