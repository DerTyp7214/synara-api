package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.db.CustomMigrationTable
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CustomMigrationServiceTest : KoinTest {
    private lateinit var database: Database
    private val service = CustomMigrationService()

    fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                single { mockk<ImageService>(relaxed = true) }
            })
        }

        database = TestDatabase.connect(dialect, "migration_test")
        transaction(database) {
            SchemaUtils.create(CustomMigrationTable)
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @Migration("1.0")
    class Migration1(val orderList: MutableList<String>) : CustomMigration() {
        override suspend fun migrate() {
            orderList.add("1.0")
        }
    }

    @Migration("2.0")
    class Migration2(val orderList: MutableList<String>) : CustomMigration() {
        override suspend fun migrate() {
            orderList.add("2.0")
        }
    }

    @Migration("1.1")
    class Migration1_1(val orderList: MutableList<String>) : CustomMigration() {
        override suspend fun migrate() {
            orderList.add("1.1")
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `migrations should be executed in correct order`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val executionOrder = mutableListOf<String>()
        val m1 = Migration1(executionOrder)
        val m1_1 = Migration1_1(executionOrder)
        val m2 = Migration2(executionOrder)

        service.runMigrations(listOf(m2, m1, m1_1))

        assertEquals(listOf("1.0", "1.1", "2.0"), executionOrder)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `migrations should only be executed once`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val list = mutableListOf<String>()
        val m1 = Migration1(list)

        service.runMigrations(listOf(m1))
        assertEquals(1, list.size)

        service.runMigrations(listOf(m1))
        assertEquals(1, list.size)

        transaction(database) {
            val executedIds = CustomMigrationTable.selectAll().map { it[CustomMigrationTable.id] }
            assertTrue(executedIds.contains("Migration1"))
            assertEquals(1, executedIds.size)
        }
    }

    @Test
    fun `version comparison should work correctly`() {
        val compareVersions = service.javaClass.getDeclaredMethod("compareVersions", String::class.java, String::class.java).apply {
            isAccessible = true
        }

        fun invokeCompare(v1: String, v2: String) = compareVersions.invoke(service, v1, v2) as Int

        assertTrue(invokeCompare("1.0", "1.1") < 0)
        assertTrue(invokeCompare("1.1", "1.0") > 0)
        assertEquals(invokeCompare("1.0", "1.0.0"), 0)
        assertTrue(invokeCompare("1.2", "1.10") < 0)
        assertTrue(invokeCompare("2.0", "1.9.9") > 0)
        assertTrue(invokeCompare("1_1", "1.2") < 0)
    }

    @Test
    fun `duration formatting should work correctly`() {
        val formatDuration = service.javaClass.getDeclaredMethod("formatDuration", Long::class.java).apply {
            isAccessible = true
        }

        fun invokeFormat(ms: Long) = formatDuration.invoke(service, ms) as String

        assertEquals("500ms", invokeFormat(500))
        assertEquals("1s", invokeFormat(1000))
        assertEquals("1m 5s", invokeFormat(65000))
        assertEquals("1h 1m 5s", invokeFormat(3600000 + 60000 + 5000))
    }
}
