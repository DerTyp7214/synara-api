package dev.dertyp.db

import dev.dertyp.data.PlaylistAccess
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

object UserPlaylistShareTable : Table("userPlaylistShare") {
    val playlistId = reference("playlistId", UserPlaylistTable.id, onDelete = ReferenceOption.CASCADE)
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val access = enumerationByName("access", 16, PlaylistAccess::class)
    val createdAt = long("createdAt").clientDefault { Instant.now().toEpochMilli() }

    override val primaryKey = PrimaryKey(playlistId, userId)

    init {
        index(false, userId)
    }
}
