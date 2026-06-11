package dev.dertyp.services.schedule

import dev.dertyp.core.HttpClientPriority
import dev.dertyp.data.TaskKeys
import dev.dertyp.db.ProviderEnrichmentType
import dev.dertyp.services.AlbumService
import dev.dertyp.services.SongService
import dev.dertyp.services.import.Type
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.MetadataService
import dev.dertyp.utils.parsers.ParserFactory
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.inject
import java.util.concurrent.atomic.AtomicInteger

@WorkerTask(TaskKeys.ISRC_PROVIDER_ENRICHMENT_WORKER, "ISRC/Barcode Provider Enrichment Worker")
class IsrcProviderEnrichmentWorker : Worker("ISRC/Barcode Provider Enrichment Worker") {
    private val albumService by inject<AlbumService>()
    private val songService by inject<SongService>()
    private val recentReleaseWorker by inject<RecentReleaseWorker>()
    private val environment by inject<ApplicationEnvironment>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        if (recentReleaseWorker.active) {
            logger.info("Skipping IsrcProviderEnrichmentWorker because RecentReleaseWorker is running")
            return mapOf("skipped" to 1)
        }

        logger.info("Starting IsrcProviderEnrichmentWorker")

        val providers = IMetadataService.MetadataType.all().map {
            it to MetadataService.getMetadataService(it, environment)
        }.filter { (_, service) ->
            service.supportedFeatures.contains(IMetadataService.Feature.GET_TRACK_BY_ISRC) ||
                    service.supportedFeatures.contains(IMetadataService.Feature.GET_ALBUM_BY_BARCODE)
        }

        if (providers.isEmpty()) {
            logger.info("No providers support ISRC or Barcode lookup")
            return mapOf("skipped" to 1)
        }

        val totalFound = AtomicInteger(0)
        val totalProcessed = AtomicInteger(0)
        val songsFound = AtomicInteger(0)
        val albumsFound = AtomicInteger(0)

        coroutineScope {
            providers.forEach { (type, service) ->
                val providerName = type.value

                if (service.supportedFeatures.contains(IMetadataService.Feature.GET_TRACK_BY_ISRC)) {
                    launch {
                        runParallel(
                            items = songService.songIdsForIsrcEnrichment(providerName),
                            baseThreadCount = 2,
                            workerName = "$name-$providerName-songs",
                            onItemProcessed = {
                                val currentProcessed = totalProcessed.incrementAndGet()
                                onProgress(0.0, "Processed $currentProcessed items (Found: ${totalFound.get()})")
                            }
                        ) { id ->
                            if (recentReleaseWorker.active) return@runParallel
                            try {
                                val song = songService.byId(id)
                                if (song?.isrc != null) {
                                    val track = service.getTrackByIsrc(song.isrc!!, HttpClientPriority.LOW)
                                    if (track != null) {
                                        val providerUrl = ParserFactory.toUrl(providerName, track.id, Type.SONG)
                                        if (providerUrl != null) {
                                            songService.addProviderUrl(id, providerUrl)
                                            totalFound.incrementAndGet()
                                            songsFound.incrementAndGet()
                                        }
                                    }
                                }
                                songService.updateProviderEnrichmentCheck(id, providerName, ProviderEnrichmentType.SONG)
                            } catch (e: Exception) {
                                logger.error("Failed to enrich song $id with provider $providerName", e)
                            }
                        }
                    }
                }

                if (service.supportedFeatures.contains(IMetadataService.Feature.GET_ALBUM_BY_BARCODE)) {
                    launch {
                        runParallel(
                            items = albumService.albumIdsForBarcodeEnrichment(providerName),
                            baseThreadCount = 2,
                            workerName = "$name-$providerName-albums",
                            onItemProcessed = {
                                val currentProcessed = totalProcessed.incrementAndGet()
                                onProgress(0.0, "Processed $currentProcessed items (Found: ${totalFound.get()})")
                            }
                        ) { id ->
                            if (recentReleaseWorker.active) return@runParallel
                            try {
                                val album = albumService.byId(id)
                                if (album?.barcode != null) {
                                    val mbAlbum = service.getAlbumByBarcode(album.barcode!!, HttpClientPriority.LOW)
                                    if (mbAlbum != null) {
                                        val providerUrl = ParserFactory.toUrl(providerName, mbAlbum.id, Type.ALBUM)
                                        if (providerUrl != null) {
                                            albumService.addProviderUrl(id, providerUrl)
                                            totalFound.incrementAndGet()
                                            albumsFound.incrementAndGet()
                                        }
                                    }
                                }
                                albumService.updateProviderEnrichmentCheck(id, providerName, ProviderEnrichmentType.ALBUM)
                            } catch (e: Exception) {
                                logger.error("Failed to enrich album $id with provider $providerName", e)
                            }
                        }
                    }
                }
            }
        }

        logger.info("Finished IsrcProviderEnrichmentWorker. Found ${songsFound.get()} songs and ${albumsFound.get()} albums.")

        return mapOf(
            "totalProcessed" to totalProcessed.get(),
            "totalFound" to totalFound.get(),
            "songsFound" to songsFound.get(),
            "albumsFound" to albumsFound.get()
        )
    }
}
