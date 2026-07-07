package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

object UserListenBrainzLinkTable : Table("user_listenbrainz_link") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val listenBrainzUserId = reference("listenBrainzUserId", ListenBrainzUserTable.id, onDelete = ReferenceOption.CASCADE)
    val enabled = bool("enabled").default(true)
    val linkedAt = long("linkedAt").clientDefault { Instant.now().toEpochMilli() }

    override val primaryKey = PrimaryKey(userId)
}
