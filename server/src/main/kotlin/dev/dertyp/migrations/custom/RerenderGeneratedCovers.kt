package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.data.CoverGenerationParams
import dev.dertyp.data.CoverStyle
import dev.dertyp.data.CoverTarget
import dev.dertyp.data.CoverTargetType
import dev.dertyp.data.ImageSource
import dev.dertyp.db.CollectionTable
import dev.dertyp.db.UserPlaylistTable
import dev.dertyp.dbQuery
import dev.dertyp.services.cover.CoverGenerationService
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.koin.core.component.inject

@Migration("3.14")
class RerenderGeneratedCovers : CustomMigration() {
    private val coverGenerationService by inject<CoverGenerationService>()

    override suspend fun migrate() {
        logTask("Re-render generated covers") {
            val targets = dbQuery {
                val playlists = UserPlaylistTable
                    .select(UserPlaylistTable.id, UserPlaylistTable.coverStyle, UserPlaylistTable.coverSeed)
                    .where { UserPlaylistTable.imageSource eq ImageSource.GENERATED }
                    .map { Triple(CoverTarget(CoverTargetType.PLAYLIST, it[UserPlaylistTable.id].value), it[UserPlaylistTable.coverStyle], it[UserPlaylistTable.coverSeed]) }
                val collections = CollectionTable
                    .select(CollectionTable.id, CollectionTable.coverStyle, CollectionTable.coverSeed)
                    .where { CollectionTable.imageSource eq ImageSource.GENERATED }
                    .map { Triple(CoverTarget(CoverTargetType.COLLECTION, it[CollectionTable.id].value), it[CollectionTable.coverStyle], it[CollectionTable.coverSeed]) }
                playlists + collections
            }

            logger.info("Found ${targets.size} generated covers to re-render.")

            var rendered = 0
            var failed = 0
            targets.forEachIndexed { index, (target, style, seed) ->
                runCatching { coverGenerationService.apply(target, CoverGenerationParams(style = style ?: CoverStyle.AUTO, seed = seed)) }
                    .onSuccess { rendered++ }
                    .onFailure {
                        failed++
                        logger.warn("Failed to re-render cover for ${target.type.name.lowercase()} ${target.id}: ${it.message}")
                    }
                updateProgress((index + 1).toDouble() / targets.size, "Re-rendering covers: ${index + 1}/${targets.size} | Rendered: $rendered | Failed: $failed")
            }

            logger.info("Re-rendered $rendered/${targets.size} generated covers ($failed failed).")

            mapOf(
                "coversFound" to targets.size,
                "coversRendered" to rendered,
                "coversFailed" to failed,
            )
        }
    }
}
