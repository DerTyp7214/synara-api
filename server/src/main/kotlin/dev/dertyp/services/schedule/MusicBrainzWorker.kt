package dev.dertyp.services.schedule

import dev.dertyp.core.HttpClientPriority
import dev.dertyp.services.AlbumService
import dev.dertyp.services.ArtistService
import dev.dertyp.services.SongService
import dev.dertyp.services.UserService
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.hours

class MusicBrainzWorker : Worker("MusicBrainzWorker") {
    private val songService by inject<SongService>()
    private val albumService by inject<AlbumService>()
    private val artistService by inject<ArtistService>()
    private val userService by inject<UserService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Int> {
        val admin = userService.findAdmin() ?: return emptyMap()

        var taggedSongs = 0
        var totalSongsChecked = 0
        var taggedAlbums = 0
        var totalAlbumsChecked = 0
        var taggedArtists = 0
        var totalArtistsChecked = 0
        
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
                                val song = songService.fetchMusicBrainzId(songId, admin.id, HttpClientPriority.LOW)
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
                                val album = albumService.fetchMusicBrainzId(albumId, priority = HttpClientPriority.LOW)
                                totalAlbumsChecked++
                                if (album?.musicbrainzId != null) {
                                    taggedAlbums++
                                } else {
                                    albumService.updateMusicBrainzLastCheck(albumId)
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
                                val artist = artistService.fetchMusicBrainzId(artistId, priority = HttpClientPriority.LOW)
                                totalArtistsChecked++
                                if (artist?.musicbrainzId != null) {
                                    taggedArtists++
                                } else {
                                    artistService.updateMusicBrainzLastCheck(artistId)
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
