package dev.dertyp.db

import org.jetbrains.exposed.dao.id.UUIDTable

object PlaylistTable : UUIDTable("playlist") {
    val name = varchar("name", 255)
    val imageId = reference("imageId", ImageTable.id).nullable()
}