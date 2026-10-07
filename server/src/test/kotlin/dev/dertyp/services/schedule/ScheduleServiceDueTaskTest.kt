package dev.dertyp.services.schedule

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.UnsynchronizedAppenderBase
import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.TaskConfiguration
import dev.dertyp.data.TriggerDefinition
import dev.dertyp.plugins.ScheduleTrigger
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScheduleServiceDueTaskTest : KoinTest {

    class WhileTheConfigurationIsApplied(
        private val loopWaits: () -> Unit,
        private val managedTaskIsScheduled: () -> Unit,
        private val managedTaskIsUnscheduled: () -> Unit,
    ) : UnsynchronizedAppenderBase<ILoggingEvent>() {
        override fun append(event: ILoggingEvent) {
            val message = event.formattedMessage
            when {
                message.startsWith("Next task in") -> loopWaits()
                message.startsWith("Scheduling task: $MANAGED_NAME") -> managedTaskIsScheduled()
                message.startsWith("Unscheduling task") -> managedTaskIsUnscheduled()
            }
        }
    }

    private val serviceLogger = LoggerFactory.getLogger(ScheduleService::class.simpleName) as Logger
    private val loopWaits = CompletableDeferred<Unit>()

    @BeforeEach
    fun setup() {
        val configService = mockk<ScheduledTaskConfigurationService>()
        every { configService.configurationsFlow } returns flow {
            loopWaits.await()
            emit(listOf(TaskConfiguration(MANAGED_KEY, MANAGED_NAME, true, TriggerDefinition.Manual)))
            emit(emptyList())
        }

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
    fun `a task that is not due stays scheduled when the due task before it is unscheduled`() = runBlocking {
        val loopThread = Executors.newSingleThreadExecutor()
        val service = ScheduleService()
        val executed = AtomicBoolean(false)
        val later = service.scheduleTask(ScheduleTrigger(Instant.now().plusSeconds(3600))) { executed.set(true) }
        service.registerManagedTask(MANAGED_KEY, MANAGED_NAME) {}

        val raced = AtomicBoolean(false)
        val applied = CountDownLatch(1)
        val appender = WhileTheConfigurationIsApplied(
            loopWaits = { loopWaits.complete(Unit) },
            managedTaskIsScheduled = {
                val due = service.scheduleTask(ScheduleTrigger(Instant.now())) {}
                loopThread.submit(Runnable { }).get(5, TimeUnit.SECONDS)
                service.unscheduleTask(due.id)
                raced.set(true)
            },
            managedTaskIsUnscheduled = { if (raced.get()) applied.countDown() },
        )
        appender.context = serviceLogger.loggerContext
        appender.start()
        serviceLogger.addAppender(appender)
        val loop = launch(loopThread.asCoroutineDispatcher()) { service.startService() }

        try {
            assertTrue(applied.await(5, TimeUnit.SECONDS))
            loopThread.submit(Runnable { }).get(5, TimeUnit.SECONDS)
            assertTrue(raced.get())
            assertFalse(executed.get())
            assertTrue(later in service.getScheduledTasks())
        } finally {
            serviceLogger.detachAppender(appender)
            loop.cancelAndJoin()
            loopThread.shutdown()
        }
    }

    companion object {
        const val MANAGED_KEY = "managed-task"
        const val MANAGED_NAME = "Managed task"
    }
}
