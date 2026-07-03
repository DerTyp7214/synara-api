package dev.dertyp.db

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object RpcCallTotalsTable : UUIDTable("rpc_call_totals") {
    val service = varchar("service", 255)
    val method = varchar("method", 255)
    val username = varchar("username", 255).default("")
    val count = long("count").default(0)

    init {
        uniqueIndex(service, method, username)
    }
}
