package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object SyncServiceTable : Table("syncService") {
    val name = varchar("name", 255)
    val ownerId = reference("ownerId", UserTable.id, onDelete = ReferenceOption.SET_NULL)
    val scope = text("scope")
    val accessToken = text("accessToken")
    val refreshToken = text("refreshToken")
    val expiresIn = integer("expiresIn")
    val tokenType = text("token_type")
    val userId = long("userId")
    val createdAt = long("createdAt")

    override val primaryKey = PrimaryKey(name, ownerId)
}