package dev.dertyp.core

import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.tag.FieldKey

fun artistSplitter(input: String): List<String> = when {
    else -> input.split(";")
    //else -> input.split(",")
}.map { it.replace("\\p{Cf}".toRegex(), "").trim() }
    .filter { it.length > 1 }

val AudioFile.title: String?
    get() = tag.getFirst(FieldKey.TITLE)

val AudioFile.artists: List<String>
    get() {
        val artists = tag.getAll(FieldKey.ARTISTS)?.filterNotNull() ?: emptyList()
        return artists.ifEmpty { tag.getAll(FieldKey.ARTIST)?.filterNotNull() ?: emptyList() }
            .map(::artistSplitter)
            .flatten()
    }

val AudioFile.year: String?
    get() = tag.getFirst(FieldKey.YEAR)

val AudioFile.album: String?
    get() = tag.getFirst(FieldKey.ALBUM)

val AudioFile.songCount: Int?
    get() = tag.getFirst(FieldKey.TRACK_TOTAL)?.toIntOrNull()

val AudioFile.albumArtists: List<String>
    get() {
        val artists = tag.getAll(FieldKey.ALBUM_ARTISTS)?.filterNotNull() ?: emptyList()
        return artists.ifEmpty { tag.getAll(FieldKey.ALBUM_ARTIST)?.filterNotNull() ?: emptyList() }
            .map(::artistSplitter)
            .flatten()
    }

val AudioFile.coverImage: ByteArray?
    get() = tag.firstArtwork.binaryData