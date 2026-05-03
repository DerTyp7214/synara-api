package dev.dertyp.services.schedule

import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WorkerTest : KoinTest {

    @BeforeEach
    fun setup() {
        Worker.resetActiveWorkers()
        Worker.overridenProcessorCount = 16
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    private fun setupKoin(threadMultiplier: Double = 1.0) {
        startKoin {
            modules(module {
                single<ApplicationConfig> {
                    MapApplicationConfig().apply {
                        put("workers.threadMultiplier", threadMultiplier.toString())
                    }
                }
            })
        }
    }

    class TestWorker(name: String) : Worker(name) {
        override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Int> = emptyMap()

        suspend fun <T> testRunParallel(
            items: Iterable<T>,
            baseThreadCount: Int,
            onItemProcessed: suspend (Int) -> Unit = {},
            block: suspend (T) -> Unit
        ) = runParallel(items, baseThreadCount, onItemProcessed, block)

        fun getThreadsFlow(): MutableStateFlow<Int> = grantedThreads
    }

    private suspend fun awaitSettlement(expectedTotal: Int, vararg workers: TestWorker) {
        withTimeout(10.seconds) {
            while (true) {
                val currentTotal = workers.sumOf { it.getThreadsFlow().value }
                val allRegistered = workers.all { it.getThreadsFlow().value > 0 }
                if (allRegistered && currentTotal == expectedTotal) break
                delay(50.milliseconds)
            }
        }
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    fun `runParallel should process all items`() = runBlocking(Dispatchers.Default) {
        setupKoin()
        val worker = TestWorker("TestWorker")
        val items = (1..100).toList()
        val processedItems = mutableListOf<Int>()
        val mutex = Mutex()

        worker.testRunParallel(items, 4) { item ->
            mutex.withLock {
                processedItems.add(item)
            }
        }

        assertEquals(100, processedItems.size)
        assertTrue(processedItems.containsAll(items))
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    fun `runParallel should work on small systems`() = runBlocking(Dispatchers.Default) {
        setupKoin()
        Worker.overridenProcessorCount = 1
        val worker = TestWorker("SmallWorker")

        worker.testRunParallel((1..10).toList(), 4) {
            assertEquals(1, worker.getThreadsFlow().value)
        }
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    fun `runParallel should work on large systems with complex split`() = runBlocking(Dispatchers.Default) {
        setupKoin()
        val cores = 128
        Worker.overridenProcessorCount = cores
        val maxSafe = 115
        
        val worker1 = TestWorker("LargeW1")
        val worker2 = TestWorker("LargeW2")
        val worker3 = TestWorker("LargeW3")

        val latch = CompletableDeferred<Unit>()
        
        val job1 = launch { worker1.testRunParallel((1..100).toList(), 100) { latch.await() } }
        val job2 = launch { worker2.testRunParallel((1..100).toList(), 50) { latch.await() } }
        val job3 = launch { worker3.testRunParallel((1..100).toList(), 50) { latch.await() } }

        awaitSettlement(maxSafe, worker1, worker2, worker3)

        val w1 = worker1.getThreadsFlow().value
        val w2 = worker2.getThreadsFlow().value
        val w3 = worker3.getThreadsFlow().value

        assertEquals(58, w1)
        assertTrue(w2 in 28..29)
        assertTrue(w3 in 28..29)
        assertEquals(maxSafe, w1 + w2 + w3)

        latch.complete(Unit)
        joinAll(job1, job2, job3)
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    fun `runParallel should respect thread scaling`() = runBlocking(Dispatchers.Default) {
        setupKoin(threadMultiplier = 1.0)
        
        val maxSafe = 14
        
        val worker1 = TestWorker("Worker1")
        val worker2 = TestWorker("Worker2")
        
        val latch = CompletableDeferred<Unit>()
        val worker1Started = CompletableDeferred<Unit>()

        val job1 = launch {
            worker1.testRunParallel((1..100).toList(), maxSafe) {
                worker1Started.complete(Unit)
                latch.await()
            }
        }

        worker1Started.await()
        assertEquals(maxSafe, worker1.getThreadsFlow().value)

        val job2 = launch {
            worker2.testRunParallel((1..100).toList(), maxSafe) {
                latch.await()
            }
        }

        awaitSettlement(maxSafe, worker1, worker2)

        val w1Threads = worker1.getThreadsFlow().value
        val w2Threads = worker2.getThreadsFlow().value

        assertEquals(maxSafe, w1Threads + w2Threads)
        assertEquals(7, w1Threads)
        assertEquals(7, w2Threads)

        latch.complete(Unit)
        job1.join()
        job2.join()
        
        val worker3 = TestWorker("Worker3")
        launch {
            worker3.testRunParallel((1..1).toList(), maxSafe) {
                 assertEquals(maxSafe, worker3.getThreadsFlow().value)
            }
        }.join()
    }
}
