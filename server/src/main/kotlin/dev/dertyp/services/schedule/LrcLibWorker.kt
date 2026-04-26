package dev.dertyp.services.schedule

import dev.dertyp.core.cleanTitle
import dev.dertyp.db.SongTable
import dev.dertyp.dbQuery
import dev.dertyp.services.LrcLibService
import dev.dertyp.services.SongService
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.component.inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class LrcLibWorker : Worker("LrcLibWorker") {
    private val lrcLibService by inject<LrcLibService>()
    private val songService by inject<SongService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Int> {
        var synced = 0
        var notFound = 0
        var failed = 0

        val now = Clock.System.now()
        val oneWeekAgo = now - 7.days

        val songsToProcess = dbQuery {
            SongTable
                .selectAll()
                .where {
                    (SongTable.lyrics eq "") and
                    (SongTable.lastLyricsFetchAttempt less oneWeekAgo.toEpochMilliseconds())
                }
                .map {
                    it[SongTable.id].value
                }
        }

        if (songsToProcess.isEmpty()) {
            logger.info("No songs to process for LrcLib.")
            return emptyMap()
        }

        val total = songsToProcess.size.toDouble()

        songsToProcess.forEachIndexed { index, songId ->
            val progress = (index / total) * 100
            onProgress(progress, "Fetching lyrics from LrcLib for song ${index + 1}/${songsToProcess.size}")

            val song = songService.byId(songId) ?: return@forEachIndexed
            val artistName = song.artists.firstOrNull()?.name ?: ""

            try {
                val result = lrcLibService.getLyrics(artistName, song.title.cleanTitle(), song.album?.name, song.duration)
                if (result != null) {
                    val lyricsContent = result.syncedLyrics ?: result.plainLyrics
                    if (lyricsContent != null) {
                        dbQuery {
                            SongTable.update({ SongTable.id eq songId }) {
                                it[SongTable.lyrics] = lyricsContent
                                it[SongTable.lastLyricsFetchAttempt] = now.toEpochMilliseconds()
                            }
                        }
                        synced++
                        logger.info("Synced lyrics for song $songId from LrcLib.")
                    } else {
                        dbQuery {
                            SongTable.update({ SongTable.id eq songId }) {
                                it[SongTable.lastLyricsFetchAttempt] = now.toEpochMilliseconds()
                            }
                        }
                        notFound++
                        logger.info("Lyrics not found (empty) for song $songId on LrcLib.")
                    }
                } else {
                    dbQuery {
                        SongTable.update({ SongTable.id eq songId }) {
                            it[SongTable.lastLyricsFetchAttempt] = now.toEpochMilliseconds()
                        }
                    }
                    notFound++
                    logger.info("Lyrics not found for song $songId on LrcLib.")
                }
            } catch (e: Exception) {
                failed++
                logger.error("Failed to process song $songId: ${e.message}")
            }
        }

        return mapOf("synced" to synced, "notFound" to notFound, "failed" to failed)
    }
}
