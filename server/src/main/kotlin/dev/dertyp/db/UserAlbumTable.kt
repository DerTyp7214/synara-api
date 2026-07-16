package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

object UserAlbumTable : Table("userAlbum") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val albumId = reference("albumId", AlbumTable.id, onDelete = ReferenceOption.CASCADE)

    val isFavourite = bool("favourite").default(false)

    val createdAt = long("createdAt").clientDefault { Instant.now().toEpochMilli() }
    val updatedAt = long("updatedAt").clientDefault { Instant.now().toEpochMilli() }

    override val primaryKey = PrimaryKey(userId, albumId)
}
