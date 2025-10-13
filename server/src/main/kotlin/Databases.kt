package dev.dertyp

import dev.dertyp.AudioUtils.getSongsWithTranscodingInfo
import dev.dertyp.core.authHeader
import dev.dertyp.data.ServerStats
import dev.dertyp.routing.*
import dev.dertyp.services.*
import dev.hayden.KHealth
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
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

        get("/stats", {
            response {
                HttpStatusCode.OK to {
                    body<ServerStats>()
                }
            }
        }) {
            val allSongs = getSongsWithTranscodingInfo(listOf())
            val allArtists = artistService.allArtists(0, Int.MAX_VALUE)
            val allAlbums = albumService.allAlbums(0, Int.MAX_VALUE)
            val allPlaylists = playlistService.allPlaylists(0, Int.MAX_VALUE)

            val totalDuration = allSongs.fold(0L) { acc, song -> acc + song.duration }
            val totalFileSize = allSongs.fold(0L) { acc, song -> acc + song.fileSize }

            call.respond(
                ServerStats(
                    songCount = allSongs.size,
                    artistCount = allArtists.data.size,
                    albumCount = allAlbums.data.size,
                    playlistCount = allPlaylists.data.size,
                    totalDuration = totalDuration,
                    totalFileSize = totalFileSize,
                    averageSizePerSong = totalFileSize / allSongs.size,
                )
            )
        }

        image(imageService)

        authenticate("synara-auth") {
            route({
                request {
                    authHeader()
                }
            }) {
                utils(imageService, spotifyService, indexer)

                tdn(tdnService)

                song(songService)
                album(albumService)
                artist(artistService)
                playlist(playlistService)
            }
        }
    }
}