package dev.dertyp.services.schedule

import dev.dertyp.services.SongService
import dev.dertyp.services.UserService
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalAtomicApi::class)
class MusicBrainzWorker : KoinComponent {
    private val logger = KtorSimpleLogger("MusicBrainzWorker")
    private val songService by inject<SongService>()
    private val userService by inject<UserService>()

    private val isRunning = AtomicBoolean(false)

    suspend fun run(): Map<String, Int> {
        if (!isRunning.compareAndSet(expectedValue = false, newValue = true)) {
            logger.info("MusicBrainzWorker is already running. Skipping this run.")
            return emptyMap()
        }

        var taggedCount = 0
        var totalChecked = 0
        try {
            val admin = userService.findAdmin() ?: return emptyMap()
            val start = Clock.System.now()
            logger.info("Starting MusicBrainzWorker")

            withTimeoutOrNull(3.hours) {
                songService.songIdsWithoutMusicBrainzId().collect { songId ->
                    try {
                        val song = songService.fetchMusicBrainzId(songId, admin.id)
                        totalChecked++
                        if (song?.musicBrainzId != null) {
                            taggedCount++
                        }
                        delay(750)
                    } catch (e: Exception) {
                        logger.error("Error fetching MusicBrainz ID for song $songId: ${e.message}", e)
                    }
                }
            }

            logger.info("MusicBrainzWorker finished after ${Clock.System.now() - start}. Checked $totalChecked songs, tagged $taggedCount.")
        } finally {
            isRunning.store(false)
        }
        return mapOf(
            "checked" to totalChecked,
            "tagged" to taggedCount
        )
    }
}
