package dev.dertyp.core

import dev.dertyp.data.Song
import dev.dertyp.data.SongWithoutLyrics

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