package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.services.ScheduledTaskLogService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class BackfillListenUpdatedAtTest : KoinTest {
    private lateinit var database: Database

    private fun setup(dialect: DbDialect) {
        val logService = mockk<ScheduledTaskLogService>(relaxed = true)
        every { logService.startLog(any(), any()) } returns EntityID(UUID.randomUUID(), ScheduledTaskLogTable)
        startKoin { modules(module { single { logService } }) }

        database = TestDatabase.connect(dialect, "backfill_listen_updated_at_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                ListenBrainzUserTable,
                ImageTable,
                AlbumTable,
                SongTable,
                ListenTable,
                ScheduledTaskLogTable,
            )
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `sets updatedAt to listenedAt only where it is unset`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val unset = UUID.randomUUID()
        val alreadySet = UUID.randomUUID()
        transaction(database) {
            ListenTable.insert {
                it[id] = unset
                it[listenedAt] = 1000
                it[listenSource] = ListenSource.LOCAL
            }
            ListenTable.insert {
                it[id] = alreadySet
                it[listenedAt] = 2000
                it[listenSource] = ListenSource.LOCAL
                it[updatedAt] = 5000
            }
        }

        BackfillListenUpdatedAt().migrate()

        val values = transaction(database) {
            ListenTable.select(ListenTable.id, ListenTable.updatedAt)
                .where { (ListenTable.id eq unset) or (ListenTable.id eq alreadySet) }
                .associate { it[ListenTable.id].value to it[ListenTable.updatedAt] }
        }
        assertEquals(1000L, values[unset])
        assertEquals(5000L, values[alreadySet])
    }
}
