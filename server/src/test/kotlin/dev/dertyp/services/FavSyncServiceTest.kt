package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.User
import dev.dertyp.db.FavSyncTable
import dev.dertyp.db.UserTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.Date
import java.util.UUID

class FavSyncServiceTest {
    private lateinit var database: Database
    private val service = FavSyncService()

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "favsync_test")
        transaction(database) {
            SchemaUtils.create(UserTable, FavSyncTable)
        }
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `insertFavSync should upsert status`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = User(UUID.randomUUID(), "user", passwordHash = "hash")
        transaction(database) {
            UserTable.insert {
                it[id] = user.id
                it[username] = user.username
                it[passwordHash] = user.passwordHash
            }
        }

        val date = Date()
        service.insertFavSync(user, ISyncService.SyncServiceType.tidal, date)
        
        val latest = service.getLatestFavSync(user, ISyncService.SyncServiceType.tidal)
        assertNotNull(latest)
        assertEquals(date.time / 1000, latest!!.syncedAt.time / 1000)
    }
}
