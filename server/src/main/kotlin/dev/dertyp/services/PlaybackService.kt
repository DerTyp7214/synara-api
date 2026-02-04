package dev.dertyp.services

import dev.dertyp.data.PlaybackState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class PlaybackService : Service() {
    private val flows = ConcurrentHashMap<UUID, MutableSharedFlow<PlaybackState>>()

    private fun getFlow(sessionId: UUID): MutableSharedFlow<PlaybackState> {
        return flows.computeIfAbsent(sessionId) {
            MutableSharedFlow(replay = 1)
        }
    }

    fun getPlaybackState(sessionId: UUID): PlaybackState? {
        return flows[sessionId]?.replayCache?.firstOrNull()
    }

    suspend fun setPlaybackState(sessionId: UUID, state: PlaybackState): Boolean {
        getFlow(sessionId).emit(state)
        return true
    }

    fun observePlaybackState(sessionId: UUID): Flow<PlaybackState> {
        return getFlow(sessionId).asSharedFlow()
    }
}
