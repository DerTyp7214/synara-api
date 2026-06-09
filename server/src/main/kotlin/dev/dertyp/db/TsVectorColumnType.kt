package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect

class TsVectorColumnType : ColumnType<Any>() {
    override fun sqlType(): String {
        return if (currentDialect is PostgreSQLDialect) "tsvector" else "VARCHAR(255)"
    }
    override fun valueFromDB(value: Any): Any = value
    override fun notNullValueToDB(value: Any): Any = value
    override fun nonNullValueToString(value: Any): String = value.toString()
}

fun Table.tsvector(name: String): Column<Any> = registerColumn(name, TsVectorColumnType())
