package dev.dertyp.services.schedule

import dev.dertyp.AudioUtils
import dev.dertyp.AudioUtils.getSongsWithTranscodingInfo
import dev.dertyp.AudioUtils.insertTranscodedSong
import dev.dertyp.AudioUtils.transcodeAudio
import dev.dertyp.core.nullIfEmpty
import dev.dertyp.data.AudioFormat
import dev.dertyp.data.SimpleSong
import dev.dertyp.data.TranscodedVersion
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.inject
import java.io.File
import java.nio.file.Paths
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class AutoTranscodeWorker : Worker("AutoTranscodeWorker") {
    private val environment by inject<ApplicationEnvironment>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Int> {
        val opusQualities = environment.config.propertyOrNull("audio.autoTranscode")?.getString()
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it > 0 }
            ?.map { TranscodedVersion(it, AudioFormat.OPUS) }
            ?: emptyList()

        val aacQualities = environment.config.propertyOrNull("audio.autoTranscodeAac")?.getString()
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it > 0 }
            ?.map { TranscodedVersion(it, AudioFormat.AAC) }
            ?: emptyList()

        val qualities = (opusQualities + aacQualities).nullIfEmpty() ?: return emptyMap()

        val results = mutableMapOf<String, Int>()
        for (qualityVersion in qualities) {
            val (quality, format) = qualityVersion
            val songs = getSongsWithTranscodingInfo(listOf(qualityVersion))
            if (songs.isEmpty()) {
                logger.info("No songs to transcode for quality: $quality ($format)")
                results["quality_${format.name}_$quality"] = 0
                continue
            }

            logger.info("Auto transcoding ${songs.size} songs for quality: $quality ($format)")
            onProgress(0.0, "Auto transcoding ${songs.size} songs for quality: $quality ($format)")

            val transcodedSongs = mutableListOf<Triple<SimpleSong, File, TranscodedVersion>>()
            val transcodedSongsMutex = Mutex()

            if (!AudioUtils.isTranscoderActive.compareAndSet(expectedValue = false, newValue = true)) {
                logger.warn("Transcoding is already in progress, skipping quality: $quality ($format)")
                results["quality_${format.name}_$quality"] = 0
                continue
            }

            try {
                runParallel(
                    items = songs,
                    baseThreadCount = 6,
                    onItemProcessed = { processedCount ->
                        val progress = (processedCount.toDouble() / songs.size) * 100.0
                        onProgress(progress, "Transcoding quality $quality ($format): $processedCount/${songs.size} songs")
                    }
                ) { song ->
                    val file = Paths.get(song.path).toFile()
                    if (!file.exists()) {
                        logger.warn("Skipping auto transcode for \"${song.title}\": file not found at ${song.path}")
                    } else {
                        try {
                            val (newFile) = transcodeAudio(environment, file, quality, audioFormat = format)
                            transcodedSongsMutex.withLock {
                                transcodedSongs.add(Triple(song, newFile, qualityVersion))
                            }
                        } catch (e: Exception) {
                            logger.error("Failed to auto transcode \"${song.title}\": ${e.message}")
                        }
                    }
                }
                results["quality_${format.name}_$quality"] = transcodedSongs.size
            } finally {
                insertTranscodedSong(transcodedSongs)
                AudioUtils.isTranscoderActive.store(false)
            }
        }

        return results
    }
}
