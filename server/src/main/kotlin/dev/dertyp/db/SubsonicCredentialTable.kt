package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

object SubsonicCredentialTable : Table("subsonicCredential") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val secret = varchar("secret", 64)
    val createdAt = long("createdAt").clientDefault { Instant.now().toEpochMilli() }

    override val primaryKey = PrimaryKey(userId)
}
