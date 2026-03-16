package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object SongMusicBrainzTable : Table("song_musicbrainz") {
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val musicBrainzId = varchar("musicBrainzId", 36).nullable()
    val lastCheck = long("lastCheck").default(0L)

    override val primaryKey = PrimaryKey(songId)
}
