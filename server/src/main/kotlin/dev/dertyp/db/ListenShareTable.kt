package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import java.time.Instant

object ListenShareTable : UUIDTable("listen_share") {
    val userA = reference("userA", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val userB = reference("userB", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val useForRecommendations = bool("useForRecommendations").default(false)
    val createdAt = long("createdAt").clientDefault { Instant.now().toEpochMilli() }

    init {
        uniqueIndex(userA, userB)
        index(false, userB)
    }
}
