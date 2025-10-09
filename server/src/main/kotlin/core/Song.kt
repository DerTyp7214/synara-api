package dev.dertyp.core

import dev.dertyp.data.Song
import dev.dertyp.data.SongWithoutLyrics
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.SongArtistTable
import dev.dertyp.db.SongTable
import org.jetbrains.exposed.sql.*

fun Song.omitLyrics(): SongWithoutLyrics = SongWithoutLyrics(
    id = this.id,
    title = this.title,
    artists = this.artists,
    album = this.album,
    duration = this.duration,
    releaseDate = this.releaseDate,
    path = this.path,
    originalUrl = this.originalUrl,
    trackNumber = this.trackNumber,
    discNumber = this.discNumber,
    copyright = this.copyright,
    sampleRate = this.sampleRate,
    bitsPerSample = this.bitsPerSample,
    bitRate = this.bitRate,
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