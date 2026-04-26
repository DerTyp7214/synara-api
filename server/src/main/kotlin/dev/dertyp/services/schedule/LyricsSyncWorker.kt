package dev.dertyp.services.schedule

import dev.dertyp.db.SongTable
import dev.dertyp.db.SyncedLyricsTable
import dev.dertyp.dbQuery
import dev.dertyp.services.LyricsService
import kotlinx.coroutines.delay
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.koin.core.component.inject
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class LyricsSyncWorker : Worker("LyricsSyncWorker") {
    private val lyricsService by inject<LyricsService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Int> {
        if (!lyricsService.isConfigured()) {
            logger.info("Lyrics sync service is not configured. Skipping worker run.")
            return emptyMap()
        }

        var retryCount = 0
        while (!lyricsService.isReachable() && retryCount < 10) {
            logger.info("Lyrics transcriber not reachable, waiting 30s... (Attempt ${retryCount + 1}/10)")
            delay(30.seconds)
            retryCount++
        }

        if (!lyricsService.isReachable()) {
            logger.info("Lyrics sync service is not reachable after retries. Skipping worker run.")
            return emptyMap()
        }

        var synced = 0
        var notFound = 0
        var failed = 0

        val songsToProcess = dbQuery {
            SongTable.leftJoin(SyncedLyricsTable, onColumn = { SongTable.id }, otherColumn = { SyncedLyricsTable.songId })
                .selectAll()
                .where { SyncedLyricsTable.songId.isNull() }
                .map { 
                    SongInfo(
                        id = it[SongTable.id].value,
                        hasLyrics = it[SongTable.lyrics].isNotBlank()
                    )
                }
        }

        if (songsToProcess.isEmpty()) {
            logger.info("No songs to process for synced lyrics.")
            return emptyMap()
        }

        val sortedSongs = songsToProcess.sortedByDescending { it.hasLyrics }
        val total = sortedSongs.size.toDouble()

        sortedSongs.forEachIndexed { index, song ->
            val progress = (index / total) * 100
            onProgress(progress, "Syncing lyrics for song ${index + 1}/${sortedSongs.size}")
            
            logger.info("Processing song ${song.id} (${index + 1}/${sortedSongs.size})")
            
            try {
                val result = lyricsService.transcribeLyrics(song.id)
                if (result != null) {
                    synced++
                } else if (!song.hasLyrics) {
                    dbQuery {
                        SyncedLyricsTable.upsert(SyncedLyricsTable.songId) {
                            it[SyncedLyricsTable.songId] = song.id
                            it[SyncedLyricsTable.content] = null
                            it[SyncedLyricsTable.provider] = "not_found"
                        }
                    }
                    notFound++
                    logger.info("Marked song ${song.id} as lyrics not found.")
                } else {
                    failed++
                }
            } catch (e: Exception) {
                failed++
                logger.error("Failed to process song ${song.id}: ${e.message}")
            }
        }

        return mapOf("synced" to synced, "notFound" to notFound, "failed" to failed)
    }

    private data class SongInfo(
        val id: UUID,
        val hasLyrics: Boolean
    )
}
