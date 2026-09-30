package dev.dertyp.core.db

import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.Statement
import java.sql.Connection

class BoundStatement(val sql: String, val arguments: List<Any?>)

fun Statement<*>.bind(transaction: Transaction): BoundStatement = BoundStatement(
    prepareSQL(transaction, prepared = true),
    (arguments().firstOrNull() ?: emptyList()).map { (type, value) -> databaseValue(type, value) }
)

private fun <T> databaseValue(type: IColumnType<T>, value: Any?): Any? = type.valueToDB(value?.let(type::valueFromDB))

fun Connection.execute(statement: BoundStatement): Int = prepareStatement(statement.sql).use { prepared ->
    statement.arguments.forEachIndexed { index, value -> prepared.setObject(index + 1, value) }
    prepared.executeUpdate()
}
