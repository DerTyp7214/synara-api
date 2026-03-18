package dev.dertyp.services.schedule

import dev.dertyp.AudioUtils
import dev.dertyp.AudioUtils.getSongsWithTranscodingInfo
import dev.dertyp.AudioUtils.insertTranscodedSong
import dev.dertyp.AudioUtils.transcodeFlacToOpus
import dev.dertyp.data.SimpleSong
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.nio.file.Paths
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class AutoTranscodeWorker : KoinComponent {
    private val logger = KtorSimpleLogger("AutoTranscodeWorker")
    private val environment by inject<ApplicationEnvironment>()

    suspend fun run() {
        val qualities = environment.config.propertyOrNull("audio.autoTranscode")?.getString()
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it > 0 } ?: return

        if (qualities.isEmpty()) return

        logger.info("Starting AutoTranscodeWorker for qualities: $qualities")

        for (quality in qualities) {
            val songs = getSongsWithTranscodingInfo(listOf(quality))
            if (songs.isEmpty()) {
                logger.info("No songs to transcode for quality: $quality")
                continue
            }

            logger.info("Auto transcoding ${songs.size} songs for quality: $quality")

            val songChannel = Channel<SimpleSong>(Channel.UNLIMITED)
            val transcodedSongs = mutableListOf<Triple<SimpleSong, File, Int>>()
            val transcodedSongsMutex = Mutex()
            val maxConcurrentTranscoders = 6

            if (!AudioUtils.isTranscoderActive.compareAndSet(expectedValue = false, newValue = true)) {
                logger.warn("Transcoding is already in progress, skipping quality: $quality")
                continue
            }

            try {
                coroutineScope {
                    repeat(maxConcurrentTranscoders) { _ ->
                        launch {
                            for (song in songChannel) {
                                val file = Paths.get(song.path).toFile()
                                try {
                                    val (newFile) = transcodeFlacToOpus(environment, file, quality)
                                    transcodedSongsMutex.withLock {
                                        transcodedSongs.add(Triple(song, newFile, quality))
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
            } finally {
                insertTranscodedSong(transcodedSongs)
                AudioUtils.isTranscoderActive.store(false)
            }
        }

        logger.info("AutoTranscodeWorker finished")
    }
}
