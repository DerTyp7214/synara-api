package dev.dertyp.routing

import dev.dertyp.AudioUtils
import dev.dertyp.AudioUtils.getSongsWithTranscodingInfo
import dev.dertyp.AudioUtils.insertTranscodedSong
import dev.dertyp.AudioUtils.transcodeFlacToWebm
import dev.dertyp.IIndexer
import dev.dertyp.Indexer
import dev.dertyp.RpcIndexer
import dev.dertyp.core.roundToNDecimals
import dev.dertyp.core.toHumanReadableSize
import dev.dertyp.data.SimpleSong
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.utils.io.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.rpc.krpc.ktor.server.rpc
import org.koin.ktor.ext.inject
import java.io.File
import java.nio.file.Paths
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
fun Route.utils() {
    val maxConcurrentTranscoders = 6
    route("/buildIndex", HttpMethod.Get, {
        request {
        }
    }) {
        rpc {
            val indexer by inject<Indexer>()

            registerService<IIndexer> { RpcIndexer(indexer) }
        }
        sse {
            val indexer by inject<Indexer>()

            if (!indexer.isActive.compareAndSet(expectedValue = false, newValue = true)) {
                indexer.logger.warn("Indexer is already running.")
                call.respond(HttpStatusCode.Conflict, "Indexer is already running.")
                return@sse
            }

            indexer.logger.info("Starting indexing ...")
            indexer.start { stdout ->
                send(stdout)
            }

            indexer.logger.info("Finished indexing ...")
            indexer.isActive.store(false)
        }
    }

    route("/transcodeAll/{bitrate}", HttpMethod.Get, {
        request {
            pathParameter<Int>("bitrate") {
                description = "The bitrate in kbps."
            }
        }
    }) {
        sse {
            if (!AudioUtils.isTranscoderActive.compareAndSet(expectedValue = false, newValue = true)) {
                call.respond(HttpStatusCode.Conflict, "Transcoding is already in progress.")
                return@sse
            }

            val bitrate = call.parameters["bitrate"]?.toIntOrNull()
            if (bitrate == null) {
                AudioUtils.isTranscoderActive.store(false)
                return@sse call.respond(HttpStatusCode.BadRequest)
            }

            val songs = getSongsWithTranscodingInfo(listOf(bitrate))

            val songChannel = Channel<SimpleSong>(Channel.UNLIMITED)

            val transcodedSongs = mutableListOf<Triple<SimpleSong, File, Int>>()
            val transcodedSongsMutex = Mutex()

            try {
                coroutineScope {
                    repeat(maxConcurrentTranscoders) { workerId ->
                        launch {
                            for (song in songChannel) {
                                val file = Paths.get(song.path).toFile()
                                send("""Worker $workerId: Starting transcode of "${song.title}" (${file.absolutePath})""")

                                try {
                                    val (newFile) = transcodeFlacToWebm(file, bitrate)

                                    transcodedSongsMutex.withLock {
                                        transcodedSongs.add(Triple(song, newFile, bitrate))
                                    }

                                    val oldSize = file.length()
                                    val newSize = newFile.length()

                                    send("""Worker $workerId: Transcoded "${song.title}" (${newFile.absolutePath})""")
                                    send(
                                        "Worker $workerId: Size reduction of ${(oldSize - newSize).toHumanReadableSize()} (${
                                            (((oldSize - newSize).toFloat() / oldSize.toFloat()) * 100.0).roundToNDecimals(
                                                2
                                            )
                                        }%)"
                                    )
                                } catch (e: Exception) {
                                    send("""Worker $workerId: Failed to transcode "${song.title}" (${e.message})""")
                                }
                            }
                        }
                    }
                    for (song in songs) {
                        songChannel.send(song)
                        ensureActive()
                    }

                    songChannel.close()
                }

                send("All transcode jobs completed.")
            } catch (e: CancellationException) {
                throw e
            } finally {
                insertTranscodedSong(transcodedSongs)
                AudioUtils.isTranscoderActive.store(false)
            }
        }
    }
}