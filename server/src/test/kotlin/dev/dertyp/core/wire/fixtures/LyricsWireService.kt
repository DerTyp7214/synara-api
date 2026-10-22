package dev.dertyp.core.wire.fixtures

import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.Song
import dev.dertyp.data.UserSong
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

@Rpc
interface ILyricsWireSongService {
    suspend fun song(): Song

    suspend fun userSong(): UserSong

    suspend fun page(): PaginatedResponse<UserSong>

    fun observe(): Flow<UserSong>
}
