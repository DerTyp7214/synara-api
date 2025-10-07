package dev.dertyp.repos

import dev.dertyp.data.Song
import java.util.*

interface SongRepository {
    suspend fun byId(id: UUID): Song?
    suspend fun byTitle(title: String): List<Song>
    suspend fun byArtist(artistId: UUID): List<Song>
    suspend fun byAlbum(albumId: UUID): List<Song>

    suspend fun allSongs(): List<Song>
}