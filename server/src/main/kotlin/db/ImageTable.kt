package dev.dertyp.db

import org.jetbrains.exposed.dao.id.UUIDTable

object ImageTable : UUIDTable("image") {
    val path = text("path")
    val imageHash = varchar("hash", 255)
}