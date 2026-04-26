package dev.dertyp.services.schedule

import dev.dertyp.AudioUtils
import dev.dertyp.AudioUtils.getSongsWithTranscodingInfo
import dev.dertyp.AudioUtils.insertTranscodedSong
import dev.dertyp.AudioUtils.transcodeFlacToOpus
import dev.dertyp.core.nullIfEmpty
import dev.dertyp.data.SimpleSong
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
        val qualities = environment.config.propertyOrNull("audio.autoTranscode")?.getString()
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it > 0 }
            ?.nullIfEmpty() ?: return emptyMap()

        val results = mutableMapOf<String, Int>()
        for (quality in qualities) {
            val songs = getSongsWithTranscodingInfo(listOf(quality))
            if (songs.isEmpty()) {
                logger.info("No songs to transcode for quality: $quality")
                results["quality_$quality"] = 0
                continue
            }

            logger.info("Auto transcoding ${songs.size} songs for quality: $quality")
            onProgress(0.0, "Auto transcoding ${songs.size} songs for quality: $quality")

            val songChannel = Channel<SimpleSong>(Channel.UNLIMITED)
            val transcodedSongs = mutableListOf<Triple<SimpleSong, File, Int>>()
            val transcodedSongsMutex = Mutex()
            val maxConcurrentTranscoders = 6
            var processedCount = 0

            if (!AudioUtils.isTranscoderActive.compareAndSet(expectedValue = false, newValue = true)) {
                logger.warn("Transcoding is already in progress, skipping quality: $quality")
                results["quality_$quality"] = 0
                continue
            }

            try {
                coroutineScope {
                    repeat(maxConcurrentTranscoders) { _ ->
                        launch {
                            for (song in songChannel) {
                                val file = Paths.get(song.path).toFile()
                                if (!file.exists()) {
                                    logger.warn("Skipping auto transcode for \"${song.title}\": file not found at ${song.path}")
                                    continue
                                }
                                try {
                                    val (newFile) = transcodeFlacToOpus(environment, file, quality)
                                    transcodedSongsMutex.withLock {
                                        transcodedSongs.add(Triple(song, newFile, quality))
                                        processedCount++
                                        val progress = (processedCount.toDouble() / songs.size) * 100.0
                                        onProgress(progress, "Transcoding quality $quality: $processedCount/${songs.size} songs")
                                    }
                                } catch (e: Exception) {
                                    logger.error("Failed to auto transcode \"${song.title}\": ${e.message}")
                                }
                            }
                        }
                    }

                    for (song in songs) {
                        songChannel.send(song)
                    }
                    songChannel.close()
                }
                results["quality_$quality"] = transcodedSongs.size
            } finally {
                insertTranscodedSong(transcodedSongs)
                AudioUtils.isTranscoderActive.store(false)
            }
        }

        return results
    }
}
