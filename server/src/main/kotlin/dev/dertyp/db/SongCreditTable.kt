package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object PersonTable : UUIDTable("person") {
    val name = text("name").uniqueIndex()
}

object SongComposerTable : Table("song_composer") {
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val personId = reference("personId", PersonTable.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(songId, personId)
}

object SongLyricistTable : Table("song_lyricist") {
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val personId = reference("personId", PersonTable.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(songId, personId)
}

object SongProducerTable : Table("song_producer") {
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val personId = reference("personId", PersonTable.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(songId, personId)
}
