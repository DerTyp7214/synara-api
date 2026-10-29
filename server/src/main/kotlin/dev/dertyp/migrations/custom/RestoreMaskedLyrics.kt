package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.core.db.dbQuery
import dev.dertyp.db.LyricsCandidateStatus
import dev.dertyp.db.LyricsCandidateTable
import dev.dertyp.services.lyrics.LyricsCandidateService
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.inject

@Migration("3.34")
class RestoreMaskedLyrics : CustomMigration() {
    private val lyricsCandidateService by inject<LyricsCandidateService>()

    override suspend fun migrate() = logTask("Restore Masked Lyrics") {
        val songIds = dbQuery {
            LyricsCandidateTable.select(LyricsCandidateTable.songId)
                .where { LyricsCandidateTable.status eq LyricsCandidateStatus.FOUND }
                .andWhere { MASK_FRAGMENTS.map { LyricsCandidateTable.plain like containing(it) }.compoundOr() }
                .withDistinct()
                .map { it[LyricsCandidateTable.songId].value }
        }
        log("Restoring masked words of the lyrics of ${songIds.size} songs")
        songIds.chunked(CHUNK).forEachIndexed { index, chunk ->
            lyricsCandidateService.refreshUncensored(chunk)
            val done = minOf((index + 1) * CHUNK, songIds.size)
            updateProgress(done * 100.0 / songIds.size, "$done / ${songIds.size} songs")
        }
        mapOf("songs" to songIds.size)
    }

    private fun containing(fragment: String): LikePattern =
        LikePattern.ofLiteral(fragment).let { LikePattern("%${it.pattern}%", it.escapeChar) }

    private companion object {
        val MASK_FRAGMENTS = listOf("*", "#", "_", "•", "--")
        const val CHUNK = 200
    }
}
