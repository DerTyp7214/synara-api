package dev.dertyp.services.schedule

import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.TaskKeys
import dev.dertyp.data.TaskStatus
import dev.dertyp.plugins.TaskCompletionTrigger
import dev.dertyp.testing.relaxedTaskLogService
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ScheduleServiceWorkerTaskTest : KoinTest {

    class BlockingWorker : Worker("BlockingWorker") {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val executions = AtomicInteger(0)

        override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
            executions.incrementAndGet()
            started.complete(Unit)
            release.await()
            return mapOf("done" to true)
        }
    }

    private val logService = relaxedTaskLogService()

    @BeforeEach
    fun setup() {
        val configService = mockk<ScheduledTaskConfigurationService>()
        every { configService.configurationsFlow } returns emptyFlow()

        startKoin {
            modules(module {
                single { configService }
                single { logService }
                single { MusicBrainzWorker() }
                single { ImageAnalysisWorker() }
                single { AudioStartAnalysisWorker() }
            })
        }
    }

    @AfterEach
    fun tearDown() = runBlocking {
        ApplicationScope.scope.coroutineContext.cancelChildren()
        yield()
        stopKoin()
    }

    private fun ScheduleService.dependentOn(key: String, block: () -> Unit) {
        schedule(
            ScheduledTask(
                trigger = TaskCompletionTrigger(dependencyKey = key),
                task = { block() }
            )
        )
    }

    @Test
    fun `worker task throws and writes no log row when the worker is already running`() = runBlocking {
        val service = ScheduleService()
        val worker = BlockingWorker()
        val task = service.workerTask("Blocking", worker)

        val first = launch(Dispatchers.Default) { task() }
        withTimeout(5.seconds) { worker.started.await() }

        val thrown = assertThrows(WorkerAlreadyRunningException::class.java) {
            runBlocking { task() }
        }
        assertEquals("BlockingWorker", thrown.workerName)
        coVerify(exactly = 1) { logService.startLog("Blocking", any()) }

        worker.release.complete(Unit)
        first.join()

        assertEquals(1, worker.executions.get())
        coVerify(exactly = 1) {
            logService.logTask("Blocking", any(), any(), TaskStatus.SUCCESS, any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            logService.logTask(any(), any(), any(), TaskStatus.FAILURE, any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `skipped managed worker run does not notify dependents`() = runBlocking {
        val service = ScheduleService()
        val worker = BlockingWorker()
        service.registerManagedWorker("blocking", "Blocking", worker)
        val dependentRuns = AtomicInteger(0)
        val dependentDone = CompletableDeferred<Unit>()
        service.dependentOn("blocking") {
            dependentRuns.incrementAndGet()
            dependentDone.complete(Unit)
        }

        val job = launch { service.startService() }

        assertTrue(service.triggerTask("blocking"))
        withTimeout(5.seconds) { worker.started.await() }

        assertTrue(service.triggerTask("blocking"))
        delay(300.milliseconds)
        assertEquals(0, dependentRuns.get())
        coVerify(exactly = 1) { logService.startLog("Blocking", any()) }

        worker.release.complete(Unit)
        withTimeout(5.seconds) { dependentDone.await() }
        delay(100.milliseconds)

        assertEquals(1, dependentRuns.get())
        assertEquals(1, worker.executions.get())
        coVerify(exactly = 1) {
            logService.logTask("Blocking", any(), any(), TaskStatus.SUCCESS, any(), any(), any(), any(), any())
        }

        service.stopService()
        job.join()
    }

    @Test
    fun `post index tasks run the managed tasks and notify their dependents`() = runBlocking {
        val service = ScheduleService()
        val musicBrainzRuns = AtomicInteger(0)
        val imageRuns = CompletableDeferred<Unit>()
        val audioStartRuns = CompletableDeferred<Unit>()
        val dependentDone = CompletableDeferred<Unit>()

        service.registerManagedTask(
            TaskKeys.MUSICBRAINZ_WORKER,
            "MusicBrainz Worker"
        ) { musicBrainzRuns.incrementAndGet() }
        service.registerManagedTask(TaskKeys.IMAGE_ANALYSIS, "Image Analysis") { imageRuns.complete(Unit) }
        service.registerManagedTask(TaskKeys.AUDIO_START_ANALYSIS, "Audio Start Analysis") {
            audioStartRuns.complete(
                Unit
            )
        }
        service.dependentOn(TaskKeys.MUSICBRAINZ_WORKER) { dependentDone.complete(Unit) }

        val job = launch { service.startService() }

        service.schedulePostIndexTasks()

        withTimeout(5.seconds) {
            imageRuns.await()
            audioStartRuns.await()
            dependentDone.await()
        }
        assertEquals(1, musicBrainzRuns.get())

        service.stopService()
        job.join()
    }
}
