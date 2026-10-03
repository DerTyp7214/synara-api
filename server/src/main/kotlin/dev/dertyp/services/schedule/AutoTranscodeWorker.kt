package dev.dertyp.services.schedule

import dev.dertyp.audio.TranscodedSongRepository
import dev.dertyp.audio.Transcoder
import dev.dertyp.core.nullIfEmpty
import dev.dertyp.data.AudioFormat
import dev.dertyp.data.SimpleSong
import dev.dertyp.data.TaskKeys
import dev.dertyp.data.TranscodedVersion
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.inject
import java.io.File
import java.nio.file.Paths
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
@WorkerTask(TaskKeys.AUTO_TRANSCODING, "Auto Transcoding", cron = "0 3 * * *")
class AutoTranscodeWorker : Worker("AutoTranscodeWorker") {
    private val environment by inject<ApplicationEnvironment>()
    private val transcoder by inject<Transcoder>()
    private val transcodedSongRepository by inject<TranscodedSongRepository>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        val opusQualities = serverConfig.transcode.autoOpusQualities.map { TranscodedVersion(it, AudioFormat.OPUS) }
        val aacQualities = serverConfig.transcode.autoAacQualities.map { TranscodedVersion(it, AudioFormat.AAC) }

        val qualities = (opusQualities + aacQualities).nullIfEmpty() ?: return emptyMap()

        val results = mutableMapOf<String, Int>()
        for (qualityVersion in qualities) {
            val (quality, format) = qualityVersion
            val songs = transcodedSongRepository.getSongsWithTranscodingInfo(listOf(qualityVersion))
            if (songs.isEmpty()) {
                logger.info("No songs to transcode for quality: $quality ($format)")
                results["quality_${format.name}_$quality"] = 0
                continue
            }

            logger.info("Auto transcoding ${songs.size} songs for quality: $quality ($format)")
            onProgress(0.0, "Auto transcoding ${songs.size} songs for quality: $quality ($format)")

            val transcodedSongs = mutableListOf<Triple<SimpleSong, File, TranscodedVersion>>()
            val transcodedSongsMutex = Mutex()

            if (!transcoder.isTranscoderActive.compareAndSet(expectedValue = false, newValue = true)) {
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
                        onProgress(
                            progress,
                            "Transcoding quality $quality ($format): $processedCount/${songs.size} songs"
                        )
                    }
                ) { song ->
                    val file = Paths.get(song.path).toFile()
                    if (!file.exists()) {
                        logger.warn("Skipping auto transcode for \"${song.title}\": file not found at ${song.path}")
                    } else {
                        try {
                            val (newFile) = transcoder.transcodeAudio(environment, file, quality, audioFormat = format)
                            transcodedSongsMutex.withLock {
                                transcodedSongs.add(Triple(song, newFile, qualityVersion))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.error("Failed to auto transcode \"${song.title}\": ${e.message}")
                        }
                    }
                }
                results["quality_${format.name}_$quality"] = transcodedSongs.size
            } finally {
                transcodedSongRepository.insertTranscodedSong(transcodedSongs)
                transcoder.isTranscoderActive.store(false)
            }
        }

        return results
    }
}
