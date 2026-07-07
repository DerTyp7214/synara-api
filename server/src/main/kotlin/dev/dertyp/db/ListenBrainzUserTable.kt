package dev.dertyp.db

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import java.time.Instant

object ListenBrainzUserTable : UUIDTable("listenbrainz_user") {
    val username = varchar("username", 255).uniqueIndex()
    val token = text("token").nullable()
    val lastListenedAt = long("lastListenedAt").nullable()
    val lastSyncedAt = long("lastSyncedAt").nullable()
    val createdAt = long("createdAt").clientDefault { Instant.now().toEpochMilli() }
}
