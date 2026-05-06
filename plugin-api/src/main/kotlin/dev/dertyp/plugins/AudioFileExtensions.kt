package dev.dertyp.plugins

import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.ID3v24Frame
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
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

val AudioFile.isExplicit: Boolean
    get() {
        val t = tag ?: return false
        val explicitTags = listOf("ADVISORY", "ITUNESADVISORY", "EXPLICIT", "CONTENTRATING", "KEYWORDS")

        return explicitTags.any { key ->
            val value = t.getFirst(key).lowercase()
            when (key) {
                "ADVISORY", "ITUNESADVISORY", "EXPLICIT" -> (value == "1") || (value == "true")
                "CONTENTRATING", "KEYWORDS" -> value.contains("explicit")
                else -> false
            }
        }
    }

fun AudioFile.setExplicit(explicit: Boolean) {
    val t = tag ?: return

    if (explicit) {
        t.setCustomField("ADVISORY", "1")
        t.setCustomField("ITUNESADVISORY", "1")
        t.setCustomField("EXPLICIT", "1")

        val keywords = t.getFirst("KEYWORDS")
        if (!keywords.lowercase().contains("explicit")) {
            val newKeywords = if (keywords.isBlank()) "explicit" else "$keywords, explicit"
            t.setCustomField("KEYWORDS", newKeywords)
        }
    } else {
        t.setCustomField("ADVISORY", "0")
        t.setCustomField("ITUNESADVISORY", "2")
        t.deleteCustomField("EXPLICIT")

        val keywords = t.getFirst("KEYWORDS")
        if (keywords.lowercase().contains("explicit")) {
            val newKeywords = keywords.split(",")
                .map { it.trim() }
                .filter { it.lowercase() != "explicit" }
                .joinToString(", ")
            if (newKeywords.isBlank()) t.deleteCustomField("KEYWORDS")
            else t.setCustomField("KEYWORDS", newKeywords)
        }
    }
}

fun Tag.setCustomField(key: String, value: String) {
    when (this) {
        is VorbisCommentTag -> setField(key, value)
        is FlacTag -> vorbisCommentTag.setField(key, value)
        is ID3v24Tag -> {
            val frame = ID3v24Frame("TXXX")
            frame.body = FrameBodyTXXX(0.toByte(), key, value)
            setField(frame)
        }
    }
}

fun Tag.deleteCustomField(key: String) {
    when (this) {
        is VorbisCommentTag -> deleteField(key)
        is FlacTag -> vorbisCommentTag.deleteField(key)
        is ID3v24Tag -> {
            val allTxxx = getFields("TXXX").filterIsInstance<ID3v24Frame>()
            val toKeep = allTxxx.filter {
                val body = it.body as? FrameBodyTXXX
                body?.description != key
            }
            if (allTxxx.size != toKeep.size) {
                deleteField("TXXX")
                toKeep.forEach { addField(it) }
            }
        }
    }
}

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
