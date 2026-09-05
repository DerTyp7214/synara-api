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
    private var emitted = -1

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            val current = clock.value
            val position = current.positionAt(now())
            val index = if (current.playing && !ended(position)) score.nextIndexAfter(position, emitted + 1, cadence) else -1
            val keyframe = score.keyframes.getOrNull(index)?.takeIf { !ended(it.atMs.toLong()) }
            if (keyframe == null) {
                clock.first { it != current }
                onClockChange()
                continue
            }
            val wait = (keyframe.atMs - position - latencyMs).coerceAtLeast(0)
            if (withTimeoutOrNull(wait) { clock.first { it != current } } != null) {
                onClockChange()
                continue
            }
            emit(keyframe, nextEligibleAtMs(keyframe, index))
            emitted = index
        }
    }

    private fun nextEligibleAtMs(keyframe: Keyframe, index: Int): Int? =
        score.keyframes.getOrNull(score.nextIndexAfter(keyframe.atMs.toLong(), index + 1, cadence))?.atMs

    private fun onClockChange() {
        val frame = score.keyframes.getOrNull(emitted)
        val position = clock.value.positionAt(now())
        if (frame == null || position >= frame.atMs || frame.atMs - position > latencyMs) emitted = -1
    }

    private fun ended(positionMs: Long): Boolean = durationMs > 0 && positionMs >= durationMs
}
