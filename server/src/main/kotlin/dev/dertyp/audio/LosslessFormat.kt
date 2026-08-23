package dev.dertyp.audio

import io.ktor.http.ContentType
import io.ktor.util.logging.KtorSimpleLogger
import java.io.File
import java.nio.file.Path
import kotlin.io.path.extension

enum class LosslessFormat(
    val extension: String,
    val contentType: ContentType,
    val ffmpegFormat: String,
) {
    FLAC("flac", ContentType("audio", "flac"), "flac"),
    WAV("wav", ContentType("audio", "wav"), "wav"),
    AIFF("aiff", ContentType("audio", "aiff"), "aiff");

    companion object {
        private val logger = KtorSimpleLogger("LosslessFormat")

        val extensions: Set<String> = setOf("flac", "wav", "aiff", "aif")

        fun fromExtension(extension: String): LosslessFormat? = when (extension.lowercase()) {
            "flac" -> FLAC
            "wav" -> WAV
            "aiff", "aif" -> AIFF
            else -> null
        }

        fun fromFfmpegFormat(format: String?): LosslessFormat? = when (format?.lowercase()) {
            "flac" -> FLAC
            "wav" -> WAV
            "aiff" -> AIFF
            else -> null
        }

        fun parse(value: String?): LosslessFormat {
            if (value.isNullOrBlank()) return FLAC
            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: run {
                logger.warn("Unknown lossless format '$value', falling back to FLAC. Valid values: ${entries.joinToString()}")
                FLAC
            }
        }
    }
}

val File.losslessFormat: LosslessFormat? get() = LosslessFormat.fromExtension(extension)
val File.isLossless: Boolean get() = losslessFormat != null
val Path.losslessFormat: LosslessFormat? get() = LosslessFormat.fromExtension(extension)
val Path.isLossless: Boolean get() = losslessFormat != null
