package dev.dertyp.db

import dev.dertyp.getISOFromDate
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import java.time.LocalDateTime
import java.util.*

object SongTable : UUIDTable("song") {
    val title = text("title")
    val albumId = long("albumId").nullable()
    val duration = long("duration").default(0L)
    val releaseDate = varchar("releaseDate", 128)
        .default(getISOFromDate(LocalDateTime.now()))
    val lyrics = text("lyrics").default("")
    val filePath = text("filePath")
}

class SongDAO(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<SongDAO>(SongTable)

    var title by SongTable.title
    var albumId by SongTable.albumId
    var duration by SongTable.duration
    var releaseDate by SongTable.releaseDate
    var lyrics by SongTable.lyrics
    var filePath by SongTable.filePath
}