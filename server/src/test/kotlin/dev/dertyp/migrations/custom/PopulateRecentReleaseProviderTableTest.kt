package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.MBReleaseGroupTable
import dev.dertyp.db.RecentReleaseProviderTable
import dev.dertyp.db.RecentReleaseTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID
import kotlin.test.assertEquals

class PopulateRecentReleaseProviderTableTest {
    private lateinit var database: Database

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "populate_rr_providers_test")
        transaction(database) {
            SchemaUtils.create(
                ArtistTable,
                MBReleaseGroupTable,
                RecentReleaseTable,
                RecentReleaseProviderTable
            )
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `migration should populate provider table from json links`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val releaseId1 = UUID.randomUUID()
        val artistId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Artist"
            }
            MBReleaseGroupTable.insert {
                it[id] = releaseId1
                it[title] = "Release 1"
            }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = releaseId1
                it[RecentReleaseTable.artistId] = artistId
                it[title] = "Release 1"
                it[links] = "[\"https://open.spotify.com/album/123\", \"https://tidal.com/album/456\"]"
            }
        }

        val migration = PopulateRecentReleaseProviderTable()
        migration.migrate()

        transaction(database) {
            val providers = RecentReleaseProviderTable.selectAll()
                .where { RecentReleaseProviderTable.releaseId eq releaseId1 }
                .associate { it[RecentReleaseProviderTable.provider] to it[RecentReleaseProviderTable.externalId] }
            
            assertEquals(2, providers.size)
            assertEquals("123", providers["spotify"])
            assertEquals("456", providers["tidal"])
        }
    }
}
