package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.TaskStatus
import dev.dertyp.db.ScheduledTaskLogTable
import dev.dertyp.db.SearchIndexEntityType
import dev.dertyp.db.SearchIndexQueueTable
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class SearchIndexWorkerTest : KoinTest {
    private lateinit var database: Database
    private val scheduler = TestCoroutineScheduler()
    private val job = SupervisorJob()

    private fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "search_index_worker")
        transaction(database) {
            SchemaUtils.create(SearchIndexQueueTable, ScheduledTaskLogTable)
        }
        startKoin {
            modules(module {
                single { mockk<RedisSearchService>(relaxed = true) }
                single { ScheduledTaskLogService() }
            })
        }
        val dispatcher = StandardTestDispatcher(scheduler)
        mockkStatic(Dispatchers::class)
        every { Dispatchers.Default } returns dispatcher
        every { Dispatchers.IO } returns dispatcher
    }

    @AfterEach
    fun tearDown() {
        job.cancel()
        scheduler.runCurrent()
        unmockkStatic(Dispatchers::class)
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun taskLogRows() = transaction(database) { ScheduledTaskLogTable.selectAll().toList() }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an empty queue creates no task log rows and a processed batch creates exactly one`(dialect: DbDialect) {
        setup(dialect)
        SearchIndexWorker().startService(CoroutineScope(job))

        scheduler.advanceTimeBy(10.seconds)
        scheduler.runCurrent()
        assertEquals(0, taskLogRows().size)

        transaction(database) {
            repeat(3) {
                SearchIndexQueueTable.insert {
                    it[entityType] = SearchIndexEntityType.SONG
                    it[entityId] = UUID.randomUUID()
                }
            }
        }

        scheduler.advanceTimeBy(10.seconds)
        scheduler.runCurrent()

        assertEquals(0L, transaction(database) { SearchIndexQueueTable.selectAll().count() })
        val rows = taskLogRows()
        assertEquals(1, rows.size)
        assertEquals("Search Index Worker", rows.single()[ScheduledTaskLogTable.taskName])
        assertEquals(TaskStatus.SUCCESS, rows.single()[ScheduledTaskLogTable.status])
        assertEquals(100.0, rows.single()[ScheduledTaskLogTable.progress])

        scheduler.advanceTimeBy(10.seconds)
        scheduler.runCurrent()
        assertEquals(1, taskLogRows().size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a queue count whose rows are gone before the batch runs creates no task log row`(dialect: DbDialect) {
        setup(dialect)
        mockkConstructor(Query::class)
        try {
            every { anyConstructed<Query>().count() } returns 5L
            SearchIndexWorker().startService(CoroutineScope(job))

            scheduler.advanceTimeBy(10.seconds)
            scheduler.runCurrent()

            assertEquals(0, taskLogRows().size)
        } finally {
            job.cancel()
            scheduler.runCurrent()
            unmockkConstructor(Query::class)
        }
    }
}
