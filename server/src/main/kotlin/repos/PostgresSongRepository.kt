package dev.dertyp.repos

import dev.dertyp.data.Song
import dev.dertyp.suspendTransaction
import java.util.*

class PostgresSongRepository: SongRepository {
    override suspend fun allSongs(): List<Song> = suspendTransaction {
        listOf()
    }

    override suspend fun byId(id: UUID): Song? = suspendTransaction {
        null
    }

    override suspend fun byArtist(artistId: UUID): List<Song> = suspendTransaction {
        listOf()
    }

    override suspend fun byAlbum(albumId: UUID): List<Song> = suspendTransaction {
        listOf()
    }

    override suspend fun byTitle(title: String): List<Song> = suspendTransaction {
        listOf()
    }
}