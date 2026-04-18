package dev.dertyp.core

import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.tag.FieldKey

enum class ArtistDelimiter(val delimiter: String) {
    Tdn(";"),
    Tiddl(";")
}

fun artistSplitter(input: String): List<String> = when {
    input.contains(ArtistDelimiter.Tdn.delimiter) -> input.split(ArtistDelimiter.Tdn.delimiter)
    else -> input.split(ArtistDelimiter.Tiddl.delimiter)
}.map { it.replace("\\p{Cf}".toRegex(), "").trim() }.filter { it.length > 1 }

val AudioFile.title: String?
    get() = tag.getFirst(FieldKey.TITLE)

val AudioFile.artists: List<String>
    get() {
        val artists = tag.getAll(FieldKey.ARTISTS)?.filterNotNull() ?: emptyList()
        return artists
            .ifEmpty { tag.getAll(FieldKey.ARTIST)?.filterNotNull() ?: emptyList() }
            .flatMap(::artistSplitter)
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
        return artists
            .ifEmpty { tag.getAll(FieldKey.ALBUM_ARTIST)?.filterNotNull() ?: emptyList() }
            .flatMap(::artistSplitter)
    }

val AudioFile.coverImage: ByteArray?
    get() = tag.firstArtwork?.binaryData