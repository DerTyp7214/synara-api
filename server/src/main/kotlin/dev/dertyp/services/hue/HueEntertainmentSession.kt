package dev.dertyp.services.hue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

class HueEntertainmentSession(
    val area: HueEntertainmentArea,
    private val stream: HueEntertainmentStream,
    val renderer: HueEntertainmentRenderer,
    private val encoder: HueStreamEncoder = HueStreamEncoder(area.id),
    private val frameIntervalMs: Long = FRAME_INTERVAL_MS,
    private val now: () -> Long = System::currentTimeMillis,
    private val onError: (Throwable) -> Unit = {},
    private val context: CoroutineContext = Dispatchers.IO,
) {
    private val lock = Any()
    private var job: Job? = null
    private var closed = false

    val isActive: Boolean get() = job?.isActive == true

    fun launch(scope: CoroutineScope): Job {
        val started = scope.launch(context) {
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
                nextFrameMs += frameIntervalMs
                val wait = nextFrameMs - now()
                if (wait > 0) delay(wait) else nextFrameMs = now()
            }
        }
        synchronized(lock) { job = started }
        return started
    }

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
