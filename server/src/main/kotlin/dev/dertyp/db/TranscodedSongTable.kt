package dev.dertyp.db

import dev.dertyp.data.AudioFormat
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object TranscodedSongTable: Table("transcodedSong") {
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val bitrate = integer("bitrate")
    val format = enumerationByName("format", 10, AudioFormat::class).default(AudioFormat.OPUS)
    val path = text("path")
    val fileSize = long("fileSize").default(0L)

    override val primaryKey = PrimaryKey(songId, bitrate, format)
}