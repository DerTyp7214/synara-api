package dev.dertyp.services

import dev.dertyp.data.PlaybackState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class PlaybackService : Service() {
    private class SessionEntry {
        val flow = MutableSharedFlow<PlaybackState>(replay = 0)
        @Volatile var lastState: PlaybackState? = null
    }

    private val sessions = ConcurrentHashMap<UUID, SessionEntry>()

    private fun getSession(sessionId: UUID): SessionEntry {
        return sessions.computeIfAbsent(sessionId) {
            SessionEntry()
        }
    }

    fun getPlaybackState(sessionId: UUID): PlaybackState? {
        return sessions[sessionId]?.lastState
    }

    suspend fun setPlaybackState(sessionId: UUID, state: PlaybackState): Boolean {
        val session = getSession(sessionId)
        session.lastState = state
        session.flow.emit(state)
        return true
    }

    fun observePlaybackState(sessionId: UUID): Flow<PlaybackState> {
        return getSession(sessionId).flow.asSharedFlow()
    }
}
