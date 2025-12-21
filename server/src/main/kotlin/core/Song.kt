package dev.dertyp.core

import dev.dertyp.data.*
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.SongArtistTable
import dev.dertyp.db.SongTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select

@Suppress("UNCHECKED_CAST")
fun <T : BaseSong> PaginatedResponse<T>.omitLyrics() = PaginatedResponse(
    data = data.map {
        when (it) {
            is Song -> it.omitLyrics()
            is UserSong -> it.omitLyrics()
            else -> it
        }
    } as List<T>,
    page = page,
    pageSize = pageSize,
    hasNextPage = hasNextPage,
)

fun Song.omitLyrics(): Song = copy(
    lyrics = ""
)

fun UserSong.omitLyrics(): UserSong = copy(
    lyrics = ""
)

fun Query.withArtistNames(artistNames: List<String>): Query = this.andWhere {
    exists(
        SongArtistTable
            .innerJoin(
                ArtistTable,
                onColumn = { SongArtistTable.artistId },
                otherColumn = { ArtistTable.id },
            )
            .select(SongArtistTable.songId)
            .where {
                (SongArtistTable.songId eq SongTable.id) and
                        (ArtistTable.name inList artistNames)
            }
    )
}

fun InsertableSong.contentEquals(other: InsertableSong): Boolean {
    return title == other.title &&
            explicit == other.explicit &&
            trackNumber == other.trackNumber &&
            discNumber == other.discNumber &&
            duration == other.duration &&
            album.name == other.album.name &&
            releaseDate == other.releaseDate
}