package dev.dertyp.services

import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.PlaybackState
import dev.dertyp.data.User
import dev.dertyp.utils.LogParam
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.util.*

class RpcPlaybackService(
    private val user: User,
    private val sessionService: SessionService,
    private val playbackService: PlaybackService
) : IPlaybackService {
    private suspend fun requireOwnSession(sessionId: UUID) {
        if (!sessionService.sessionBelongsTo(sessionId, user.id)) {
            throw UnauthorizedException("Session does not belong to the current user")
        }
    }

    override suspend fun getPlaybackState(sessionId: UUID): PlaybackState? {
        requireOwnSession(sessionId)
        return playbackService.getPlaybackState(sessionId)
    }

    override suspend fun setPlaybackState(
        sessionId: UUID,
        @LogParam("sourceId") state: PlaybackState
    ): Boolean {
        requireOwnSession(sessionId)
        return playbackService.setPlaybackState(sessionId, state)
    }

    override fun observePlaybackState(sessionId: UUID): Flow<PlaybackState> = flow {
        requireOwnSession(sessionId)
        emitAll(playbackService.observePlaybackState(sessionId))
    }
}
