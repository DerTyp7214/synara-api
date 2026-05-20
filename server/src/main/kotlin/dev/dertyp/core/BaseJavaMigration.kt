package dev.dertyp.core

import dev.dertyp.services.DatabaseManager
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.koin.core.context.GlobalContext

fun <T> tempConnection (block: JdbcTransaction.() -> T): T {
    val databaseManager = GlobalContext.get().get<DatabaseManager>()
    return databaseManager.tempConnection(block)
}
