package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object SongAcoustIdTable : Table("song_acoustid") {
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val fingerprint = text("fingerprint")
    val duration = integer("duration")
    val acoustId = varchar("acoustId", 64).nullable()
    val score = double("score").nullable()
    val lastCheck = long("lastCheck").default(0L)

    override val primaryKey = PrimaryKey(songId)
}
