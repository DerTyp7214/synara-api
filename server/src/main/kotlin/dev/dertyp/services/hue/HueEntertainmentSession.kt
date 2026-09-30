package dev.dertyp.services.hue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock

class HueEntertainmentSession(
    val area: HueEntertainmentArea,
    private val stream: HueEntertainmentStream,
    val renderer: HueEntertainmentRenderer,
    private val onError: (Throwable) -> Unit,
) {
    private val encoder = HueStreamEncoder(area.id)
    private val lock = Any()
    private var job: Job? = null
    private var closed = false

    val isActive: Boolean get() = job?.isActive == true

    fun launch(scope: CoroutineScope): Job {
        val started = scope.launch(Dispatchers.IO) {
            var nextFrameMs = now()
            while (this.isActive) {
                val frame = update { tick(now()) }
                try {
                    stream.send(encoder.next(frame))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    onError(e)
                    break
                }
                nextFrameMs += FRAME_INTERVAL_MS
                val wait = nextFrameMs - now()
                if (wait > 0) delay(wait) else nextFrameMs = now()
            }
        }
        synchronized(lock) { job = started }
        return started
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    fun <T> update(block: HueEntertainmentRenderer.() -> T): T = synchronized(renderer) { renderer.block() }

    fun close() {
        val running = synchronized(lock) {
            if (closed) return
            closed = true
            job
        }
        running?.cancel()
        stream.close()
    }

    companion object {
        const val FRAME_INTERVAL_MS = 40L
    }
}
