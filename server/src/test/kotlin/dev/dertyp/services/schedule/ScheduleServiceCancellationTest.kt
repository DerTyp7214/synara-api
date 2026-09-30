package dev.dertyp.services.schedule

import dev.dertyp.core.ApplicationScope
import dev.dertyp.plugins.ScheduleTrigger
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class ScheduleServiceCancellationTest : KoinTest {

    @BeforeEach
    fun setup() {
        val configService = mockk<ScheduledTaskConfigurationService>()
        every { configService.configurationsFlow } returns emptyFlow()

        startKoin {
            modules(module {
                single { configService }
            })
        }
    }

    @AfterEach
    fun tearDown() = runBlocking {
        ApplicationScope.scope.coroutineContext.cancelChildren()
        yield()
        stopKoin()
    }

    @Test
    fun `cancelling the scheduler ends the loop and does not reschedule the running task`() = runBlocking {
        val service = ScheduleService()
        val started = CompletableDeferred<Unit>()

        service.scheduleTask(ScheduleTrigger(Instant.now())) {
            started.complete(Unit)
            awaitCancellation()
        }

        val job = launch { service.startService() }

        withTimeout(1.seconds) {
            started.await()
            job.cancelAndJoin()
        }

        assertTrue(job.isCancelled)
        assertTrue(service.getScheduledTasks().isEmpty())
    }

    @Test
    fun `a one-shot task ending in cancellation is not rescheduled`() = runBlocking {
        val service = ScheduleService()
        val attempts = AtomicInteger(0)
        val ran = CompletableDeferred<Unit>()

        service.scheduleTask(ScheduleTrigger(Instant.now())) {
            attempts.incrementAndGet()
            ran.complete(Unit)
            throw CancellationException("task cancelled")
        }

        val job = launch { service.startService() }

        withTimeout(1.seconds) {
            ran.await()
            service.stopService()
            job.join()
        }

        assertEquals(1, attempts.get())
        assertTrue(service.getScheduledTasks().isEmpty())
    }
}
