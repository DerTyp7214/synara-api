package dev.dertyp.db

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object ImageTable : UUIDTable("image") {
    val path = text("path")
    val imageHash = varchar("hash", 255)
    val origin = text("origin")
}