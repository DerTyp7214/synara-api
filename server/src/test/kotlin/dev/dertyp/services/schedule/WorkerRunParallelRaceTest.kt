package dev.dertyp.services.schedule

import dev.dertyp.config.ServerConfig
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
import kotlin.time.Duration.Companion.seconds

@Execution(ExecutionMode.SAME_THREAD)
class WorkerRunParallelRaceTest : KoinTest {

    class RaceWorker : Worker("RaceWorker") {
        override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> = emptyMap()

        suspend fun <T> parallel(items: Iterable<T>, baseThreadCount: Int, block: suspend (T) -> Unit) =
            runParallel(items, baseThreadCount, block = block)
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
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    fun `runParallel always returns on a multi threaded dispatcher`() = runBlocking(Dispatchers.Default) {
        val worker = RaceWorker()
        repeat(2000) { iteration ->
            val itemCount = iteration % 4
            val processed = AtomicInteger(0)
            val finished = withTimeoutOrNull(10.seconds) {
                worker.parallel((1..itemCount).toList(), 8) { processed.incrementAndGet() }
                true
            }
            assertNotNull(finished, "runParallel did not return in iteration $iteration")
            assertEquals(itemCount, processed.get())
        }
    }
}
