package dev.dertyp.services.hue

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HueEntertainmentSessionTest {
    private val area = HueEntertainmentArea(
        id = "01234567-89ab-cdef-0123-456789abcdef",
        name = "Living room",
        channels = listOf(
            HueEntertainmentChannel(0, 0.0, 0.0, 0.0, listOf("light-1")),
            HueEntertainmentChannel(1, 1.0, 0.0, 0.0, listOf("light-2")),
        ),
        lightIds = setOf("light-1", "light-2"),
        active = false,
        activeStreamer = null,
    )

    private class FakeStream(private val failOnSend: Boolean = false) : HueEntertainmentStream {
        val frames = ArrayList<ByteArray>()
        var started = false
        var closed = false

        override suspend fun start() {
            started = true
        }

        override fun send(frame: ByteArray) {
            if (failOnSend) throw IllegalStateException("the bridge is gone")
            frames += frame
        }

        override fun close() {
            closed = true
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun TestScope.useVirtualTime() {
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns StandardTestDispatcher(testScheduler)
        mockkObject(Clock.System)
        every { Clock.System.now() } answers { Instant.fromEpochMilliseconds(testScheduler.currentTime) }
    }

    private fun renderer() = HueEntertainmentRenderer(area.orderedChannelIds, 0.5, 100)

    @Test
    fun `the loop sends a frame every forty milliseconds`() = runTest {
        useVirtualTime()
        val stream = FakeStream()
        val session = HueEntertainmentSession(
            area = area,
            stream = stream,
            renderer = renderer(),
            onError = {},
        )
        session.update { setPalette(listOf(0xFFFFFFFF.toInt()), 0, 0, 0) }

        session.launch(backgroundScope)
        advanceTimeBy(400)
        runCurrent()

        assertTrue(stream.frames.size in 9..12, "expected about ten frames but got ${stream.frames.size}")
        assertEquals((0 until stream.frames.size).toList(), stream.frames.map { it[11].toInt() and 0xFF })
        assertTrue(stream.frames.all { it.size == HueStreamFrame.HEADER_SIZE + 2 * HueStreamFrame.CHANNEL_SIZE })
        assertTrue(session.isActive)
        session.close()
    }

    @Test
    fun `every frame carries the rendered channels`() = runTest {
        useVirtualTime()
        val stream = FakeStream()
        val session = HueEntertainmentSession(
            area = area,
            stream = stream,
            renderer = renderer(),
            onError = {},
        )
        session.update { setPalette(listOf(0xFFFF0000.toInt()), 0, 0, 0) }

        session.launch(backgroundScope)
        advanceTimeBy(80)
        runCurrent()

        val frame = stream.frames.first()
        assertEquals(0.toByte(), frame[52])
        assertEquals(0xFF.toByte(), frame[53])
        assertEquals(1.toByte(), frame[59])
        session.close()
    }

    @Test
    fun `close stops the loop and the stream`() = runTest {
        useVirtualTime()
        val stream = FakeStream()
        val session = HueEntertainmentSession(
            area = area,
            stream = stream,
            renderer = renderer(),
            onError = {},
        )

        session.launch(backgroundScope)
        advanceTimeBy(100)
        runCurrent()
        val sent = stream.frames.size
        session.close()
        session.close()
        advanceTimeBy(400)
        runCurrent()

        assertTrue(stream.closed)
        assertFalse(session.isActive)
        assertEquals(sent, stream.frames.size)
    }

    @Test
    fun `a failing send reports once and ends the loop`() = runTest {
        useVirtualTime()
        val stream = FakeStream(failOnSend = true)
        val errors = ArrayList<Throwable>()
        val session = HueEntertainmentSession(
            area = area,
            stream = stream,
            renderer = renderer(),
            onError = { errors += it },
        )

        session.launch(backgroundScope)
        advanceTimeBy(400)
        runCurrent()

        assertEquals(1, errors.size)
        assertFalse(session.isActive)
        session.close()
    }
}
