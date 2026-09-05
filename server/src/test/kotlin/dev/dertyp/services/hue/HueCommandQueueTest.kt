package dev.dertyp.services.hue

import dev.dertyp.data.HueTarget
import dev.dertyp.data.HueTargetType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class HueCommandQueueTest {
    private fun light(id: String) = HueTarget(HueTargetType.LIGHT, id, id)
    private val room = HueTarget(HueTargetType.ROOM, "r", "room", "g")
    private fun update(brightness: Double) = LightUpdate(on = ClipOn(true), dimming = ClipDimming(brightness))

    @Test
    fun `light commands are paced at the light interval`() = runTest {
        val api = mockk<HueBridgeApi>(relaxed = true)
        val sentAt = CopyOnWriteArrayList<Long>()
        val queue = HueCommandQueue(api, backgroundScope, 100.milliseconds, 1.seconds, clock = { testScheduler.currentTime }, onSent = { sentAt += testScheduler.currentTime })
        repeat(5) { queue.submit(HueCommand(light("l$it"), update(50.0))) }
        advanceTimeBy(1)
        yield()
        assertEquals(1, sentAt.size)
        advanceTimeBy(450)
        yield()
        assertEquals(5, sentAt.size)
        for (i in 1 until sentAt.size) assertTrue(sentAt[i] - sentAt[i - 1] >= 100, "spacing ${sentAt[i] - sentAt[i - 1]}")
        coVerify(exactly = 5) { api.putLight(any(), any()) }
        queue.close()
    }

    @Test
    fun `rapid updates to one light collapse to the latest`() = runTest {
        val api = mockk<HueBridgeApi>(relaxed = true)
        val queue = HueCommandQueue(api, backgroundScope, 100.milliseconds, 1.seconds, clock = { testScheduler.currentTime })
        queue.submit(HueCommand(light("a"), update(10.0)))
        queue.submit(HueCommand(light("b"), update(10.0)))
        queue.submit(HueCommand(light("b"), update(20.0)))
        queue.submit(HueCommand(light("b"), update(30.0)))
        advanceTimeBy(2_000)
        yield()
        coVerify(exactly = 1) { api.putLight("a", any()) }
        coVerify(exactly = 1) { api.putLight("b", any()) }
        coVerify(exactly = 1) { api.putLight("b", update(30.0)) }
        queue.close()
    }

    @Test
    fun `grouped commands use the group interval and rate limits add a penalty`() = runTest {
        val api = mockk<HueBridgeApi>(relaxed = true)
        val errors = CopyOnWriteArrayList<Throwable>()
        val sentAt = CopyOnWriteArrayList<Long>()
        coEvery { api.putGroupedLight("g", update(1.0)) } throws HueRateLimited()
        val queue = HueCommandQueue(api, backgroundScope, 100.milliseconds, 1.seconds, 1.seconds, clock = { testScheduler.currentTime }, onSent = { sentAt += testScheduler.currentTime }, onError = { errors += it })
        queue.submit(HueCommand(room, update(1.0)))
        advanceTimeBy(1)
        yield()
        assertEquals(1, errors.size)
        assertTrue(errors.single() is HueRateLimited)
        queue.submit(HueCommand(light("x"), update(5.0)))
        advanceTimeBy(500)
        yield()
        assertEquals(0, sentAt.size)
        advanceTimeBy(600)
        yield()
        assertEquals(1, sentAt.size)
        queue.submit(HueCommand(room, update(2.0)))
        queue.submit(HueCommand(room.copy(id = "r2", groupedLightId = "g2"), update(2.0)))
        advanceTimeBy(1_500)
        yield()
        coVerify(exactly = 1) { api.putGroupedLight("g", update(2.0)) }
        coVerify(exactly = 1) { api.putGroupedLight("g2", update(2.0)) }
        assertTrue(sentAt[2] - sentAt[1] >= 1000)
        queue.close()
    }
}
