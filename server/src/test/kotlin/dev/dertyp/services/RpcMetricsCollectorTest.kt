package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.RpcCallEventTable
import dev.dertyp.db.RpcCallStatsTable
import dev.dertyp.db.RpcCallTotalsTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class RpcMetricsCollectorTest {
    private lateinit var database: Database

    private fun setup(dialect: DbDialect, name: String, enabled: Boolean = true): RpcMetricsCollector {
        database = TestDatabase.connect(dialect, name)
        transaction(database) {
            SchemaUtils.create(RpcCallTotalsTable, RpcCallStatsTable, RpcCallEventTable)
        }
        return RpcMetricsCollector(MetricsConfig(enabled = enabled))
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `records and flushes aggregate counts, totals and events`(dialect: DbDialect) = runBlocking {
        val collector = setup(dialect, "metrics_test")

        repeat(3) { collector.record("ISongService", "byId", "alice") }
        repeat(2) { collector.record("ISongService", "byId", "bob") }
        collector.record("IAlbumService", "byId", "")

        collector.flush()

        transaction(database) {
            val totals = RpcCallTotalsTable.selectAll().associate {
                Triple(
                    it[RpcCallTotalsTable.service],
                    it[RpcCallTotalsTable.method],
                    it[RpcCallTotalsTable.username],
                ) to it[RpcCallTotalsTable.count]
            }
            assertEquals(3L, totals[Triple("ISongService", "byId", "alice")])
            assertEquals(2L, totals[Triple("ISongService", "byId", "bob")])
            assertEquals(1L, totals[Triple("IAlbumService", "byId", "")])

            assertEquals(6L, RpcCallEventTable.selectAll().count())

            assertEquals(3L, RpcCallStatsTable.selectAll().count())
        }

        repeat(4) { collector.record("ISongService", "byId", "alice") }
        collector.flush()

        transaction(database) {
            val aliceTotal = RpcCallTotalsTable.selectAll()
                .first {
                    it[RpcCallTotalsTable.service] == "ISongService" &&
                        it[RpcCallTotalsTable.username] == "alice"
                }[RpcCallTotalsTable.count]
            assertEquals(7L, aliceTotal)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `disabled collector records nothing`(dialect: DbDialect) = runBlocking {
        val collector = setup(dialect, "metrics_disabled_test", enabled = false)

        repeat(5) { collector.record("ISongService", "byId", "alice") }
        collector.flush()

        transaction(database) {
            assertEquals(0L, RpcCallTotalsTable.selectAll().count())
            assertEquals(0L, RpcCallEventTable.selectAll().count())
            assertEquals(0L, RpcCallStatsTable.selectAll().count())
        }
    }
}
