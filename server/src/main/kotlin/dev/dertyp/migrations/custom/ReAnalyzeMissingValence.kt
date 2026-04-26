package dev.dertyp.migrations.custom

import dev.dertyp.PlatformUUID
import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.db.SongAudioDataTable
import dev.dertyp.dbQuery
import dev.dertyp.services.AudioAnalysisService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.koin.core.component.inject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

@Migration("1.5")
class ReAnalyzeMissingValence : CustomMigration() {
    private val audioAnalysisService by inject<AudioAnalysisService>()

    override suspend fun migrate() {
        val songIds = dbQuery {
            SongAudioDataTable
                .select(SongAudioDataTable.songId)
                .where { SongAudioDataTable.valence.isNull() }
                .map { it[SongAudioDataTable.songId].value }
        }

        if (songIds.isEmpty()) return

        val threadCount = (Runtime.getRuntime().availableProcessors() / 4).coerceAtLeast(1)
        logger.info("Re-analyzing ${songIds.size} songs with missing valence data using $threadCount threads.")

        val processedCount = AtomicInteger(0)
        coroutineScope {
            val songChannel = Channel<PlatformUUID>(Channel.UNLIMITED)

            repeat(threadCount) {
                launch {
                    for (songId in songChannel) {
                        try {
                            audioAnalysisService.analyzeSong(songId)
                            val currentCount = processedCount.incrementAndGet()
                            if (currentCount % 10 == 0) {
                                logger.info("Progress: $currentCount / ${songIds.size}")
                            }
                        } catch (e: Exception) {
                            if (e !is CancellationException) logger.error("Failed to re-analyze song $songId: ${e.message}")
                        }
                    }
                }
            }

            for (songId in songIds) {
                songChannel.send(songId)
            }
            songChannel.close()
        }
        
        logger.info("Finished re-analyzing ${processedCount.get()} songs.")
    }
}
