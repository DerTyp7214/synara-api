package dev.dertyp.repos

import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.data.Song

class FakeSongRepository : SongRepository {
    private val bonez = Artist(
        id = "testId",
        name = "Bonez MC",
        isGroup = false,
        imageUrl = "some Url"
    )

    private val songs = mutableListOf(
        Song(
            id = "testId",
            title = "Song title",
            artists = listOf(bonez),
            album = Album(
                id = "testId",
                name = "Palmen aus Plastik III",
                artists = listOf(bonez),
                coverUrl = "some Url",
                releaseDate = System.currentTimeMillis(),
                totalDuration = 10
            ),
            coverUrl = "some Url",
            duration = 60,
            releaseDate = System.currentTimeMillis(),
            path = "some Path"
        )
    )

    override fun allSongs(): List<Song> = songs

    override fun byId(id: String): Song? = songs.find { it.id == id }

    override fun byTitle(title: String): List<Song> = songs.filter { it.title.contains(title, true) }

    override fun byArtist(artistId: String): List<Song> =
        songs.filter { s -> s.artists.any { a -> a.id == artistId || a.artists.any { it.id == artistId } } }

    override fun byAlbum(albumId: String): List<Song> = songs.filter { it.album.id == albumId }
}