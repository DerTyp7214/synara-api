package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object GenreTable : UUIDTable("genre") {
    val name = varchar("name", 255).uniqueIndex()
}

object ArtistGenreTable : Table("artist_genre") {
    val artistId = reference("artistId", ArtistTable.id, onDelete = ReferenceOption.CASCADE)
    val genreId = reference("genreId", GenreTable.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(artistId, genreId)
}

object SongGenreTable : Table("song_genre") {
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val genreId = reference("genreId", GenreTable.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(songId, genreId)
}

object AlbumGenreTable : Table("album_genre") {
    val albumId = reference("albumId", AlbumTable.id, onDelete = ReferenceOption.CASCADE)
    val genreId = reference("genreId", GenreTable.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(albumId, genreId)
}
