package dev.dertyp.song

import com.ucasoft.ktor.simpleCache.cacheOutput
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.repos.SongRepository
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.time.Duration.Companion.seconds

fun Routing.song(repository: SongRepository) {
    cacheOutput(2.seconds) {
        route("/song") {
            get("/byId/{id}") {
                val id = call.parameters["id"]?.toUUIDOrNull()
                if (id == null) return@get call.respond(HttpStatusCode.BadRequest)

                val song = repository.byId(id)
                if (song == null) return@get call.respond(HttpStatusCode.NotFound)

                call.respond(song)
            }
            get("/byAlbum/{albumId}") {
                val id = call.parameters["albumId"]?.toUUIDOrNull()
                if (id == null) return@get call.respond(HttpStatusCode.BadRequest)

                call.respond(repository.byAlbum(id))
            }
            get("/byArtist/{artistId}") {
                val id = call.parameters["artistId"]?.toUUIDOrNull()
                if (id == null) return@get call.respond(HttpStatusCode.BadRequest)

                call.respond(repository.byArtist(id))
            }
            get("/byTitle/{title}") {
                val title = call.parameters["title"]
                if (title == null) return@get call.respond(HttpStatusCode.BadRequest)

                call.respond(repository.byTitle(title))
            }

            get("/list") {
                call.respond(repository.allSongs())
            }
        }
    }
}