package dev.dertyp.db

import org.jetbrains.exposed.dao.id.UUIDTable

object AlbumTable : UUIDTable("album") {
    val name = text("name")
    val releaseDate = varchar("releaseDate", 128).nullable()
    val songCount = integer("songCount").default(0)
    val cover = reference("cover", ImageTable.id).nullable()
}