package dev.dertyp.services.schedule

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class TriggerTest {

    @Test
    fun `CronTrigger should calculate next execution`() {
        // Every minute at second 0
        val trigger = CronTrigger("* * * * *")
        val now = Instant.parse("2023-10-27T10:00:00Z")
        val next = trigger.nextExecution(now)
        
        assertEquals(now.plus(1, ChronoUnit.MINUTES), next)
    }

    @Test
    fun `CronTrigger should handle complex expressions`() {
        // Daily at 14:30
        val trigger = CronTrigger("30 14 * * *")
        val zoneId = java.time.ZoneId.systemDefault()

        val now = java.time.LocalDate.of(2023, 10, 27).atTime(10, 0).atZone(zoneId).toInstant()
        val next = trigger.nextExecution(now)
        
        val expected = java.time.LocalDate.of(2023, 10, 27).atTime(14, 30).atZone(zoneId).toInstant()
        assertEquals(expected, next)
    }

    @Test
    fun `CronTrigger should roll over to next day`() {
        // Daily at 08:00
        val trigger = CronTrigger("0 8 * * *")
        val zoneId = java.time.ZoneId.systemDefault()

        val now = java.time.LocalDate.of(2023, 10, 27).atTime(10, 0).atZone(zoneId).toInstant()
        val next = trigger.nextExecution(now)
        
        val expected = java.time.LocalDate.of(2023, 10, 28).atTime(8, 0).atZone(zoneId).toInstant()
        assertEquals(expected, next)
    }

    @Test
    fun `ScheduleTrigger should repeat`() {
        val start = Instant.parse("2023-10-27T10:00:00Z")
        val trigger = ScheduleTrigger(start, repeat = java.time.Duration.ofMinutes(10))
        
        assertTrue(trigger.doesRepeat())
        assertEquals(start.plus(10, ChronoUnit.MINUTES), trigger.nextExecution(start))
        
        val nextTrigger = trigger.updateForNextRun(start)
        assertEquals(start.plus(10, ChronoUnit.MINUTES), nextTrigger.scheduledTime)
    }

    @Test
    fun `ScheduleTrigger should not repeat if duration is zero`() {
        val trigger = ScheduleTrigger(Instant.now())
        assertFalse(trigger.doesRepeat())
    }
}
