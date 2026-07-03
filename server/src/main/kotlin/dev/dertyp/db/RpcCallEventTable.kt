package dev.dertyp.db

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object RpcCallEventTable : UUIDTable("rpc_call_event") {
    val service = varchar("service", 255)
    val method = varchar("method", 255)
    val username = varchar("username", 255).default("")
    val timestamp = long("timestamp").index()
}
