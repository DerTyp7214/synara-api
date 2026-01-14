package dev.dertyp.db

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable

object RefreshTokenTable: UUIDTable(name = "refreshToken") {
    val tokenHash = varchar("tokenHash", 255).uniqueIndex()
    val userId = reference("userId", UserTable.id)
    val isRevoked = bool("isRevoked").default(false)
    val expiresAt = long("expiresAt")
}