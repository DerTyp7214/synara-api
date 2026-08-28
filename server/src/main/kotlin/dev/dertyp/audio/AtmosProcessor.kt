package dev.dertyp.audio

import dev.dertyp.AudioUtils
import dev.dertyp.plugins.atmosSibling
import io.ktor.util.logging.KtorSimpleLogger
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.absolutePathString
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

open class AtmosProcessor(private val audioConfig: AudioConfig) {
    private val logger = KtorSimpleLogger("AtmosProcessor")

    open fun isAtmos(path: Path): Boolean {
        if (!path.extension.equals("m4a", ignoreCase = true)) return false
        return runCatching {
            FFmpegFrameGrabber(path.absolutePathString()).use { grabber ->
                grabber.start()
                grabber.audioCodec == avcodec.AV_CODEC_ID_EAC3
            }
        }.getOrDefault(false)
    }

    open suspend fun process(m4a: Path, onLiveOutput: suspend (String) -> Unit): Path? {
        val target = audioConfig.losslessFormat
        val lossless = m4a.resolveSibling(m4a.nameWithoutExtension + "." + target.extension)
        val atmos = m4a.atmosSibling
        return try {
            AudioUtils.convertLossless(m4a.toFile(), lossless.toFile(), target)
            Files.move(m4a, atmos, StandardCopyOption.REPLACE_EXISTING)
            onLiveOutput("Dolby Atmos: ${m4a.name} -> ${lossless.name} + ${atmos.name}")
            lossless
        } catch (e: Exception) {
            logger.error("Dolby Atmos conversion failed for ${m4a.absolutePathString()}", e)
            onLiveOutput("Dolby Atmos conversion failed for ${m4a.absolutePathString()}: ${e.message}")
            null
        }
    }
}
