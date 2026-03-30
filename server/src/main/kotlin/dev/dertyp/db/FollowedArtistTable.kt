package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object FollowedArtistTable : Table("followed_artist") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val artistId = reference("artistId", ArtistTable.id, onDelete = ReferenceOption.CASCADE)
    val lastCheck = long("lastCheck").default(0L)

    override val primaryKey = PrimaryKey(userId, artistId)
}
