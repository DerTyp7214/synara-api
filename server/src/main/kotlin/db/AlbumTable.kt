package dev.dertyp.db

import dev.dertyp.getISOFromDate
import org.jetbrains.exposed.dao.id.UUIDTable
import java.time.LocalDateTime

object AlbumTable : UUIDTable("album") {
    val name = text("name")
    val releaseDate = varchar("releaseDate", 128)
        .default(getISOFromDate(LocalDateTime.now()))
}