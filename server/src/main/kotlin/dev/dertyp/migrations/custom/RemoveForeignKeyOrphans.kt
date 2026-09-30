package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.db.Dialect
import dev.dertyp.core.db.SchemaTables
import dev.dertyp.core.db.dbQuery
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

@Migration("3.23")
class RemoveForeignKeyOrphans : CustomMigration() {
    override suspend fun migrate() {
        if (dbQuery { Dialect.current() } != Dialect.SQLITE) return

        val keys = dbQuery {
            val existing = SchemaTables.all.filter { it.exists() }.toSet()
            existing.flatMap { it.foreignKeys }
                .filter { it.fromTable in existing && it.targetTable in existing }
                .distinct()
        }

        var deleted = 0
        var nulled = 0
        for (pass in 1..MAX_PASSES) {
            var changed = 0
            for (key in keys) {
                val count = try {
                    when (action(key)) {
                        Action.DELETE -> dbQuery { key.fromTable.deleteWhere { orphans(key) } }.also { deleted += it }
                        Action.SET_NULL -> dbQuery { setNull(key) }.also { nulled += it }
                        Action.REPORT -> 0
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Failed to clean orphans of ${describe(key)}", e)
                    0
                }
                changed += count
            }
            if (changed == 0) break
        }

        for (key in keys.filter { action(it) == Action.REPORT }) {
            val count = try {
                dbQuery { key.fromTable.selectAll().where { orphans(key) }.count() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to count orphans of ${describe(key)}", e)
                0L
            }
            if (count > 0) logger.warn("$count row(s) of ${describe(key)} reference missing rows and were left in place")
        }

        logger.info("Foreign key orphans: deleted $deleted row(s), cleared $nulled reference(s)")
    }

    private fun action(key: ForeignKeyConstraint): Action = when {
        key.deleteRule == ReferenceOption.CASCADE -> Action.DELETE
        key.deleteRule == ReferenceOption.SET_NULL && key.from.all { it.columnType.nullable } -> Action.SET_NULL
        else -> Action.REPORT
    }

    private fun orphans(key: ForeignKeyConstraint): Op<Boolean> {
        val parent = key.targetTable.alias("fk_parent")
        val match = key.references.map<Column<*>, Column<*>, Op<Boolean>> { (from, target) -> EqOp(parent[target], from) }
            .reduce { a, b -> a and b }
        val present = key.from.map<Column<*>, Op<Boolean>> { it.isNotNull() }.reduce { a, b -> a and b }
        return present and notExists(parent.select(parent[key.target.first()]).where { match })
    }

    private fun setNull(key: ForeignKeyConstraint): Int = key.fromTable.update({ orphans(key) }) { row ->
        key.from.forEach { row.clear(it) }
    }

    private fun <T> UpdateStatement.clear(column: Column<T>) {
        this[column] = Op.nullOp<T>()
    }

    private fun describe(key: ForeignKeyConstraint) =
        "${key.fromTable.tableName}(${key.from.joinToString { it.name }}) -> ${key.targetTable.tableName}"

    private enum class Action { DELETE, SET_NULL, REPORT }

    companion object {
        private const val MAX_PASSES = 10
    }
}
