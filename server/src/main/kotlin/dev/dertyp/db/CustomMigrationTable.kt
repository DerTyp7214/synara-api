package dev.dertyp.db

import org.jetbrains.exposed.v1.core.Table

object CustomMigrationTable : Table("customMigration") {
    val id = varchar("id", 255)
    val executedAt = long("executedAt")

    override val primaryKey = PrimaryKey(id)
}
