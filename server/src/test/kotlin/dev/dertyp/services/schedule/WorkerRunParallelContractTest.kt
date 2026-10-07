package dev.dertyp.services.schedule

import dev.dertyp.config.ServerConfig
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
@Execution(ExecutionMode.SAME_THREAD)
class WorkerRunParallelContractTest : KoinTest {

    class ContractWorker(name: String) : Worker(name) {
        override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> = emptyMap()

        val granted: Int
            get() = grantedThreads.value

        suspend fun <T> parallel(
            items: Iterable<T>,
            baseThreadCount: Int,
            onItemProcessed: suspend (Int) -> Unit = {},
            block: suspend (T) -> Unit
        ) = runParallel(items, baseThreadCount, onItemProcessed, block)

        suspend fun <T> parallelAs(workerName: String, items: Flow<T>, baseThreadCount: Int, block: suspend (T) -> Unit) =
            runParallel(items, baseThreadCount, workerName, block = block)
    }

    @BeforeEach
    fun setup() {
        mockkStatic(::availableCores)
        every { availableCores() } returns 16
        startKoin {
            modules(module {
                single<ApplicationConfig> { MapApplicationConfig() }
                single { ServerConfig(get()) }
            })
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(::availableCores)
        stopKoin()
    }

    @Test
    fun `runParallel returns at once for no items and reports nothing`() = runTest {
        val worker = ContractWorker("EmptyWorker")
        val progress = mutableListOf<Int>()
        var calls = 0

        worker.parallel(emptyList<Int>(), 4, { progress += it }) { calls++ }

        assertEquals(0, calls)
        assertEquals(emptyList<Int>(), progress)
        assertEquals(0, currentTime)
        assertFalse(Worker.isActive("EmptyWorker"))
    }

    @Test
    fun `runParallel never runs more items at once than it was granted and returns with the last item`() = runTest {
        val worker = ContractWorker("LimitWorker")
        val progress = mutableListOf<Int>()
        val processed = mutableListOf<Int>()
        var running = 0
        var mostRunning = 0

        worker.parallel((1..20).toList(), 3, { progress += it }) { item ->
            running++
            mostRunning = maxOf(mostRunning, running)
            assertEquals(3, worker.granted)
            assertTrue(Worker.isActive("LimitWorker"))
            delay(100.milliseconds)
            running--
            processed += item
        }

        assertEquals(3, mostRunning)
        assertEquals((1..20).toList(), processed.sorted())
        assertEquals((1..20).toList(), progress)
        assertEquals(700, currentTime)
        assertFalse(Worker.isActive("LimitWorker"))
    }

    @Test
    fun `runParallel rethrows the failure of one item, cancels the running items and starts no further ones`() = runTest {
        val worker = ContractWorker("FailureWorker")
        val progress = mutableListOf<Int>()
        val started = mutableListOf<Int>()
        val completed = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()

        var thrown: Throwable? = null
        try {
            worker.parallel((1..10).toList(), 2, { progress += it }) { item ->
                started += item
                try {
                    if (item == 3) {
                        delay(500.milliseconds)
                        throw IllegalStateException("boom")
                    }
                    delay(1000.milliseconds)
                    completed += item
                } catch (e: CancellationException) {
                    cancelled += item
                    throw e
                }
            }
        } catch (e: Throwable) {
            thrown = e
        }

        assertTrue(thrown is IllegalStateException)
        assertEquals("boom", thrown?.message)
        assertEquals(listOf(1, 2, 3, 4), started.sorted())
        assertEquals(listOf(1, 2), completed.sorted())
        assertEquals(listOf(4), cancelled)
        assertEquals(listOf(1, 2, 3), progress)
        assertEquals(1500, currentTime)
        assertFalse(Worker.isActive("FailureWorker"))

        val again = mutableListOf<Int>()
        worker.parallel((1..5).toList(), 2) { again += it }
        assertEquals((1..5).toList(), again.sorted())
    }

    @Test
    fun `cancelling the caller cancels the running items, reports none of them and releases the worker`() = runTest {
        val worker = ContractWorker("CancelWorker")
        val progress = mutableListOf<Int>()
        val started = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()

        val job = launch {
            worker.parallel((1..10).toList(), 3, { progress += it }) { item ->
                started += item
                try {
                    awaitCancellation()
                } finally {
                    cancelled += item
                }
            }
        }
        delay(1000.milliseconds)

        assertEquals(listOf(1, 2, 3), started.sorted())
        assertTrue(Worker.isActive("CancelWorker"))
        assertTrue(job.isActive)

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(listOf(1, 2, 3), started.sorted())
        assertEquals(listOf(1, 2, 3), cancelled.sorted())
        assertEquals(emptyList<Int>(), progress)
        assertFalse(Worker.isActive("CancelWorker"))

        val again = mutableListOf<Int>()
        worker.parallel((1..5).toList(), 3) { again += it }
        assertEquals((1..5).toList(), again.sorted())
    }

    @Test
    fun `a worker cancelled while it waits for its first grant is unregistered and can run again`() = runTest {
        every { availableCores() } returns 1
        val holder = ContractWorker("GrantHolder")
        val waiter = ContractWorker("GrantWaiter")
        val release = CompletableDeferred<Unit>()
        val started = mutableListOf<Int>()

        val holderJob = launch { holder.parallel(listOf(1), 1) { release.await() } }
        delay(100.milliseconds)
        assertEquals(1, holder.granted)

        val waiterJob = launch { waiter.parallel((1..5).toList(), 4) { started += it } }
        delay(1000.milliseconds)

        assertEquals(1, holder.granted)
        assertEquals(0, waiter.granted)
        assertEquals(emptyList<Int>(), started)
        assertTrue(Worker.isActive("GrantWaiter"))
        assertTrue(waiterJob.isActive)

        waiterJob.cancel()
        waiterJob.join()

        assertTrue(waiterJob.isCancelled)
        assertEquals(emptyList<Int>(), started)
        assertFalse(Worker.isActive("GrantWaiter"))
        assertEquals(1, holder.granted)

        release.complete(Unit)
        holderJob.join()
        assertFalse(Worker.isActive("GrantHolder"))

        val again = mutableListOf<Int>()
        waiter.parallel((1..5).toList(), 4) { again += it }
        assertEquals((1..5).toList(), again.sorted())
        assertFalse(Worker.isActive("GrantWaiter"))
    }

    @Test
    fun `runParallel follows its grant down and up while it runs`() = runTest {
        val first = ContractWorker("ScaleFirst")
        val second = ContractWorker("ScaleSecond")
        val release = CompletableDeferred<Unit>()
        val processed = mutableListOf<Int>()
        var running = 0
        var mostRunning = 0

        val firstJob = launch {
            first.parallel((1..100).toList(), 14) { item ->
                running++
                mostRunning = maxOf(mostRunning, running)
                delay(100.milliseconds)
                running--
                processed += item
            }
        }
        delay(250.milliseconds)
        assertEquals(14, running)
        assertEquals(14, first.granted)

        val secondJob = launch { second.parallel(listOf(1), 14) { release.await() } }
        delay(200.milliseconds)
        assertEquals(7, first.granted)
        assertEquals(7, second.granted)
        assertEquals(7, running)

        release.complete(Unit)
        secondJob.join()
        delay(75.milliseconds)
        assertEquals(14, first.granted)
        assertEquals(14, running)

        firstJob.join()
        assertEquals(14, mostRunning)
        assertEquals((1..100).toList(), processed.sorted())
        assertFalse(Worker.isActive("ScaleFirst"))
        assertFalse(Worker.isActive("ScaleSecond"))
    }

    @Test
    fun `a run under another name registers that name and leaves the grant of the worker alone`() = runTest {
        val worker = ContractWorker("NamedWorker")
        val processed = mutableListOf<Int>()

        worker.parallelAs("NamedWorker-part", (1..6).asFlow(), 2) { item ->
            assertTrue(Worker.isActive("NamedWorker-part"))
            assertFalse(Worker.isActive("NamedWorker"))
            assertEquals(0, worker.granted)
            delay(10.milliseconds)
            processed += item
        }

        assertEquals((1..6).toList(), processed.sorted())
        assertFalse(Worker.isActive("NamedWorker-part"))
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    fun `runParallel keeps its limit on a multi threaded dispatcher`() = runBlocking(Dispatchers.Default) {
        val worker = ContractWorker("ThreadedWorker")
        val running = AtomicInteger(0)
        val mostRunning = AtomicInteger(0)
        val processed = AtomicInteger(0)
        val reported = AtomicInteger(0)

        worker.parallel((1..400).toList(), 4, { reported.accumulateAndGet(it) { a, b -> maxOf(a, b) } }) {
            mostRunning.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
            delay(1.milliseconds)
            running.decrementAndGet()
            processed.incrementAndGet()
        }

        assertEquals(4, mostRunning.get())
        assertEquals(400, processed.get())
        assertEquals(400, reported.get())
        assertFalse(Worker.isActive("ThreadedWorker"))
    }
}
