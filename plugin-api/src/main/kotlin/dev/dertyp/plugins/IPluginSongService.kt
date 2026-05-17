package dev.dertyp.plugins

import dev.dertyp.PlatformUUID
import dev.dertyp.PrefixedId
import dev.dertyp.data.InsertableSong
import dev.dertyp.data.Song
import dev.dertyp.data.UserSong
import java.time.Instant

interface IPluginSongService {
    suspend fun createBatch(songs: List<InsertableSong>): Map<PlatformUUID, Song>
    suspend fun byOriginalIds(ids: Collection<PrefixedId>, userId: PlatformUUID): List<UserSong>
    suspend fun setLiked(songId: PlatformUUID, userId: PlatformUUID, liked: Boolean, addedAt: Instant? = null)
}
