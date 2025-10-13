package dev.dertyp.routing

import dev.dertyp.ApiClient
import dev.dertyp.AudioUtils
import dev.dertyp.AudioUtils.getSongsWithTranscodingInfo
import dev.dertyp.AudioUtils.insertTranscodedSong
import dev.dertyp.AudioUtils.transcodeFlacToWebm
import dev.dertyp.Indexer
import dev.dertyp.core.roundToNDecimals
import dev.dertyp.core.safeGet
import dev.dertyp.core.sha256
import dev.dertyp.core.toHumanReadableSize
import dev.dertyp.data.InsertableImage
import dev.dertyp.data.ServerStats
import dev.dertyp.data.SimpleSong
import dev.dertyp.db.ArtistTable
import dev.dertyp.dbQuery
import dev.dertyp.services.*
import io.github.smiley4.ktoropenapi.get
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
import org.jetbrains.exposed.sql.update
import java.io.File
import java.nio.file.Paths
import java.util.*
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
fun Routing.utils(
    songService: SongService,
    imageService: ImageService,
    albumService: AlbumService,
    artistService: ArtistService,
    playlistService: PlaylistService,
    spotifyService: SpotifyService,
    indexer: Indexer,
) {
    val maxConcurrentTranscoders = 6

    sse("/fetchArtistImages") {
        if (indexer.isActive.load()) {
            call.respond(HttpStatusCode.Conflict, "Index is running")
            return@sse
        }

        if (!spotifyService.isFetching.compareAndSet(expectedValue = false, newValue = true)) {
            call.respond(HttpStatusCode.Conflict, "Fetching is already in progress.")
            return@sse
        }

        val artists = dbQuery {
            ArtistTable
                .select(ArtistTable.id, ArtistTable.name, ArtistTable.image)
                .where { ArtistTable.image.isNull() }
                .map { Pair(it[ArtistTable.id].value, it[ArtistTable.name]) }
        }

        val artistChannel = Channel<Pair<UUID, String>>(Channel.UNLIMITED)

        try {
            coroutineScope {
                repeat(1) {
                    launch {
                        for ((id, name) in artistChannel) {
                            send("Fetching image for: $name")
                            val response = spotifyService.searchArtists(name)
                            val artist = response.firstOrNull { artist ->
                                artist.name.equals(name, ignoreCase = true)
                            }
                            if (artist == null) continue

                            val image = artist.images.maxByOrNull { it.width }
                            if (image == null) continue

                            val imageBytes = ApiClient.instance.safeGet<ByteArray>(image.url)
                            if (imageBytes == null) continue

                            val imageId = imageService.createBatch(
                                listOf(
                                    InsertableImage(
                                        data = imageBytes,
                                        imageHash = imageBytes.sha256()
                                    )
                                )
                            ).firstOrNull()
                            if (imageId == null) continue

                            val updates = dbQuery {
                                ArtistTable.update({ ArtistTable.id eq id }) {
                                    it[ArtistTable.image] = imageId
                                }
                            }

                            if (updates == 1) send("Updated \"$name\" with an image.")
                            else send("Something went wrong. $name")
                        }
                    }
                }
                for (artist in artists) {
                    artistChannel.send(artist)
                    ensureActive()
                }

                artistChannel.close()
            }

            send("Loading artist images done.")
        } catch (e: CancellationException) {
            throw e
        } finally {
            spotifyService.isFetching.store(false)
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
}