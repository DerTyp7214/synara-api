package dev.dertyp.services.schedule

import dev.dertyp.services.SongService
import dev.dertyp.services.UserService
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class MusicBrainzWorker : KoinComponent {
    private val logger = KtorSimpleLogger("MusicBrainzWorker")
    private val songService by inject<SongService>()
    private val userService by inject<UserService>()

    suspend fun run() {
        val admin = userService.findAdmin() ?: return
        val start = Clock.System.now()
        logger.info("Starting MusicBrainzWorker")

        var count = 0
        var total = 0
        withTimeoutOrNull(3.hours) {
            songService.songIdsWithoutMusicBrainzId().collect { songId ->
                try {
                    val song = songService.fetchMusicBrainzId(songId, admin.id)
                    total++
                    if (song?.musicBrainzId != null) {
                        count++
                    }
                    delay(250)
                } catch (e: Exception) {
                    logger.error("Error fetching MusicBrainz ID for song $songId: ${e.message}", e)
                }
            }
        }

        logger.info("MusicBrainzWorker finished after ${Clock.System.now() - start}. Checked $total songs, tagged $count.")
    }
}
