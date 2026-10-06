package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.core.Migration
import dev.dertyp.core.db.dbQuery
import dev.dertyp.core.logTask
import dev.dertyp.db.AlbumMusicBrainzTable
import dev.dertyp.db.AlbumProviderTable
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.MBReleaseTable
import dev.dertyp.services.import.Type
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.MetadataService
import dev.dertyp.utils.Barcodes
import dev.dertyp.utils.parsers.ParserFactory
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.component.inject
import java.util.UUID

private const val ALBUM_CHUNK_SIZE = 1000
private const val PROVIDER_CHUNK_SIZE = 20
private const val BARCODE_MAX_LENGTH = 32

private val barcodeProviders = listOf(IMetadataService.MetadataType.tidal, IMetadataService.MetadataType.appleMusic)

@Migration("3.28")
class FillAlbumBarcodes : CustomMigration() {
    private val environment by inject<ApplicationEnvironment>()

    override suspend fun migrate() {
        logTask("Fill album barcodes") {
            val albumsWithoutBarcode = dbQuery {
                AlbumTable
                    .select(AlbumTable.id, AlbumTable.barcode)
                    .filter { Barcodes.normalize(it[AlbumTable.barcode]) == null }
                    .map { it[AlbumTable.id].value }
            }

            val releaseBarcodes = albumsWithoutBarcode.chunked(ALBUM_CHUNK_SIZE).flatMap { chunk ->
                dbQuery {
                    AlbumMusicBrainzTable
                        .innerJoin(
                            MBReleaseTable,
                            onColumn = { AlbumMusicBrainzTable.musicBrainzId },
                            otherColumn = { MBReleaseTable.id })
                        .select(AlbumMusicBrainzTable.albumId, MBReleaseTable.barcode)
                        .where { AlbumMusicBrainzTable.albumId inList chunk }
                        .andWhere { MBReleaseTable.barcode.isNotNull() }
                        .mapNotNull { row ->
                            val barcode = row[MBReleaseTable.barcode]?.trim()?.take(BARCODE_MAX_LENGTH)
                            if (barcode != null && Barcodes.normalize(barcode) != null) {
                                row[AlbumMusicBrainzTable.albumId].value to barcode
                            } else null
                        }
                }
            }.toMap()

            val fromMusicBrainz = writeBarcodes(releaseBarcodes)
            updateProgress(0.3, "From MusicBrainz releases: $fromMusicBrainz/${albumsWithoutBarcode.size}")

            val pending = albumsWithoutBarcode.filter { it !in releaseBarcodes }.toMutableSet()
            var fromProviders = 0

            barcodeProviders.forEachIndexed { providerIndex, type ->
                val providerName = ParserFactory.getParserForProvider(type.value)?.name ?: type.value
                val progressStart = 0.3 + 0.7 * providerIndex / barcodeProviders.size
                val progressShare = 0.7 / barcodeProviders.size

                val albumsByExternalId = pending.chunked(ALBUM_CHUNK_SIZE).flatMap { chunk ->
                    dbQuery {
                        AlbumProviderTable
                            .select(AlbumProviderTable.albumId, AlbumProviderTable.externalId)
                            .where { AlbumProviderTable.albumId inList chunk }
                            .andWhere { AlbumProviderTable.provider eq providerName }
                            .andWhere { AlbumProviderTable.type.isNull() or (AlbumProviderTable.type eq Type.ALBUM.value) }
                            .andWhere { AlbumProviderTable.externalId neq "" }
                            .map { it[AlbumProviderTable.externalId] to it[AlbumProviderTable.albumId].value }
                    }
                }.groupBy({ it.first }, { it.second })

                if (albumsByExternalId.isEmpty()) return@forEachIndexed

                val service = MetadataService.getMetadataService(type, environment)
                if (!service.supported()) {
                    log("Skipping ${albumsByExternalId.size} ${type.value} album(s), the provider is not available.")
                    return@forEachIndexed
                }

                val chunks = albumsByExternalId.keys.chunked(PROVIDER_CHUNK_SIZE)
                chunks.forEachIndexed { chunkIndex, chunk ->
                    try {
                        val providerBarcodes = service.getAlbumsByIds(chunk, HttpClientPriority.LOW)
                            .flatMap { album ->
                                val barcode = album.barcode?.trim()?.take(BARCODE_MAX_LENGTH)
                                if (barcode != null && Barcodes.normalize(barcode) != null) {
                                    albumsByExternalId[album.id.substringAfter(":")].orEmpty().map { it to barcode }
                                } else emptyList()
                            }
                            .toMap()
                        fromProviders += writeBarcodes(providerBarcodes)
                        pending -= providerBarcodes.keys
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("Failed to fetch ${type.value} album barcodes, batch ${chunkIndex + 1}/${chunks.size}", e)
                    }
                    updateProgress(
                        progressStart + progressShare * (chunkIndex + 1) / chunks.size,
                        "Fetching ${type.value} barcodes: ${chunkIndex + 1}/${chunks.size} batches | Updated: $fromProviders"
                    )
                }
            }

            logger.info("Filled $fromMusicBrainz album barcode(s) from MusicBrainz, $fromProviders from providers")
            mapOf(
                "albumsWithoutBarcode" to albumsWithoutBarcode.size,
                "fromMusicBrainz" to fromMusicBrainz,
                "fromProviders" to fromProviders
            )
        }
    }

    private suspend fun writeBarcodes(barcodes: Map<UUID, String>): Int = dbQuery {
        barcodes.entries.sumOf { (albumId, value) ->
            val stored = AlbumTable
                .select(AlbumTable.barcode)
                .where { AlbumTable.id eq albumId }
                .singleOrNull()
                ?: return@sumOf 0
            if (Barcodes.normalize(stored[AlbumTable.barcode]) != null) return@sumOf 0

            AlbumTable.update({ AlbumTable.id eq albumId }) {
                it[barcode] = value
            }
        }
    }
}
