package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object RefreshTokenTable: UUIDTable(name = "refreshToken") {
    val tokenHash = varchar("tokenHash", 255).uniqueIndex()
    val userId = reference("userId", UserTable.id)
    val sessionId = reference("sessionId", SessionTable.id, onDelete = ReferenceOption.CASCADE).nullable()
    val isRevoked = bool("isRevoked").default(false)
    val expiresAt = long("expiresAt")
}