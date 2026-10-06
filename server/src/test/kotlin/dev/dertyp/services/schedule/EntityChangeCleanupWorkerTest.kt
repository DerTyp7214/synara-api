package dev.dertyp.services.schedule

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.config.EntityChangeConfig
import dev.dertyp.config.ServerConfig
import dev.dertyp.core.db.dbQuery
import dev.dertyp.data.EntityChangeAspect
import dev.dertyp.data.EntityChangeKind
import dev.dertyp.data.EntityType
import dev.dertyp.db.EntityChangeScopeTable
import dev.dertyp.db.EntityChangeTable
import dev.dertyp.db.UserEntityChangeTable
import dev.dertyp.db.UserTable
import dev.dertyp.services.EntityChangeService
import dev.dertyp.testing.insertUser
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID
import kotlin.time.Duration.Companion.days

class EntityChangeCleanupWorkerTest : KoinTest {

    private fun setup(dialect: DbDialect, retentionDays: Long) = runBlocking {
        TestDatabase.connect(dialect, "entity_change_cleanup_worker_test")
        dbQuery {
            SchemaUtils.create(UserTable, EntityChangeTable, UserEntityChangeTable, EntityChangeScopeTable)
        }
        startKoin {
            modules(module {
                single { ServerConfig(MapApplicationConfig()) }
                single { EntityChangeService(EntityChangeConfig(retentionDays)) }
            })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun insertChange(entity: UUID, at: Long, owner: UUID) {
        val change = EntityChangeTable.insertAndGetId {
            it[entityType] = EntityType.SONG
            it[entityId] = entity
            it[aspect] = EntityChangeAspect.DATA
            it[kind] = EntityChangeKind.UPDATED
            it[changedAt] = at
        }
        EntityChangeScopeTable.insert {
            it[changeId] = change
            it[scopeType] = EntityType.ALBUM
            it[scopeId] = UUID.randomUUID()
        }
        UserEntityChangeTable.insert {
            it[userId] = owner
            it[entityType] = EntityType.SONG
            it[entityId] = entity
            it[aspect] = EntityChangeAspect.LIKE
            it[kind] = EntityChangeKind.UPDATED
            it[changedAt] = at
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the worker deletes only rows older than the retention in both tables and their scopes`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect, retentionDays = 10)
        val now = System.currentTimeMillis()
        val expired = listOf(UUID.randomUUID(), UUID.randomUUID())
        val current = listOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        dbQuery {
            val owner = insertUser()
            expired.forEachIndexed { index, entity -> insertChange(entity, now - (11 + index).days.inWholeMilliseconds, owner) }
            current.forEachIndexed { index, entity -> insertChange(entity, now - (9 - index).days.inWholeMilliseconds, owner) }
        }

        val result = EntityChangeCleanupWorker().run()

        assertEquals(mapOf<String, Any?>("deletedEntityChanges" to 2, "deletedUserEntityChanges" to 2), result)
        dbQuery {
            assertEquals(current.toSet(), EntityChangeTable.selectAll().map { it[EntityChangeTable.entityId] }.toSet())
            assertEquals(
                current.toSet(),
                UserEntityChangeTable.selectAll().map { it[UserEntityChangeTable.entityId] }.toSet()
            )
            assertEquals(3L, EntityChangeScopeTable.selectAll().count())
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `after the cleanup every scope row has its change row and every surviving change row has its scopes`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect, retentionDays = 10)
        val now = System.currentTimeMillis()
        val expired = List(3) { UUID.randomUUID() }
        val current = List(4) { UUID.randomUUID() }
        dbQuery {
            val owner = insertUser()
            expired.forEachIndexed { index, entity -> insertChange(entity, now - (11 + index).days.inWholeMilliseconds, owner) }
            current.forEachIndexed { index, entity -> insertChange(entity, now - (9 - index).days.inWholeMilliseconds, owner) }
        }
        val scopesBefore = dbQuery {
            EntityChangeScopeTable.selectAll().map {
                Triple(
                    it[EntityChangeScopeTable.changeId].value,
                    it[EntityChangeScopeTable.scopeType],
                    it[EntityChangeScopeTable.scopeId]
                )
            }
        }

        EntityChangeCleanupWorker().run()

        dbQuery {
            val changes = EntityChangeTable.selectAll().associate { it[EntityChangeTable.id].value to it[EntityChangeTable.entityId] }
            val scopesAfter = EntityChangeScopeTable.selectAll().map {
                Triple(
                    it[EntityChangeScopeTable.changeId].value,
                    it[EntityChangeScopeTable.scopeType],
                    it[EntityChangeScopeTable.scopeId]
                )
            }
            assertEquals(current.toSet(), changes.values.toSet())
            assertEquals(emptyList<UUID>(), scopesAfter.map { it.first }.filter { it !in changes })
            assertEquals(scopesBefore.filter { it.first in changes }.toSet(), scopesAfter.toSet())
            assertEquals(changes.keys, scopesAfter.map { it.first }.toSet())
        }
    }
}
