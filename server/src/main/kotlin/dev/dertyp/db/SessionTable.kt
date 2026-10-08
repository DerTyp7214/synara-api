package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import java.time.Instant

object SessionTable : UUIDTable("session") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val isActive = bool("isActive").default(true)
    val lastActive = long("lastActive").clientDefault { Instant.now().toEpochMilli() }
    val userAgent = text("userAgent").default("")
    val ipAddress = text("ipAddress").default("")

    init {
        index(false, userId)
    }
}
