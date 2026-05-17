package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object ArtistMusicBrainzTable : Table("artist_musicbrainz") {
    val artistId = reference("artistId", ArtistTable.id, onDelete = ReferenceOption.CASCADE)
    val musicBrainzId = reference("musicBrainzId", MBArtistTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val lastCheck = long("lastCheck").default(0L)
    val lastReleaseCheck = long("lastReleaseCheck").default(0L)

    override val primaryKey = PrimaryKey(artistId)
}
