package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.services.ImageService
import dev.dertyp.services.import.Type
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.inject

@Migration("3.18")
class ResetAppleArtistLinks : CustomMigration() {
    private val imageService by inject<ImageService>()

    override suspend fun migrate() {
        logTask("Reset Apple Music Artist Links") {
            val releases = dbQuery {
                ProviderReleaseTable
                    .select(ProviderReleaseTable.id, ProviderReleaseTable.externalId, ProviderReleaseTable.imageId)
                    .where { ProviderReleaseTable.provider eq PROVIDER }
                    .map {
                        Triple(
                            it[ProviderReleaseTable.id].value,
                            it[ProviderReleaseTable.externalId],
                            it[ProviderReleaseTable.imageId]?.value
                        )
                    }
            }

            log("Found ${releases.size} Apple Music provider releases")

            val releaseChunks = releases.map { it.first }.chunked(10000)
            var linkMappingsDeleted = 0
            releaseChunks.forEachIndexed { index, chunk ->
                linkMappingsDeleted += dbQuery {
                    ProviderReleaseLinkTable.deleteWhere {
                        ProviderReleaseLinkTable.providerReleaseId inList chunk
                    }
                }
                updateProgress(
                    (index + 1).toDouble() / releaseChunks.size * 10.0,
                    "Removed provider link batch ${index + 1}/${releaseChunks.size}"
                )
            }

            val externalIds = releases.map { it.second }.filter { it.isNotBlank() }.distinct()
            val externalChunks = externalIds.chunked(10000)
            var backLinksDeleted = 0
            externalChunks.forEachIndexed { index, chunk ->
                backLinksDeleted += dbQuery {
                    val linkIds = ProviderLinkTable
                        .select(ProviderLinkTable.id)
                        .where { ProviderLinkTable.provider eq PROVIDER }
                        .andWhere { ProviderLinkTable.externalId inList chunk }
                        .map { it[ProviderLinkTable.id].value }

                    if (linkIds.isEmpty()) 0
                    else RecentReleaseLinkTable.deleteWhere { RecentReleaseLinkTable.linkId inList linkIds }
                }
                updateProgress(
                    10.0 + (index + 1).toDouble() / externalChunks.size * 10.0,
                    "Removed back-link batch ${index + 1}/${externalChunks.size}"
                )
            }

            val providerReleasesDeleted = dbQuery {
                ProviderReleaseTable.deleteWhere { ProviderReleaseTable.provider eq PROVIDER }
            }
            updateProgress(30.0, "Deleted $providerReleasesDeleted Apple Music provider releases")

            val artistLinksDeleted = dbQuery {
                ArtistProviderTable.deleteWhere {
                    (ArtistProviderTable.provider eq PROVIDER) and (ArtistProviderTable.type eq Type.ARTIST.value)
                }
            }
            updateProgress(40.0, "Deleted $artistLinksDeleted Apple Music artist links")

            val referenced = imageService.collectReferencedImageIds()
            val deletable = releases.mapNotNull { it.third }.toSet() - referenced
            updateProgress(50.0, "Deleting ${deletable.size} unreferenced cover images")

            val imagesDeleted = imageService.deleteImagesByIds(deletable) { progress, message ->
                updateProgress(50.0 + progress / 2.0, message)
            }

            mapOf(
                "providerReleasesDeleted" to providerReleasesDeleted,
                "artistLinksDeleted" to artistLinksDeleted,
                "linkMappingsDeleted" to linkMappingsDeleted,
                "backLinksDeleted" to backLinksDeleted,
                "imagesDeleted" to imagesDeleted
            )
        }
    }

    companion object {
        private const val PROVIDER = "apple"
    }
}
