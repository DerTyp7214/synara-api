package dev.dertyp

import dev.dertyp.AudioUtils.getSongsWithTranscodingInfo
import dev.dertyp.AudioUtils.insertTranscodedSong
import dev.dertyp.AudioUtils.transcodeFlacToWebm
import dev.dertyp.data.SimpleSong
import dev.dertyp.routing.*
import dev.dertyp.services.*
import dev.hayden.KHealth
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.sql.Database
import java.io.File
import java.nio.file.Paths
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalAtomicApi::class)
fun Application.configureDatabases() {
    val dbPath = environment.config.propertyOrNull("sqlite.path")?.getString() ?: "./data.db"
    val database = Database.connect("jdbc:sqlite:$dbPath", "org.sqlite.JDBC")
    val songService = SongService(database)
    val imageService = ImageService(database)
    val albumService = AlbumService(database)
    val artistService = ArtistService(database)
    val playlistService = PlaylistService(database)

    val indexer = Indexer(songService, imageService, playlistService)

    val tdnService = TdnService(indexer)

    val maxConcurrentTranscoders = 6

    val isTranscoderActive = AtomicBoolean(false)

    routing {
        install(KHealth) {
            successfulCheckStatusCode = HttpStatusCode.Accepted
            unsuccessfulCheckStatusCode = HttpStatusCode.Accepted
            healthChecks {
                check("available") {
                    true
                }
            }

            readyChecks {
                check("indexer_ready") {
                    !indexer.isActive.load()
                }
                check("transcoder_ready") {
                    !isTranscoderActive.load()
                }
                check("downloader_ready") {
                    !tdnService.isDownloadActive.load()
                }
            }
        }

        sse("/buildIndex") {
            if (!indexer.isActive.compareAndSet(expectedValue = false, newValue = true)) {
                call.respond(HttpStatusCode.Conflict, "Indexer is already running.")
                return@sse
            }

            indexer.start { stdout ->
                send(stdout)
            }

            indexer.isActive.store(false)
        }

        sse("/transcodeAll/{bitrate}") {
            if (!isTranscoderActive.compareAndSet(expectedValue = false, newValue = true)) {
                call.respond(HttpStatusCode.Conflict, "Transcoding is already in progress.")
                return@sse
            }

            val bitrate = call.parameters["bitrate"]?.toIntOrNull()
            if (bitrate == null) {
                isTranscoderActive.store(false)
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

                                    send("""Worker $workerId: Transcoded "${song.title}" (${newFile.absolutePath})""")
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
                isTranscoderActive.store(false)
            }
        }

        tdn(tdnService)

        song(songService)
        image(imageService)
        album(albumService)
        artist(artistService)
        playlist(playlistService)
    }
}