package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object SongAudioEmbeddingTable : Table("song_audio_embedding") {
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val vector = binary("vector")
    val dim = integer("dim")
    val modelVersion = varchar("modelVersion", 64)
    val updatedAt = long("updatedAt")

    override val primaryKey = PrimaryKey(songId)
}
