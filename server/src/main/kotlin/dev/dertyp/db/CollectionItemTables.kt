package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

object CollectionSongTable : Table("collectionSong") {
    val collectionId = reference("collectionId", CollectionTable.id, onDelete = ReferenceOption.CASCADE)
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val addedAt = long("addedAt").clientDefault { Instant.now().toEpochMilli() }

    override val primaryKey = PrimaryKey(collectionId, songId)
}

object CollectionAlbumTable : Table("collectionAlbum") {
    val collectionId = reference("collectionId", CollectionTable.id, onDelete = ReferenceOption.CASCADE)
    val albumId = reference("albumId", AlbumTable.id, onDelete = ReferenceOption.CASCADE)
    val addedAt = long("addedAt").clientDefault { Instant.now().toEpochMilli() }

    override val primaryKey = PrimaryKey(collectionId, albumId)
}

object CollectionArtistTable : Table("collectionArtist") {
    val collectionId = reference("collectionId", CollectionTable.id, onDelete = ReferenceOption.CASCADE)
    val artistId = reference("artistId", ArtistTable.id, onDelete = ReferenceOption.CASCADE)
    val addedAt = long("addedAt").clientDefault { Instant.now().toEpochMilli() }

    override val primaryKey = PrimaryKey(collectionId, artistId)
}

object CollectionPlaylistTable : Table("collectionPlaylist") {
    val collectionId = reference("collectionId", CollectionTable.id, onDelete = ReferenceOption.CASCADE)
    val playlistId = reference("playlistId", UserPlaylistTable.id, onDelete = ReferenceOption.CASCADE)
    val addedAt = long("addedAt").clientDefault { Instant.now().toEpochMilli() }

    override val primaryKey = PrimaryKey(collectionId, playlistId)
}
