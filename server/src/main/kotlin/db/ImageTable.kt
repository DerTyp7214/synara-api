package dev.dertyp.db

import org.jetbrains.exposed.dao.id.UUIDTable

object ImageTable : UUIDTable("image") {
    val data = blob("data")
    val imageHash = varchar("hash", 255)
}