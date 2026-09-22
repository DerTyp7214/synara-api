package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.services.release.ReleaseArtistService
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.inject

@Migration("3.21")
class BackfillReleaseArtistLinks : CustomMigration() {
    private val releaseArtistService by inject<ReleaseArtistService>()

    override suspend fun migrate() {
        logTask("Backfill Release Artist Links") {
            val groups = dbQuery {
                RecentReleaseTable
                    .select(RecentReleaseTable.releaseId, RecentReleaseTable.artistId)
                    .map { it[RecentReleaseTable.releaseId].value to it[RecentReleaseTable.artistId].value }
            }

            val providerReleases = dbQuery {
                ProviderReleaseTable
                    .select(ProviderReleaseTable.id, ProviderReleaseTable.artistId)
                    .map { it[ProviderReleaseTable.id].value to it[ProviderReleaseTable.artistId].value }
            }

            val total = groups.size + providerReleases.size
            var processed = 0

            groups.chunked(10000).forEach { chunk ->
                dbQuery {
                    chunk.forEach { (groupId, artistId) ->
                        releaseArtistService.linkGroupTx(groupId, listOf(artistId))
                    }
                }
                processed += chunk.size
                updateProgress(
                    if (total == 0) 100.0 else processed * 100.0 / total,
                    "Linked $processed of $total releases"
                )
            }

            providerReleases.chunked(10000).forEach { chunk ->
                dbQuery {
                    chunk.forEach { (providerReleaseId, artistId) ->
                        releaseArtistService.linkProviderReleaseTx(providerReleaseId, listOf(artistId))
                    }
                }
                processed += chunk.size
                updateProgress(
                    if (total == 0) 100.0 else processed * 100.0 / total,
                    "Linked $processed of $total releases"
                )
            }

            log("Linked ${groups.size} release groups and ${providerReleases.size} provider releases to their artist")
            updateProgress(100.0, "Linked $total releases to their artist")

            mapOf("groups" to groups.size, "providerReleases" to providerReleases.size)
        }
    }
}
