package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object AlbumMusicBrainzTable : Table("album_musicbrainz") {
    val albumId = reference("albumId", AlbumTable.id, onDelete = ReferenceOption.CASCADE)
    val musicBrainzId = reference("musicBrainzId", MBReleaseTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val lastCheck = long("lastCheck").default(0L)

    override val primaryKey = PrimaryKey(albumId)
}
