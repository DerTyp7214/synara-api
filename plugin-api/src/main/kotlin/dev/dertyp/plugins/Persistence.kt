package dev.dertyp.plugins

import dev.dertyp.PlatformUUID
import dev.dertyp.PrefixedId
import dev.dertyp.data.*
import java.time.Instant

interface SongLibrary {
    suspend fun createBatch(songs: List<InsertableSong>): Map<PlatformUUID, Song>
    suspend fun byOriginalIds(ids: Collection<PrefixedId>, userId: PlatformUUID): List<UserSong>
    suspend fun byOriginalUrls(urls: Collection<String>, userId: PlatformUUID): Map<String, UserSong?>
    suspend fun setLiked(songId: PlatformUUID, userId: PlatformUUID, liked: Boolean, addedAt: Instant? = null)
    suspend fun setLikedReturning(songId: PlatformUUID, userId: PlatformUUID, liked: Boolean, addedAt: Instant? = null): UserSong?
}

interface AlbumLibrary {
    suspend fun createBatch(albums: List<InsertableAlbum>): Map<PlatformUUID, Album>
    suspend fun byMusicBrainzId(mbId: PlatformUUID): List<Album>
    suspend fun syncMusicBrainzForAlbums(albumIds: List<PlatformUUID>)
}

interface ArtistLibrary {
    suspend fun byMusicBrainzId(mbId: PlatformUUID): List<Artist>
}

interface ImageLibrary {
    suspend fun createBatch(images: List<InsertableImage>): Map<String, PlatformUUID>
    suspend fun getCoverHashes(hashes: List<String>): Map<String, PlatformUUID>
}

interface PlaylistLibrary {
    suspend fun createBatch(playlists: List<InsertablePlaylist>, userId: PlatformUUID? = null): List<PlatformUUID>
    suspend fun getOrAddPlaylist(userId: PlatformUUID, customIdentifier: String?, playlist: InsertablePlaylist): PlatformUUID
    suspend fun addToPlaylist(id: PlatformUUID, songIds: List<Pair<Long, PlatformUUID>>)
}
