package dev.dertyp

import dev.dertyp.routing.*
import dev.dertyp.services.*
import dev.hayden.KHealth
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.Database
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
fun Application.configureDatabases() {
    val dbPath = environment.config.propertyOrNull("sqlite.path")?.getString() ?: "./data.db"
    val database = Database.connect("jdbc:sqlite:$dbPath", "org.sqlite.JDBC")
    val songService = SongService(database)
    val imageService = ImageService(database, environment)
    val albumService = AlbumService(database)
    val artistService = ArtistService(database)
    val playlistService = PlaylistService(database)
    val spotifyService = SpotifyService(environment)

    val indexer = Indexer(songService, imageService, playlistService)

    val tdnService = TdnService(indexer)

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
                    !AudioUtils.isTranscoderActive.load()
                }
                check("downloader_ready") {
                    !tdnService.isDownloadActive.load()
                }
            }
        }

        utils(songService, imageService, albumService, artistService, playlistService, spotifyService, indexer)

        tdn(tdnService)

        song(songService)
        image(imageService)
        album(albumService)
        artist(artistService)
        playlist(playlistService)
    }
}