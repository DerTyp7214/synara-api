package dev.dertyp

import dev.dertyp.routing.image
import dev.dertyp.routing.song
import dev.dertyp.services.AlbumService
import dev.dertyp.services.ArtistService
import dev.dertyp.services.ImageService
import dev.dertyp.services.SongService
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import org.jetbrains.exposed.sql.Database

fun Application.configureDatabases() {
    val dbPath = environment.config.propertyOrNull("sqlite.path")?.getString() ?: "./data.db"
    val database = Database.connect("jdbc:sqlite:$dbPath", "org.sqlite.JDBC")
    val songService = SongService(database)
    val imageService = ImageService(database)
    val albumService = AlbumService(database)
    val artistService = ArtistService(database)

    routing {
        sse("/buildIndex") {
            val indexer = Indexer(songService, imageService)

            indexer.start { stdout ->
                send(stdout)
            }
        }

        song(songService)
        image(imageService)
    }
}