package dev.dertyp.core

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction

fun Transaction.foreignKeyOn(database: Database) {
    when (database.dialect.name) {
        "SQLite" -> execInBatch(listOf("PRAGMA foreign_keys = ON"))
    }
}