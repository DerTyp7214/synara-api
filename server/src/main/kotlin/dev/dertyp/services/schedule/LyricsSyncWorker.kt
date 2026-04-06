package dev.dertyp.services.schedule

import dev.dertyp.db.SongTable
import dev.dertyp.db.SyncedLyricsTable
import dev.dertyp.dbQuery
import dev.dertyp.services.LyricsService
import io.ktor.util.logging.KtorSimpleLogger
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class LyricsSyncWorker : KoinComponent {
    private val logger = KtorSimpleLogger("LyricsSyncWorker")
    private val lyricsService by inject<LyricsService>()

    private val isRunning = AtomicBoolean(false)

    suspend fun run(onProgress: suspend (Double, String) -> Unit = { _, _ -> }): Map<String, Int> {
        if (!lyricsService.isConfigured()) {
            logger.info("Lyrics sync service is not configured. Skipping worker run.")
            return emptyMap()
        }

        if (!isRunning.compareAndSet(expectedValue = false, newValue = true)) {
            logger.info("LyricsSyncWorker is already running. Skipping this run.")
            return emptyMap()
        }

        var synced = 0
        var notFound = 0
        var failed = 0

        return try {
            logger.info("Starting LyricsSyncWorker")
            
            val songsToProcess = dbQuery {
                SongTable.join(SyncedLyricsTable, JoinType.LEFT, SongTable.id, SyncedLyricsTable.songId)
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

            onProgress(100.0, "LyricsSyncWorker finished. Processed ${sortedSongs.size} songs.")
            logger.info("LyricsSyncWorker finished. Synced: $synced, Not Found: $notFound, Failed: $failed")
            mapOf("synced" to synced, "notFound" to notFound, "failed" to failed)
        } catch (e: Exception) {
            logger.error("Error in LyricsSyncWorker", e)
            mapOf("error" to 1)
        } finally {
            isRunning.store(false)
        }
    }

    private data class SongInfo(
        val id: UUID,
        val hasLyrics: Boolean
    )
}
