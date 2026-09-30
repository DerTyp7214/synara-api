package dev.dertyp.core.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

suspend fun <T> dbQuery(block: suspend () -> T): T =
    suspendTransaction { withContext(Dispatchers.IO) { block() } }
