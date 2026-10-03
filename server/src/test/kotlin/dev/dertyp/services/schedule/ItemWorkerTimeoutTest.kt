package dev.dertyp.services.schedule

import dev.dertyp.config.ServerConfig
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ItemWorkerTimeoutTest : KoinTest {

    class BlockingWorker(private val blocking: Set<Int>, override val timeout: Duration?) :
        ItemWorker<Int>("BlockingWorker") {
        override val baseThreads = 4
        override val resultKey = "processed"
        override val emptyMessage = "Nothing to do"

        override suspend fun loadItems(): List<Int> = listOf(1, 2, 3)

        override suspend fun process(item: Int): ItemOutcome {
            if (item in blocking) awaitCancellation()
            return ItemOutcome.SUCCEEDED
        }

        override fun progressMessage(current: Int, total: Int) = "$current/$total"
    }

    @BeforeEach
    fun setup() {
        startKoin {
            modules(module {
                single<ApplicationConfig> { MapApplicationConfig() }
                single { ServerConfig(get()) }
            })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `a timed out run reports the processed count and flags the timeout`() = runBlocking {
        val results = BlockingWorker(setOf(2, 3), 300.milliseconds).run()

        assertEquals(setOf("processed", "timedOut"), results.keys)
        assertEquals(1, results["processed"])
        assertEquals(true, results["timedOut"])
    }

    @Test
    fun `a run finishing within its timeout reports only the result key`() = runBlocking {
        val results = BlockingWorker(emptySet(), 30.seconds).run()

        assertEquals(mapOf("processed" to 3), results)
    }

    @Test
    fun `a run without a timeout reports only the result key`() = runBlocking {
        val results = BlockingWorker(emptySet(), null).run()

        assertEquals(mapOf("processed" to 3), results)
    }
}
