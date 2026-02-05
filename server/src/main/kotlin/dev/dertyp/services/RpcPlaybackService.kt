package dev.dertyp.services

import dev.dertyp.data.PlaybackState
import dev.dertyp.utils.LogParam
import kotlinx.coroutines.flow.Flow
import java.util.*

class RpcPlaybackService(
    private val playbackService: PlaybackService
) : IPlaybackService {
    override suspend fun getPlaybackState(sessionId: UUID): PlaybackState? {
        return playbackService.getPlaybackState(sessionId)
    }

    override suspend fun setPlaybackState(
        sessionId: UUID,
        @LogParam("sourceId") state: PlaybackState
    ): Boolean {
        return playbackService.setPlaybackState(sessionId, state)
    }

    override fun observePlaybackState(sessionId: UUID): Flow<PlaybackState> {
        return playbackService.observePlaybackState(sessionId)
    }
}
