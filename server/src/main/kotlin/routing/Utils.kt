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
import dev.dertyp.data.SimpleSong
import dev.dertyp.db.ArtistTable
import dev.dertyp.dbQuery
import dev.dertyp.services.ImageService
import dev.dertyp.services.metadata.MetadataService
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.application.*
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
fun Route.utils(
    imageService: ImageService,
    environment: ApplicationEnvironment,
    indexer: Indexer,
) {
    val maxConcurrentTranscoders = 6

    route("/fetchArtistImages/{metadataProvider}", HttpMethod.Get, {
        request {
            pathParameter<MetadataService.Companion.MetadataType>("metadataProvider")
        }
    }) {
        sse {
            if (indexer.isActive.load()) {
                call.respond(HttpStatusCode.Conflict, "Index is running")
                return@sse
            }

            val metadataProviderString = call.parameters["metadataProvider"]
            if (metadataProviderString == null) return@sse call.respond(HttpStatusCode.BadRequest)

            val metadataProvider = MetadataService.Companion.MetadataType.valueOf(metadataProviderString)

            val service = MetadataService.getMetadataService(metadataProvider, environment)

            if (!MetadataService.isFetching.compareAndSet(expectedValue = false, newValue = true)) {
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
                                val response = service.searchArtists(name)
                                val artist = response.sortedByDescending { it.popularity }.firstOrNull { artist ->
                                    artist.name.replace(".", "")
                                        .equals(name.replace(".", ""), ignoreCase = true)
                                }
                                if (artist == null) {
                                    send("No artist with name \"$name\" ${response.joinToString(", ") { it.name }}")
                                    continue
                                }

                                val images = artist.images()
                                val image = images.maxByOrNull { it.width }
                                if (image == null) {
                                    send("No image for \"$name\" $artist ${images.joinToString(", ")}")
                                    continue
                                }

                                val imageBytes = ApiClient.instance.safeGet<ByteArray>(image.url)
                                if (imageBytes == null) {
                                    send("No image (null) for \"$name\"")
                                    continue
                                }

                                val imageId = imageService.createBatch(
                                    listOf(
                                        InsertableImage(
                                            data = imageBytes,
                                            imageHash = imageBytes.sha256(),
                                            origin = image.url
                                        )
                                    )
                                ).firstOrNull()
                                if (imageId == null) {
                                    send("Error inserting image for \"$name\": ${image.url} (${imageBytes.sha256()})")
                                    continue
                                }

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
            } catch (_: ClosedWriteChannelException) {
            } catch (e: Throwable) {
                e.printStackTrace()
            } finally {
                MetadataService.isFetching.store(false)
            }
        }
    }
    route("/buildIndex", HttpMethod.Get, {
        request {
        }
    }) {
        sse {
            if (!indexer.isActive.compareAndSet(expectedValue = false, newValue = true)) {
                call.respond(HttpStatusCode.Conflict, "Indexer is already running.")
                return@sse
            }

            indexer.start { stdout ->
                send(stdout)
            }

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