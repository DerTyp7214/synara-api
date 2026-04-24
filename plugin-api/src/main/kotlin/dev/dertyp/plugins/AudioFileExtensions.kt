package dev.dertyp.plugins

import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.ID3v24Frame
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyWXXX
import org.jaudiotagger.tag.images.StandardArtwork
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

fun artistSplitter(input: String, delimiter: String = ";"): List<String> {
    return input.split(delimiter).map { it.replace("\\p{Cf}".toRegex(), "").trim() }.filter { it.length > 1 }
}

fun AudioFile.getArtists(delimiter: String = ";"): List<String> {
    val artists = tag.getAll(FieldKey.ARTISTS)?.filterNotNull() ?: emptyList()
    return artists
        .ifEmpty { tag.getAll(FieldKey.ARTIST)?.filterNotNull() ?: emptyList() }
        .flatMap { artistSplitter(it, delimiter) }
}

val AudioFile.title: String?
    get() = tag.getFirst(FieldKey.TITLE)

val AudioFile.year: String?
    get() = tag.getFirst(FieldKey.YEAR)

val AudioFile.album: String?
    get() = tag.getFirst(FieldKey.ALBUM)

val AudioFile.songCount: Int?
    get() = tag.getFirst(FieldKey.TRACK_TOTAL)?.toIntOrNull()

val AudioFile.musicBrainzTrackId: String?
    get() = tag.getFirst(FieldKey.MUSICBRAINZ_TRACK_ID)

val AudioFile.musicBrainzReleaseId: String?
    get() = tag.getFirst(FieldKey.MUSICBRAINZ_RELEASEID)

val AudioFile.musicBrainzArtistId: String?
    get() = tag.getFirst(FieldKey.MUSICBRAINZ_ARTISTID)

val AudioFile.originalUrl: String?
    get() {
        return when (val t = tag) {
            is VorbisCommentTag -> t.getFirst("URL")
            is FlacTag -> t.vorbisCommentTag.getFirst("URL")
            is ID3v24Tag -> t.getCustomField("URL")
            else -> null
        }
    }

fun ID3v24Tag.getCustomField(key: String): String? {
    val fields = getFields("WXXX")

    for (field in fields) {
        val frame = field as? ID3v24Frame
        val body = frame?.body as? FrameBodyWXXX

        if (body?.description == key) {
            return body.urlLink
        }
    }

    return null
}

fun ID3v24Tag.setCustomField(key: String, value: String) {
    val frame = ID3v24Frame("WXXX")
    frame.body = FrameBodyWXXX(0.toByte(), key, value)
    setField(frame)
}

fun AudioFile.setOriginalUrl(url: String) {
    when (val t = tag) {
        is VorbisCommentTag -> t.setField("URL", url)
        is FlacTag -> t.vorbisCommentTag.setField("URL", url)
        is ID3v24Tag -> t.setCustomField("URL", url)
    }
}

fun AudioFile.getAlbumArtists(delimiter: String = ";"): List<String> {
    val artists = tag.getAll(FieldKey.ALBUM_ARTISTS)?.filterNotNull() ?: emptyList()
    return artists
        .ifEmpty { tag.getAll(FieldKey.ALBUM_ARTIST)?.filterNotNull() ?: emptyList() }
        .flatMap { artistSplitter(it, delimiter) }
}

val AudioFile.coverImage: ByteArray?
    get() = tag.firstArtwork?.binaryData

fun AudioFile.setCoverImage(
    data: ByteArray,
    mimeType: String? = null,
    imageUrl: String? = null,
    width: Int? = null,
    height: Int? = null
) {
    tag.deleteArtworkField()
    val artwork = StandardArtwork()
    artwork.binaryData = data
    artwork.pictureType = 3

    if (mimeType != null) artwork.mimeType = mimeType
    if (imageUrl != null) artwork.imageUrl = imageUrl
    if (width != null) artwork.width = width
    if (height != null) artwork.height = height

    if (artwork.width <= 0 || artwork.height <= 0 || artwork.mimeType.isNullOrBlank()) {
        try {
            val bis = ByteArrayInputStream(artwork.binaryData)
            val stream = ImageIO.createImageInputStream(bis)
            val readers = ImageIO.getImageReaders(stream)
            if (readers.hasNext()) {
                val reader = readers.next()
                reader.input = stream
                if (artwork.width <= 0) artwork.width = reader.getWidth(0)
                if (artwork.height <= 0) artwork.height = reader.getHeight(0)
                if (artwork.mimeType.isNullOrBlank()) artwork.mimeType = "image/${reader.formatName.lowercase()}"
                reader.dispose()
            }
            stream.close()
        } catch (_: Exception) {
        }
    }

    tag.setField(artwork)
}
