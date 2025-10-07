package dev.dertyp.repos

import dev.dertyp.data.Song

interface SongRepository {
    fun byId(id: String): Song?
    fun byTitle(title: String): List<Song>
    fun byArtist(artistId: String): List<Song>
    fun byAlbum(albumId: String): List<Song>

    fun allSongs(): List<Song>
}