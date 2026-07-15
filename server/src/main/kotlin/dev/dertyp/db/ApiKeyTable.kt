package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import java.time.Instant

object ApiKeyTable : UUIDTable("apiKey") {
    val keyHash = varchar("keyHash", 64).uniqueIndex()
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val label = varchar("label", 255).default("")
    val createdAt = long("createdAt").clientDefault { Instant.now().toEpochMilli() }
    val lastUsed = long("lastUsed").nullable()
    val expiresAt = long("expiresAt").nullable()
    val isRevoked = bool("isRevoked").default(false)

    init {
        index(false, userId)
    }
}
