package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.UserTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID

class PrefixLegacyTidalIdsTest {
    private lateinit var database: Database

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "migration_test")
        transaction(database) {
            SchemaUtils.create(UserTable, ImageTable, ArtistTable, AlbumTable)
        }
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `migrate should prefix legacy IDs with tidal`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        val legacyId = "12345"
        
        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Legacy Album"
                it[originalId] = legacyId
            }
            AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Modern Album"
                it[originalId] = "tidal:67890"
            }
        }

        val migration = PrefixLegacyTidalIds()
        migration.migrate()

        transaction(database) {
            val updated = AlbumTable.selectAll().where { AlbumTable.id eq albumId }.single()
            assertEquals("tidal:12345", updated[AlbumTable.originalId])
            
            val modernCount = AlbumTable.selectAll().where { AlbumTable.originalId eq "tidal:67890" }.count()
            assertEquals(1L, modernCount)
        }
    }
}
