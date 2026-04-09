package dev.dertyp.services.schedule

import dev.dertyp.services.AlbumService
import dev.dertyp.services.ArtistService
import dev.dertyp.services.SongService
import dev.dertyp.services.UserService
import io.ktor.util.logging.KtorSimpleLogger
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
    private val artistService by inject<ArtistService>()
    private val userService by inject<UserService>()

    private val isRunning = AtomicBoolean(false)

    suspend fun run(onProgress: suspend (Double, String) -> Unit = { _, _ -> }): Map<String, Int> {
        if (!isRunning.compareAndSet(expectedValue = false, newValue = true)) {
            logger.info("MusicBrainzWorker is already running. Skipping this run.")
            return emptyMap()
        }

        var taggedSongs = 0
        var totalSongsChecked = 0
        var taggedAlbums = 0
        var totalAlbumsChecked = 0
        var taggedArtists = 0
        var totalArtistsChecked = 0
        
        try {
            val admin = userService.findAdmin() ?: return emptyMap()
            val start = Clock.System.now()
            logger.info("Starting MusicBrainzWorker")
            onProgress(0.0, "Starting MusicBrainzWorker")

            withTimeoutOrNull(3.hours) {
                val songIdsChannel = songService.songIdsWithoutMusicBrainzId().produceIn(this)
                val albumIdsChannel = albumService.albumIdsWithoutMusicBrainzId().produceIn(this)
                val artistIdsChannel = artistService.artistIdsWithoutMusicBrainzId().produceIn(this)

                try {
                    var songsDone = false
                    var albumsDone = false
                    var artistsDone = false
                    while (!songsDone || !albumsDone || !artistsDone) {
                        if (!songsDone) {
                            val songResult = songIdsChannel.receiveCatching()
                            songResult.getOrNull()?.let { songId ->
                                try {
                                    val song = songService.fetchMusicBrainzId(songId, admin.id)
                                    totalSongsChecked++
                                    if (song?.musicBrainzId != null) {
                                        taggedSongs++
                                    }
                                    onProgress(0.0, "Checked $totalSongsChecked songs ($taggedSongs tagged), $totalAlbumsChecked albums ($taggedAlbums tagged), $totalArtistsChecked artists ($taggedArtists tagged)")
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
                                    onProgress(0.0, "Checked $totalSongsChecked songs ($taggedSongs tagged), $totalAlbumsChecked albums ($taggedAlbums tagged), $totalArtistsChecked artists ($taggedArtists tagged)")
                                } catch (e: Exception) {
                                    logger.error("Error fetching MusicBrainz ID for album $albumId: ${e.message}", e)
                                }
                            }
                            if (albumResult.isClosed) albumsDone = true
                        }

                        if (!artistsDone) {
                            val artistResult = artistIdsChannel.receiveCatching()
                            artistResult.getOrNull()?.let { artistId ->
                                try {
                                    val artist = artistService.fetchMusicBrainzId(artistId)
                                    totalArtistsChecked++
                                    if (artist?.musicbrainzId != null) {
                                        taggedArtists++
                                    }
                                    onProgress(0.0, "Checked $totalSongsChecked songs ($taggedSongs tagged), $totalAlbumsChecked albums ($taggedAlbums tagged), $totalArtistsChecked artists ($taggedArtists tagged)")
                                } catch (e: Exception) {
                                    logger.error("Error fetching MusicBrainz ID for artist $artistId: ${e.message}", e)
                                }
                            }
                            if (artistResult.isClosed) artistsDone = true
                        }
                    }
                } finally {
                    songIdsChannel.cancel()
                    albumIdsChannel.cancel()
                    artistIdsChannel.cancel()
                }
            }

            onProgress(100.0, "Finished: $totalSongsChecked songs ($taggedSongs tagged), $totalAlbumsChecked albums ($taggedAlbums tagged), $totalArtistsChecked artists ($taggedArtists tagged)")
            logger.info("MusicBrainzWorker finished after ${Clock.System.now() - start}. Checked $totalSongsChecked songs (tagged $taggedSongs), $totalAlbumsChecked albums (tagged $taggedAlbums), $totalArtistsChecked artists (tagged $taggedArtists).")
        } finally {
            isRunning.store(false)
        }
        return mapOf(
            "songsChecked" to totalSongsChecked,
            "songsTagged" to taggedSongs,
            "albumsChecked" to totalAlbumsChecked,
            "albumsTagged" to taggedAlbums,
            "artistsChecked" to totalArtistsChecked,
            "artistsTagged" to taggedArtists
        )
    }
}
