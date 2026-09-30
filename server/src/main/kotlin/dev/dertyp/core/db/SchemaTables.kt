package dev.dertyp.core.db

import io.github.classgraph.ClassGraph
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID

object SchemaTables {
    val all: List<Table> by lazy {
        ClassGraph()
            .enableClassInfo()
            .acceptPackages("dev.dertyp.db")
            .scan()
            .use { scanResult ->
                scanResult.getSubclasses(Table::class.java.name)
                    .loadClasses(Table::class.java)
                    .asSequence()
                    .mapNotNull {
                        try {
                            it.kotlin.objectInstance
                        } catch (_: Exception) {
                            null
                        }
                    }
                    .distinct()
                    .sortedBy { it.tableName }
                    .toList()
            }
    }

    fun referencesTo(target: Table): List<Column<*>> =
        all.flatMap { it.foreignKeys }
            .filter { it.targetTable == target && it.deleteRule != ReferenceOption.CASCADE }
            .flatMap { it.from }
            .distinct()
}

fun Column<*>.referencedUuids(): List<UUID> =
    table.select(this)
        .where { isNotNull() }
        .withDistinct()
        .mapNotNull { (it[this] as? EntityID<*>)?.value as? UUID }
