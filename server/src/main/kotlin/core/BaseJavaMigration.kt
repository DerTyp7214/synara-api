package dev.dertyp.core

import dev.dertyp.services.DatabaseManager
import org.jetbrains.exposed.sql.Database
import org.koin.core.context.GlobalContext

fun <T> tempConnection (block: Database.() -> T): T {
    val databaseManager = GlobalContext.get().get<DatabaseManager>()
    return databaseManager.tempConnection(block)
}

