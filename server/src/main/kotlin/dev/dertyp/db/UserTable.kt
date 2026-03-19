package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object UserTable: UUIDTable("user") {
    val username = varchar("username", 255).uniqueIndex()
    val displayName = varchar("displayName", 255).nullable()
    val passwordHash = varchar("passwordHash", 255)
    val isAdmin = bool("isAdmin").default(false)
    val profileImage = reference("profileImageId", ImageTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
}