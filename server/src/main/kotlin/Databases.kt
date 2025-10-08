package dev.dertyp

import dev.dertyp.services.AlbumService
import dev.dertyp.services.ArtistService
import dev.dertyp.services.SongService
import dev.dertyp.song.song
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.Database

fun Application.configureDatabases() {
    val database = Database.connect("jdbc:sqlite:./data.db", "org.sqlite.JDBC")
    val songService = SongService(database)
    val albumService = AlbumService(database)
    val artistService = ArtistService(database)

    routing {
        song(songService)
    }
}