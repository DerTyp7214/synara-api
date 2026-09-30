package dev.dertyp.core.db

import org.jetbrains.exposed.v1.core.Column
import java.sql.Connection

fun existingForeignKeyNames(connection: Connection, column: Column<*>): List<String> {
    val schema = try { connection.schema } catch (_: Exception) { null }
    val tableName = column.table.tableName
    return listOf(tableName.lowercase(), tableName).distinct().flatMap { name ->
        connection.metaData.getImportedKeys(null, schema, name).use { rs ->
            buildList {
                while (rs.next()) {
                    if (rs.getString("FKCOLUMN_NAME").equals(column.name, ignoreCase = true)) {
                        rs.getString("FK_NAME")?.let { add(it) }
                    }
                }
            }
        }
    }.distinct()
}
