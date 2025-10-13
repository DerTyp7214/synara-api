package dev.dertyp.db

import org.jetbrains.exposed.dao.id.UUIDTable

object UserTable: UUIDTable("user") {
    val username = varchar("username", 255).uniqueIndex()
    val passwordHash = varchar("passwordHash", 255)
}