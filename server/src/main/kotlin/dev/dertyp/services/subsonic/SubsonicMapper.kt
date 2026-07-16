package dev.dertyp.services.subsonic

import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.data.BaseSong
import dev.dertyp.data.UserPlaylist
import dev.dertyp.data.UserSong
import java.time.Instant
import java.time.ZoneOffset

private val EPOCH_ISO = Instant.EPOCH.toString()

private fun contentTypeFor(suffix: String): String? = when (suffix) {
    "flac" -> "audio/flac"
    "mp3" -> "audio/mpeg"
    "ogg", "oga", "opus" -> "audio/ogg"
    "m4a", "mp4", "aac" -> "audio/mp4"
    "wav" -> "audio/wav"
    else -> null
}

fun BaseSong.toChild(): Child {
    val suffix = path.substringAfterLast('.', "").lowercase().ifEmpty { null }
    val userSong = this as? UserSong
    return Child(
        id = id.trId(),
        parent = album?.id?.alId(),
        title = title,
        album = album?.name,
        artist = artists.joinToString(", ") { it.creditedName ?: it.name }.ifEmpty { null },
        track = trackNumber,
        year = releaseDate?.year,
        genre = genres.firstOrNull()?.name,
        coverArt = coverId?.imId(),
        size = fileSize.takeIf { it > 0 },
        contentType = suffix?.let(::contentTypeFor),
        suffix = suffix,
        duration = duration / 1000,
        bitRate = (bitRate / 1000).toInt().takeIf { it > 0 },
        samplingRate = sampleRate.takeIf { it > 0 },
        bitDepth = bitsPerSample.takeIf { it > 0 },
        discNumber = discNumber,
        starred = if (userSong?.isFavourite == true) {
            userSong.userSongCreatedAt?.toInstant()?.toString() ?: EPOCH_ISO
        } else null,
        albumId = album?.id?.alId(),
        artistId = artists.firstOrNull()?.id?.arId(),
        musicBrainzId = musicBrainzId?.toString(),
        genres = genres.map { ItemGenre(it.name) }.ifEmpty { null },
    )
}

fun Album.toAlbumID3(starred: String? = null): AlbumID3 = AlbumID3(
    id = id.alId(),
    name = name,
    artist = artists.joinToString(", ") { it.creditedName ?: it.name }.ifEmpty { null },
    artistId = artists.firstOrNull()?.id?.arId(),
    coverArt = coverId?.imId(),
    songCount = songCount,
    duration = totalDuration / 1000,
    created = releaseDate?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toString() ?: EPOCH_ISO,
    starred = starred,
    year = releaseDate?.year,
    genre = genres.firstOrNull()?.name,
    musicBrainzId = musicbrainzId?.toString(),
    genres = genres.map { ItemGenre(it.name) }.ifEmpty { null },
)

fun Artist.toArtistID3(albumCount: Int? = null): ArtistID3 = ArtistID3(
    id = id.arId(),
    name = name,
    coverArt = imageId?.imId(),
    albumCount = albumCount,
    starred = if (isFollowed) EPOCH_ISO else null,
    musicBrainzId = musicbrainzId?.toString(),
)

fun UserPlaylist.toPlaylistDto(owner: String?, songCount: Int, durationMs: Long): PlaylistDto = PlaylistDto(
    id = id.plId(),
    name = name,
    comment = description.ifEmpty { null },
    owner = owner,
    songCount = songCount,
    duration = durationMs.coerceAtLeast(0) / 1000,
    created = modifiedAt?.toInstant()?.toString() ?: EPOCH_ISO,
    changed = modifiedAt?.toInstant()?.toString() ?: EPOCH_ISO,
    coverArt = imageId?.imId(),
)
