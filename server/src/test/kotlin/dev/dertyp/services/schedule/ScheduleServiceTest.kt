package dev.dertyp.services.schedule

import dev.dertyp.plugins.ScheduleTrigger
import dev.dertyp.plugins.TaskCompletionTrigger
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ScheduleServiceTest {

    @Test
    fun `should execute scheduled task`() = runBlocking {
        val service = ScheduleService()
        val executed = CompletableDeferred<Unit>()
        
        service.scheduleTask(ScheduleTrigger(Instant.now())) {
            executed.complete(Unit)
        }

        val job = launch { service.startService() }
        
        withTimeout(1.seconds) {
            executed.await()
        }
        
        service.stopService()
        job.join()
    }

    @Test
    fun `should execute repeating task`() = runBlocking {
        val service = ScheduleService()
        val count = AtomicInteger(0)
        val finished = CompletableDeferred<Unit>()

        service.scheduleTask(ScheduleTrigger(Instant.now(), repeat = Duration.ofMillis(10))) {
            if (count.incrementAndGet() >= 3) {
                finished.complete(Unit)
            }
        }

        val job = launch { service.startService() }

        withTimeout(1.seconds) {
            finished.await()
        }

        service.stopService()
        job.join()
        assertTrue(count.get() >= 3)
    }

    @Test
    fun `should manually trigger task`() = runBlocking {
        val service = ScheduleService()
        val executed = CompletableDeferred<Unit>()

        val task = service.scheduleTask(ScheduleTrigger(Instant.now().plusSeconds(3600))) {
            executed.complete(Unit)
        }

        assertTrue(service.triggerTask(task.id))

        withTimeout(5.seconds) {
            executed.await()
        }
    }

    @Test
    fun `should unschedule task`() = runBlocking {
        val service = ScheduleService()
        val executed = AtomicInteger(0)

        val task = service.scheduleTask(ScheduleTrigger(Instant.now().plusMillis(50))) {
            executed.incrementAndGet()
        }

        service.unscheduleTask(task.id)

        val job = launch { service.startService() }
        delay(100.milliseconds)
        service.stopService()
        job.join()

        assertEquals(0, executed.get())
    }

    @Test
    fun `should handle task dependency`() = runBlocking {
        val service = ScheduleService()
        val firstExecuted = CompletableDeferred<Unit>()
        val secondExecuted = CompletableDeferred<Unit>()

        val firstTask = service.scheduleTask(ScheduleTrigger(Instant.now())) {
            firstExecuted.complete(Unit)
        }

        service.schedule(ScheduledTask(
            trigger = TaskCompletionTrigger(firstTask.id),
            task = {
                secondExecuted.complete(Unit)
            }
        ))

        val job = launch { service.startService() }

        withTimeout(1.seconds) {
            firstExecuted.await()
            secondExecuted.await()
        }

        service.stopService()
        job.join()
    }

    @Test
    fun `should handle custom triggers and signaling`() = runBlocking {
        val service = ScheduleService()
        val executed = CompletableDeferred<Unit>()

        service.register("my-key") {
            executed.complete(Unit)
        }

        val job = launch { service.startService() }
        
        delay(50.milliseconds)
        service.signal("my-key")

        withTimeout(1.seconds) {
            executed.await()
        }

        service.stopService()
        job.join()
    }

    @Test
    fun `should reschedule failed task if not repeating`() = runBlocking {
        val service = ScheduleService()
        val attempt = AtomicInteger(0)
        val secondAttemptScheduled = CompletableDeferred<UUID>()

        val spiedService = spyk(service, recordPrivateCalls = true)

        every { spiedService.schedule(any()) } answers {
            val task = it.invocation.args[0] as ScheduledTask
            if (attempt.get() == 1) {
                secondAttemptScheduled.complete(task.id)
            }
            service.schedule(task)
        }

        spiedService.scheduleTask(ScheduleTrigger(Instant.now())) {
            if (attempt.incrementAndGet() == 1) {
                throw RuntimeException("Fail first attempt")
            }
        }

        val job = launch { spiedService.startService() }

        withTimeout(1.seconds) {
            secondAttemptScheduled.await()
        }

        spiedService.stopService()
        job.join()
        
        assertEquals(1, attempt.get())
    }
}
