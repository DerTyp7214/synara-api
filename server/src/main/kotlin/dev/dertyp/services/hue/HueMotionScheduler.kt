package dev.dertyp.services.hue

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

data class PlaybackClock(val positionMs: Long, val anchorAt: Long, val playing: Boolean) {
    fun positionAt(now: Long): Long = if (playing) positionMs + (now - anchorAt).coerceAtLeast(0) else positionMs
}

class HueMotionScheduler(
    private val clock: StateFlow<PlaybackClock>,
    private val score: LightScore,
    private val durationMs: Long,
    latencyMs: Int,
    private val cadence: (Keyframe) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val emit: suspend (Keyframe, Int?) -> Unit,
) {
    private val latencyMs = latencyMs.coerceAtLeast(0)

    suspend fun run() {
        var emitted = -1
        while (currentCoroutineContext().isActive) {
            val current = clock.value
            if (!current.playing) {
                clock.first { it != current }
                emitted = -1
                continue
            }
            val position = current.positionAt(now())
            if (durationMs > 0 && position >= durationMs) return
            val index = score.nextIndexAfter(position, emitted + 1, cadence)
            if (index < 0) return
            val keyframe = score.keyframes[index]
            if (durationMs > 0 && keyframe.atMs >= durationMs) return
            val wait = (keyframe.atMs - position - latencyMs).coerceAtLeast(0)
            val changed = withTimeoutOrNull(wait) { clock.first { it != current } }
            if (changed != null) {
                emitted = -1
                continue
            }
            emit(keyframe, score.keyframes.getOrNull(index + 1)?.atMs)
            emitted = index
        }
    }
}
