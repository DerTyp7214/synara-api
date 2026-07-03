package dev.dertyp.services

import dev.dertyp.data.RpcCallEvent
import dev.dertyp.data.RpcCallStat
import dev.dertyp.data.RpcCallTotal
import dev.dertyp.db.RpcCallEventTable
import dev.dertyp.db.RpcCallStatsTable
import dev.dertyp.db.RpcCallTotalsTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll

class RpcMetricsService(
    private val collector: RpcMetricsCollector,
) : IRpcMetricsService, Service() {

    override suspend fun lifetimeTotals(limit: Int, username: String?): List<RpcCallTotal> {
        collector.flush()
        return dbQuery {
            val query = RpcCallTotalsTable.selectAll()
            if (username != null) query.where { RpcCallTotalsTable.username eq username }
            query
                .orderBy(RpcCallTotalsTable.count, SortOrder.DESC)
                .limit(limit)
                .map {
                    RpcCallTotal(
                        service = it[RpcCallTotalsTable.service],
                        method = it[RpcCallTotalsTable.method],
                        username = it[RpcCallTotalsTable.username].ifEmpty { null },
                        count = it[RpcCallTotalsTable.count],
                    )
                }
        }
    }

    override suspend fun timeSeries(service: String, method: String, sinceMillis: Long): List<RpcCallStat> {
        collector.flush()
        return dbQuery {
            RpcCallStatsTable.selectAll()
                .where {
                    (RpcCallStatsTable.service eq service) and
                        (RpcCallStatsTable.method eq method) and
                        (RpcCallStatsTable.bucketStart greaterEq sinceMillis)
                }
                .orderBy(RpcCallStatsTable.bucketStart, SortOrder.ASC)
                .map {
                    RpcCallStat(
                        service = it[RpcCallStatsTable.service],
                        method = it[RpcCallStatsTable.method],
                        username = it[RpcCallStatsTable.username].ifEmpty { null },
                        bucketStart = it[RpcCallStatsTable.bucketStart],
                        count = it[RpcCallStatsTable.count],
                    )
                }
        }
    }

    override suspend fun recentEvents(limit: Int): List<RpcCallEvent> {
        collector.flush()
        return dbQuery {
            RpcCallEventTable.selectAll()
                .orderBy(RpcCallEventTable.timestamp, SortOrder.DESC)
                .limit(limit)
                .map {
                    RpcCallEvent(
                        service = it[RpcCallEventTable.service],
                        method = it[RpcCallEventTable.method],
                        username = it[RpcCallEventTable.username].ifEmpty { null },
                        timestamp = it[RpcCallEventTable.timestamp],
                    )
                }
        }
    }
}
