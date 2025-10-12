package dev.dertyp.core

import dev.dertyp.data.InsertableSong
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.Song
import dev.dertyp.data.SongWithoutLyrics
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.SongArtistTable
import dev.dertyp.db.SongTable
import org.jetbrains.exposed.sql.*

fun PaginatedResponse<Song>.omitLyrics() = PaginatedResponse(
    data = data.map { it.omitLyrics() },
    page = page,
    pageSize = pageSize,
    hasNextPage = hasNextPage,
)

fun Song.omitLyrics(): SongWithoutLyrics = SongWithoutLyrics(
    id = this.id,
    title = this.title,
    artists = this.artists,
    album = this.album,
    duration = this.duration,
    explicit = this.explicit,
    releaseDate = this.releaseDate,
    path = this.path,
    originalUrl = this.originalUrl,
    trackNumber = this.trackNumber,
    discNumber = this.discNumber,
    copyright = this.copyright,
    sampleRate = this.sampleRate,
    bitsPerSample = this.bitsPerSample,
    bitRate = this.bitRate,
    fileSize = this.fileSize,
    coverId = this.coverId,
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