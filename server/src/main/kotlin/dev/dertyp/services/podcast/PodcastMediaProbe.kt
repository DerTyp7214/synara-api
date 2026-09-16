package dev.dertyp.services.podcast

import dev.dertyp.plugins.coverImage
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object PodcastMediaProbe {
    val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav")

    private val SRT_CUE = Regex("""^\d+\s*\R\d\d:\d\d:\d\d,\d{3} -->""")

    data class Probe(
        val durationMs: Long? = null,
        val format: String? = null,
        val title: String? = null,
        val album: String? = null,
        val comment: String? = null,
        val date: Long? = null,
        val track: Int? = null,
        val artwork: ByteArray? = null,
        val lyrics: String? = null
    )

    fun probe(file: File): Probe {
        val tagged = runCatching {
            val audioFile = AudioFileIO.read(file)
            val header = audioFile.audioHeader
            val tag = audioFile.tag

            val durationMs = runCatching { header.preciseTrackLength.toDouble() }.getOrNull()
                ?.takeIf { it > 0.0 }
                ?.let { (it * 1000).toLong() }
                ?: runCatching { header.trackLength }.getOrNull()?.takeIf { it > 0 }?.let { it * 1000L }

            Probe(
                durationMs = durationMs,
                format = file.extension.lowercase().ifBlank { null },
                title = tag?.readField(FieldKey.TITLE),
                album = tag?.readField(FieldKey.ALBUM),
                comment = tag?.readField(FieldKey.COMMENT),
                date = tag?.readField(FieldKey.YEAR)?.let { parseTagDate(it) },
                track = tag?.readField(FieldKey.TRACK)?.trim()?.substringBefore('/')?.toIntOrNull(),
                artwork = runCatching { audioFile.coverImage }.getOrNull()?.takeIf { it.isNotEmpty() },
                lyrics = tag?.readField(FieldKey.LYRICS)
            )
        }.getOrElse { Probe(format = file.extension.lowercase().ifBlank { null }) }

        if (tagged.durationMs != null) return tagged

        val fallback = runCatching {
            FFmpegFrameGrabber(file.absolutePath).use { grabber ->
                grabber.start()
                grabber.lengthInTime.takeIf { it > 0 }?.let { it / 1000 }
            }
        }.getOrNull()

        return if (fallback != null) tagged.copy(durationMs = fallback) else tagged
    }

    fun transcriptTypeOf(text: String): String {
        val trimmed = text.trimStart()
        return when {
            trimmed.startsWith("WEBVTT") -> "text/vtt"
            SRT_CUE.containsMatchIn(trimmed) -> "application/srt"
            else -> "text/plain"
        }
    }

    private fun Tag.readField(key: FieldKey): String? =
        runCatching { getFirst(key) }.getOrNull()?.trim()?.ifBlank { null }

    private fun parseTagDate(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        runCatching {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }

        val year = text.take(4).toIntOrNull() ?: return null
        if (year < 1000 || year > 9999) return null

        return LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
}
