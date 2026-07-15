package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.db.FollowedArtistTable
import dev.dertyp.db.RecentReleaseTable
import dev.dertyp.dbQuery
import dev.dertyp.services.ImageService
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.component.inject

@Migration("3.5")
class UnlinkUnfollowedRecentReleaseImages : CustomMigration() {
    private val imageService by inject<ImageService>()

    override suspend fun migrate() {
        logTask("Unlink Unfollowed Recent Release Images") {
            val candidates = dbQuery {
                RecentReleaseTable
                    .select(RecentReleaseTable.releaseId, RecentReleaseTable.imageId)
                    .where { RecentReleaseTable.imageId.isNotNull() }
                    .andWhere { RecentReleaseTable.artistId notInSubQuery FollowedArtistTable.select(FollowedArtistTable.artistId) }
                    .map { it[RecentReleaseTable.releaseId].value to it[RecentReleaseTable.imageId]!!.value }
            }

            log("Found ${candidates.size} recent releases of unfollowed artists with a stored cover image")

            val releaseChunks = candidates.map { it.first }.chunked(10000)
            releaseChunks.forEachIndexed { index, chunk ->
                dbQuery {
                    RecentReleaseTable.update({ RecentReleaseTable.releaseId inList chunk }) {
                        it[RecentReleaseTable.imageId] = null
                        it[RecentReleaseTable.lastImageFetch] = null
                    }
                }
                updateProgress(
                    (index + 1).toDouble() / releaseChunks.size * 40.0,
                    "Unlinked batch ${index + 1}/${releaseChunks.size}"
                )
            }

            val referenced = imageService.collectReferencedImageIds()
            val deletable = candidates.map { it.second }.toSet() - referenced
            updateProgress(50.0, "Deleting ${deletable.size} unreferenced cover images")

            val deleted = imageService.deleteImagesByIds(deletable) { progress, message ->
                updateProgress(50.0 + progress / 2.0, message)
            }

            mapOf("rowsUnlinked" to candidates.size, "imagesDeleted" to deleted)
        }
    }
}
