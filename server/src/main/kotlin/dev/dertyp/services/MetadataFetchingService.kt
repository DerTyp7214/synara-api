package dev.dertyp.services

import dev.dertyp.ApiClient
import dev.dertyp.core.safeGet
import dev.dertyp.core.sha256
import dev.dertyp.data.InsertableImage
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.ImageTable
import dev.dertyp.dbQuery
import dev.dertyp.services.metadata.MetadataService
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.component.inject
import java.util.UUID
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

@OptIn(ExperimentalAtomicApi::class)
class MetadataFetchingService(private val environment: ApplicationEnvironment) : Service() {
    private val imageService by inject<ImageService>()

    suspend fun fetchArtistImages(
        metadataProvider: MetadataService.Companion.MetadataType,
        onProgress: suspend (Double, String) -> Unit = { _, _ -> }
    ): Map<String, Int> {
        val service = MetadataService.getMetadataService(metadataProvider, environment)

        if (!MetadataService.isFetching.compareAndSet(expectedValue = false, newValue = true)) {
            onProgress(0.0, "Fetching is already in progress.")
            return emptyMap()
        }

        var foundCount = 0
        var totalChecked = 0
        try {
            val thirtyDaysAgo = Clock.System.now() - 30.days
            val artists = dbQuery {
                ArtistTable
                    .select(ArtistTable.id, ArtistTable.name)
                    .where { ArtistTable.image.isNull() and (ArtistTable.lastImageCheck eq 0L or (ArtistTable.lastImageCheck less thirtyDaysAgo.toEpochMilliseconds())) }
                    .map { Pair(it[ArtistTable.id].value, it[ArtistTable.name]) }
            }

            logger.info("Starting artist image fetch for ${artists.size} artists")
            onProgress(0.0, "Starting fetch for ${artists.size} artists")

            val artistChannel = Channel<Pair<UUID, String>>(Channel.UNLIMITED)
            val totalToFetch = artists.size

            coroutineScope {
                repeat(1) {
                    launch {
                        for ((id, name) in artistChannel) {
                            totalChecked++
                            val progress = (totalChecked.toDouble() / totalToFetch) * 100.0
                            onProgress(progress, "Fetching image for: $name")
                            val response = try {
                                service.searchArtists(name, 20)
                            } catch (e: Exception) {
                                logger.error("Error searching artists for $name", e)
                                emptyList()
                            }
                            
                            val artist = response.sortedByDescending { it.popularity }.firstOrNull { artist ->
                                artist.name.replace(".", "")
                                    .equals(name.replace(".", ""), ignoreCase = true)
                            }

                            if (artist == null) {
                                onProgress(progress, "No artist with name \"$name\" found.")
                                updateLastCheck(id)
                                continue
                            }

                            val images = artist.images
                            val image = images.maxByOrNull { it.width }
                            if (image == null) {
                                onProgress(progress, "No image for \"$name\"")
                                updateLastCheck(id)
                                continue
                            }

                            val imageBytes = ApiClient.instance.safeGet<ByteArray>(image.url)
                            if (imageBytes == null) {
                                onProgress(progress, "Failed to download image for \"$name\"")
                                updateLastCheck(id)
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
                                onProgress(progress, "Error inserting image for \"$name\"")
                                updateLastCheck(id)
                                continue
                            }

                            val updates = dbQuery {
                                ArtistTable.update({ ArtistTable.id eq id }) {
                                    it[ArtistTable.image] = EntityID(imageId, ImageTable)
                                    it[ArtistTable.lastImageCheck] = System.currentTimeMillis()
                                }
                            }

                            if (updates == 1) {
                                onProgress(progress, "Updated \"$name\" with an image.")
                                foundCount++
                            }
                            else onProgress(progress, "Something went wrong updating $name")
                        }
                    }
                }

                for (artist in artists) {
                    artistChannel.send(artist)
                    ensureActive()
                }

                artistChannel.close()
            }

            onProgress(100.0, "Loading artist images done.")
        } finally {
            MetadataService.isFetching.store(false)
        }
        return mapOf(
            "checked" to totalChecked,
            "found" to foundCount
        )
    }

    private suspend fun updateLastCheck(id: UUID) = dbQuery {
        ArtistTable.update({ ArtistTable.id eq id }) {
            it[ArtistTable.lastImageCheck] = System.currentTimeMillis()
        }
    }
}
