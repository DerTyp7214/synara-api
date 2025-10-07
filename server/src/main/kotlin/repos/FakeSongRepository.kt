package dev.dertyp.repos

import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.data.Song
import java.time.LocalDateTime
import java.util.*

class FakeSongRepository : SongRepository {
    private val bonez = Artist(
        id = UUID.fromString("00000000-0000-4000-bb00-000000000187"),
        name = "Bonez MC",
        isGroup = false,
        imageUrl = "some Url"
    )

    private val songs = mutableListOf(
        Song(
            id = UUID.fromString("00000000-0000-4000-bb00-000000000001"),
            title = "Song title",
            artists = listOf(bonez),
            album = Album(
                id = UUID.fromString("00000000-0000-4000-bb00-000000000003"),
                name = "Palmen aus Plastik III",
                artists = listOf(bonez),
                coverUrl = "some Url",
                releaseDate = LocalDateTime.now(),
                totalDuration = 10
            ),
            coverUrl = "some Url",
            duration = 60,
            releaseDate = LocalDateTime.now(),
            path = "some Path"
        )
    )

    override suspend fun allSongs(): List<Song> = songs

    override suspend fun byId(id: UUID): Song? = songs.find { it.id == id }

    override suspend fun byTitle(title: String): List<Song> = songs.filter { it.title.contains(title, true) }

    override suspend fun byArtist(artistId: UUID): List<Song> =
        songs.filter { s -> s.artists.any { a -> a.id == artistId || a.artists.any { it.id == artistId } } }

    override suspend fun byAlbum(albumId: UUID): List<Song> = songs.filter { it.album.id == albumId }
}