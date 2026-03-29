package dev.dertyp.services.schedule

import dev.dertyp.services.AlbumService
import dev.dertyp.services.SongService
import dev.dertyp.services.UserService
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.produceIn
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
    private val albumService by inject<AlbumService>()
    private val userService by inject<UserService>()

    private val isRunning = AtomicBoolean(false)

    suspend fun run(): Map<String, Int> {
        if (!isRunning.compareAndSet(expectedValue = false, newValue = true)) {
            logger.info("MusicBrainzWorker is already running. Skipping this run.")
            return emptyMap()
        }

        var taggedSongs = 0
        var totalSongsChecked = 0
        var taggedAlbums = 0
        var totalAlbumsChecked = 0
        
        try {
            val admin = userService.findAdmin() ?: return emptyMap()
            val start = Clock.System.now()
            logger.info("Starting MusicBrainzWorker")

            withTimeoutOrNull(3.hours) {
                val songIdsChannel = songService.songIdsWithoutMusicBrainzId().produceIn(this)
                val albumIdsChannel = albumService.albumIdsWithoutMusicBrainzId().produceIn(this)

                try {
                    var songsDone = false
                    var albumsDone = false
                    while (!songsDone || !albumsDone) {
                        if (!songsDone) {
                            val songResult = songIdsChannel.receiveCatching()
                            songResult.getOrNull()?.let { songId ->
                                try {
                                    val song = songService.fetchMusicBrainzId(songId, admin.id)
                                    totalSongsChecked++
                                    if (song?.musicBrainzId != null) {
                                        taggedSongs++
                                    }
                                    delay(750)
                                } catch (e: Exception) {
                                    logger.error("Error fetching MusicBrainz ID for song $songId: ${e.message}", e)
                                }
                            }
                            if (songResult.isClosed) songsDone = true
                        }

                        if (!albumsDone) {
                            val albumResult = albumIdsChannel.receiveCatching()
                            albumResult.getOrNull()?.let { albumId ->
                                try {
                                    val album = albumService.fetchMusicBrainzId(albumId)
                                    totalAlbumsChecked++
                                    if (album?.musicbrainzId != null) {
                                        taggedAlbums++
                                    }
                                    delay(750)
                                } catch (e: Exception) {
                                    logger.error("Error fetching MusicBrainz ID for album $albumId: ${e.message}", e)
                                }
                            }
                            if (albumResult.isClosed) albumsDone = true
                        }
                    }
                } finally {
                    songIdsChannel.cancel()
                    albumIdsChannel.cancel()
                }
            }

            logger.info("MusicBrainzWorker finished after ${Clock.System.now() - start}. Checked $totalSongsChecked songs (tagged $taggedSongs), $totalAlbumsChecked albums (tagged $taggedAlbums).")
        } finally {
            isRunning.store(false)
        }
        return mapOf(
            "songsChecked" to totalSongsChecked,
            "songsTagged" to taggedSongs,
            "albumsChecked" to totalAlbumsChecked,
            "albumsTagged" to taggedAlbums
        )
    }
}
