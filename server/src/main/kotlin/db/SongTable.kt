package dev.dertyp.db

import dev.dertyp.getISOFromDate
import org.jetbrains.exposed.dao.id.UUIDTable
import java.time.LocalDateTime

object SongTable : UUIDTable("song") {
    val title = text("title")
    val albumId = reference("albumId", AlbumTable.id)
    val duration = long("duration").default(0L)
    val releaseDate = varchar("releaseDate", 128)
        .default(getISOFromDate(LocalDateTime.now()))
    val lyrics = text("lyrics").default("")
    val filePath = text("filePath")
}