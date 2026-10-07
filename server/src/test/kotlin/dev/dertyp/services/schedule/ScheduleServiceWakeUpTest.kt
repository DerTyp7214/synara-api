package dev.dertyp.services.schedule

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
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
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class ScheduleServiceWakeUpTest : KoinTest {

    class BeforeTheLoopWaits(private val action: () -> Unit) : AppenderBase<ILoggingEvent>() {
        val runs = AtomicInteger(0)

        override fun append(event: ILoggingEvent) {
            if (event.formattedMessage.startsWith("Next task in") && runs.getAndIncrement() == 0) action()
        }
    }

    private val serviceLogger = LoggerFactory.getLogger(ScheduleService::class.simpleName) as Logger

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

    private suspend fun CoroutineScope.whileTheLoopGoesToSleep(service: ScheduleService, action: () -> Unit) {
        val appender = BeforeTheLoopWaits(action)
        appender.context = serviceLogger.loggerContext
        appender.start()
        serviceLogger.addAppender(appender)
        val loop = launch { service.startService() }
        try {
            withTimeout(5.seconds) { loop.join() }
            assertTrue(appender.runs.get() > 0)
        } finally {
            serviceLogger.detachAppender(appender)
            loop.cancelAndJoin()
        }
    }

    @Test
    fun `a task scheduled between the look at the queue and the wait is executed`() = runBlocking {
        val service = ScheduleService()
        service.scheduleTask(ScheduleTrigger(Instant.now().plusSeconds(3600))) {}

        whileTheLoopGoesToSleep(service) {
            service.scheduleTask(ScheduleTrigger(Instant.now())) { service.stopService() }
        }
    }

    @Test
    fun `a stop requested between the look at the queue and the wait ends the loop`() = runBlocking {
        val service = ScheduleService()
        service.scheduleTask(ScheduleTrigger(Instant.now().plusSeconds(3600))) {}

        whileTheLoopGoesToSleep(service) {
            runBlocking { service.stopService() }
        }
    }

    @Test
    fun `an earlier task removed between the look at the queue and the wait does not delay the next one`() =
        runBlocking {
            val service = ScheduleService()
            val earlier = service.scheduleTask(ScheduleTrigger(Instant.now().plusSeconds(3600))) {}

            whileTheLoopGoesToSleep(service) {
                service.unscheduleTask(earlier.id)
                service.scheduleTask(ScheduleTrigger(Instant.now())) { service.stopService() }
            }
        }

    @Test
    fun `every task scheduled the moment the queue runs empty is executed`() = runBlocking(Dispatchers.Default) {
        val service = ScheduleService()
        val executed = AtomicInteger(0)
        val loop = launch { service.startService() }

        try {
            withTimeout(15.seconds) {
                repeat(ITERATIONS) {
                    service.scheduleTask(ScheduleTrigger(Instant.now())) { executed.incrementAndGet() }
                    while (service.getScheduledTasks().isNotEmpty()) yield()
                }
                while (executed.get() < ITERATIONS) yield()
            }
            assertEquals(ITERATIONS, executed.get())
            service.stopService()
            withTimeout(5.seconds) { loop.join() }
        } finally {
            loop.cancelAndJoin()
        }
    }

    companion object {
        const val ITERATIONS = 1000
    }
}
