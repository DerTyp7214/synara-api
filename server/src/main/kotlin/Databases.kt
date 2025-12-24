package dev.dertyp

import dev.dertyp.AudioUtils.getSongsWithTranscodingInfo
import dev.dertyp.data.ServerStats
import dev.dertyp.db.ImageTable
import dev.dertyp.routing.*
import dev.dertyp.services.*
import dev.dertyp.services.tdn.DownloadService
import dev.hayden.KHealth
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.jdbc.select
import org.koin.ktor.ext.getKoin
import org.koin.ktor.ext.inject
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
fun Application.configureDatabases() {
    CoroutineScope(Dispatchers.IO).launch {
        val downloadService = getKoin().get<DownloadService>()
        downloadService.startService()
    }

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
                    val indexer by inject<Indexer>()
                    !indexer.isActive.load()
                }
                check("transcoder_ready") {
                    !AudioUtils.isTranscoderActive.load()
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
            val artistService by inject<ArtistService>()
            val albumService by inject<AlbumService>()
            val playlistService by inject<PlaylistService>()
            val storageService by inject<StorageService>()

            val allSongs = getSongsWithTranscodingInfo(listOf())
            val allArtists = artistService.allArtists(0, Int.MAX_VALUE)
            val allAlbums = albumService.allAlbums(0, Int.MAX_VALUE)
            val allPlaylists = playlistService.allPlaylists(0, Int.MAX_VALUE)
            val images = dbQuery { ImageTable.select(ImageTable.id).map { it[ImageTable.id].value } }

            val totalDuration = allSongs.fold(0L) { acc, song -> acc + song.duration }
            val indexedFileSize = allSongs.fold(0L) { acc, song -> acc + song.fileSize }

            val totalFileSize = storageService.getTotalStorage()

            call.respond(
                ServerStats(
                    songCount = allSongs.size,
                    artistCount = allArtists.data.size,
                    albumCount = allAlbums.data.size,
                    imagesCount = images.size,
                    playlistCount = allPlaylists.data.size,
                    totalDuration = totalDuration,
                    totalFileSize = totalFileSize,
                    indexedFileSize = indexedFileSize,
                    averageSizePerSong = if (allSongs.isNotEmpty()) totalFileSize / allSongs.size else 0,
                    version = ServerStats.Version(
                        version = BuildConfig.VERSION,
                        buildTime = BuildConfig.BUILD_TIME
                    )
                )
            )
        }

        image()

        stream()

        getKoin().get<JwtService>().authenticated(this) {
            utils()
            metadata()

            sync()

            tdn()

            song()
            album()
            artist()
            playlist()
            userPlaylist()
        }
    }
}