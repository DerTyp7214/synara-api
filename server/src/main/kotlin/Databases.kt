package dev.dertyp

import dev.dertyp.AudioUtils.transcodeFlacToWebm
import dev.dertyp.data.Song
import dev.dertyp.routing.*
import dev.dertyp.services.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.Database
import java.nio.file.Paths

fun Application.configureDatabases() {
    val dbPath = environment.config.propertyOrNull("sqlite.path")?.getString() ?: "./data.db"
    val database = Database.connect("jdbc:sqlite:$dbPath", "org.sqlite.JDBC")
    val songService = SongService(database)
    val imageService = ImageService(database)
    val albumService = AlbumService(database)
    val artistService = ArtistService(database)
    val playlistService = PlaylistService(database)

    val maxConcurrentTranscoders = 6

    routing {
        sse("/buildIndex") {
            val indexer = Indexer(songService, imageService, playlistService)

            indexer.start { stdout ->
                send(stdout)
            }
        }

        sse("/transcodeAll/{bitrate}") {
            val bitrate = call.parameters["bitrate"]?.toIntOrNull()
            if (bitrate == null) return@sse call.respond(HttpStatusCode.BadRequest)

            val songs = songService.allSongs(0, Int.MAX_VALUE).data

            val songChannel = Channel<Song>(Channel.UNLIMITED)

            coroutineScope {
                repeat(maxConcurrentTranscoders) { workerId ->
                    launch {
                        for (song in songChannel) {
                            val file = Paths.get(song.path).toFile()
                            send("""Worker $workerId: Starting transcode of "${song.title}" (${file.absolutePath})""")

                            try {
                                val (newFile) = transcodeFlacToWebm(file, bitrate)
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
        }

        song(songService)
        image(imageService)
        album(albumService)
        artist(artistService)
        playlist(playlistService)
    }
}