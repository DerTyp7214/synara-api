package dev.dertyp.plugins

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TriggerTest {
    @Test
    fun testCronTrigger() {
        val trigger = CronTrigger("0 0 * * *", zoneId = ZoneId.of("UTC")) // Daily at midnight
        val now = Instant.parse("2023-01-01T12:00:00Z")
        val next = trigger.nextExecution(now)
        assertEquals(Instant.parse("2023-01-02T00:00:00Z"), next)
    }

    @Test
    fun testScheduleTrigger() {
        val startTime = Instant.parse("2023-01-01T10:00:00Z")
        val trigger = ScheduleTrigger(startTime, Duration.ofHours(1))
        
        assertTrue(trigger.doesRepeat())
        assertEquals(Instant.parse("2023-01-01T11:00:00Z"), trigger.nextExecution(startTime))
        
        val nextTrigger = trigger.updateForNextRun(startTime)
        assertEquals(Instant.parse("2023-01-01T11:00:00Z"), nextTrigger.scheduledTime)
    }

    @Test
    fun testEventTrigger() {
        val trigger = EventTrigger()
        assertEquals(Instant.MAX, trigger.scheduledTime)
        
        val fired = trigger.fire()
        assertTrue(fired.scheduledTime <= Instant.now())
    }

    @Test
    fun testTaskCompletionTrigger() {
        val id = UUID.randomUUID()
        val trigger = TaskCompletionTrigger(id)
        assertEquals(Instant.MAX, trigger.scheduledTime)
        
        trigger.activate()
        val activatedAt = trigger.scheduledTime
        assertTrue(activatedAt <= Instant.now())
        
        val reset = trigger.updateForNextRun(Instant.now())
        assertEquals(Instant.MAX, reset.scheduledTime)
        assertTrue(reset is TaskCompletionTrigger)
        assertEquals(id, reset.dependencyId)
    }

    @Test
    fun testCustomTrigger() {
        val trigger = CustomTrigger(autoRepeat = true)
        assertEquals(Instant.MAX, trigger.scheduledTime)

        trigger.signal()
        assertTrue(trigger.scheduledTime <= Instant.now())

        val next = trigger.updateForNextRun(Instant.now())
        assertEquals(Instant.MAX, next.scheduledTime)
    }
}
