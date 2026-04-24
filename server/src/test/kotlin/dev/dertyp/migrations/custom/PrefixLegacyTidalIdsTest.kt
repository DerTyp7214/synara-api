package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ImageTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest
import java.util.UUID

class PrefixLegacyTidalIdsTest : KoinTest {
    private lateinit var database: Database

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "migration_test")
        transaction(database) {
            SchemaUtils.create(ImageTable, AlbumTable)
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `PrefixLegacyTidalIds should prefix non-prefixed originalIds`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        transaction(database) {
            AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Legacy Album"
                it[originalId] = "12345"
            }
            AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Already Prefixed Album"
                it[originalId] = "youtube:abcde"
            }
            AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Tidal Prefixed Album"
                it[originalId] = "tidal:67890"
            }
            AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Empty OriginalId"
                it[originalId] = ""
            }
            AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Null OriginalId"
                it[originalId] = null
            }
        }

        val migration = PrefixLegacyTidalIds()
        migration.migrate()

        transaction(database) {
            val albums = AlbumTable.selectAll().associate { it[AlbumTable.name] to it[AlbumTable.originalId] }
            assertEquals("tidal:12345", albums["Legacy Album"])
            assertEquals("youtube:abcde", albums["Already Prefixed Album"])
            assertEquals("tidal:67890", albums["Tidal Prefixed Album"])
            assertEquals("", albums["Empty OriginalId"])
            assertEquals(null, albums["Null OriginalId"])
        }
    }
}
