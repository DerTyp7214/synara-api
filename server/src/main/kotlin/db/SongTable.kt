package dev.dertyp.db

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption

object SongTable : UUIDTable("song") {
    val title = text("title").default("")
    val albumId = reference("albumId", AlbumTable.id, onDelete = ReferenceOption.SET_NULL)
    val duration = long("duration").default(0L)
    val releaseDate = varchar("releaseDate", 128).nullable()
    val lyrics = text("lyrics").default("")
    val explicit = bool("explicit").default(false)
    val filePath = text("filePath").default("")
    val cover = reference("cover", ImageTable.id).nullable()
    val originalUrl = text("originalUrl").default("")
    val trackNumber = integer("trackNumber").default(1)
    val discNumber = integer("discNumber").default(1)
    val copyright = text("copyright").default("")
    val sampleRate = integer("sampleRate").default(0)
    val bitsPerSample = integer("bitsPerSample").default(0)
    val bitRate = long("bitRate").default(0)
    val fileSize = long("fileSize").default(0)
}