package dev.dertyp.services.schedule

import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ScheduleServiceTest {

    @Test
    fun `should execute scheduled task`() = runTest {
        val service = ScheduleService()
        val executed = CompletableDeferred<Unit>()
        
        service.scheduleTask(ScheduleTrigger(Instant.now())) {
            executed.complete(Unit)
        }

        val job = launch { service.startService() }
        
        withTimeout(1000) {
            executed.await()
        }
        
        service.stopService()
        job.join()
    }

    @Test
    fun `should execute repeating task`() = runTest {
        val service = ScheduleService()
        val count = AtomicInteger(0)
        val finished = CompletableDeferred<Unit>()

        service.scheduleTask(ScheduleTrigger(Instant.now(), repeat = Duration.ofMillis(10))) {
            if (count.incrementAndGet() >= 3) {
                finished.complete(Unit)
            }
        }

        val job = launch { service.startService() }

        withTimeout(1000) {
            finished.await()
        }

        service.stopService()
        job.join()
        assertTrue(count.get() >= 3)
    }

    @Test
    fun `should manually trigger task`() = runTest {
        val service = ScheduleService()
        val executed = CompletableDeferred<Unit>()

        val task = service.scheduleTask(ScheduleTrigger(Instant.now().plusSeconds(3600))) {
            executed.complete(Unit)
        }

        assertTrue(service.triggerTask(task.id))

        withTimeout(1000) {
            executed.await()
        }
    }

    @Test
    fun `should unschedule task`() = runTest {
        val service = ScheduleService()
        val executed = AtomicInteger(0)

        val task = service.scheduleTask(ScheduleTrigger(Instant.now().plusMillis(50))) {
            executed.incrementAndGet()
        }

        service.unscheduleTask(task.id)

        val job = launch { service.startService() }
        delay(100)
        service.stopService()
        job.join()

        assertEquals(0, executed.get())
    }

    @Test
    fun `should handle task dependency`() = runTest {
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

        withTimeout(1000) {
            firstExecuted.await()
            secondExecuted.await()
        }

        service.stopService()
        job.join()
    }

    @Test
    fun `should handle custom triggers and signaling`() = runTest {
        val service = ScheduleService()
        val executed = CompletableDeferred<Unit>()

        service.register("my-key") {
            executed.complete(Unit)
        }

        val job = launch { service.startService() }
        
        delay(50)
        service.signal("my-key")

        withTimeout(1000) {
            executed.await()
        }

        service.stopService()
        job.join()
    }

    @Test
    fun `should reschedule failed task if not repeating`() = runTest {
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

        withTimeout(1000) {
            secondAttemptScheduled.await()
        }

        spiedService.stopService()
        job.join()
        
        assertEquals(1, attempt.get())
    }
}
