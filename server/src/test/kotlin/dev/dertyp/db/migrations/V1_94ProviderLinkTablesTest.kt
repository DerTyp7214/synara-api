package dev.dertyp.db.migrations

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.sql.Connection
import java.time.Instant
import java.util.UUID

private object LegacyProviderTable : Table("recent_release_provider") {
    val releaseId = reference("releaseId", MBReleaseGroupTable.id, onDelete = ReferenceOption.CASCADE)
    val provider = varchar("provider", 64)
    val externalId = text("externalId").default("")
    val type = varchar("type", 32).nullable()
    val rawUrl = text("rawUrl")
    val addedAt = long("addedAt").clientDefault { Instant.now().toEpochMilli() }

    override val primaryKey = PrimaryKey(releaseId, provider, externalId)
}

class V1_94ProviderLinkTablesTest {
    private lateinit var database: Database

    private fun setup(dialect: DbDialect, withLegacyTable: Boolean) {
        database = TestDatabase.connect(dialect, "provider_link_migration_test")
        transaction(database) {
            SchemaUtils.create(
                MBReleaseGroupTable,
                ProviderLinkTable,
                RecentReleaseLinkTable
            )
            if (withLegacyTable) SchemaUtils.create(LegacyProviderTable)
        }
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `legacy provider rows become links and mappings`(dialect: DbDialect) {
        setup(dialect, withLegacyTable = true)
        val groupA = UUID.randomUUID()
        val groupB = UUID.randomUUID()

        transaction(database) {
            listOf(groupA, groupB).forEach { group ->
                MBReleaseGroupTable.insert { it[MBReleaseGroupTable.id] = group; it[title] = "Group" }
            }
            LegacyProviderTable.insert {
                it[releaseId] = groupA
                it[provider] = "tidal"
                it[externalId] = "456"
                it[type] = "album"
                it[rawUrl] = "https://tidal.com/album/456"
                it[addedAt] = 100L
            }
            LegacyProviderTable.insert {
                it[releaseId] = groupB
                it[provider] = "tidal"
                it[externalId] = "456"
                it[type] = "album"
                it[rawUrl] = "https://tidal.com/album/456"
                it[addedAt] = 100L
            }
            LegacyProviderTable.insert {
                it[releaseId] = groupA
                it[provider] = "spotify"
                it[externalId] = "123"
                it[rawUrl] = "https://spotify.com/album/123"
                it[addedAt] = 200L
            }
        }

        transaction(database) {
            V1_94__ProviderLinkTables().copyLegacyLinks(connection.connection as Connection)
        }

        transaction(database) {
            val links = ProviderLinkTable.selectAll()
                .orderBy(ProviderLinkTable.provider to SortOrder.ASC)
                .toList()
            assertEquals(2, links.size)
            assertEquals("spotify", links[0][ProviderLinkTable.provider])
            assertEquals("123", links[0][ProviderLinkTable.externalId])
            assertEquals("https://spotify.com/album/123", links[0][ProviderLinkTable.rawUrl])
            assertNull(links[0][ProviderLinkTable.type])
            assertEquals(200L, links[0][ProviderLinkTable.addedAt])
            assertEquals("tidal", links[1][ProviderLinkTable.provider])
            assertEquals("album", links[1][ProviderLinkTable.type])
            assertEquals(100L, links[1][ProviderLinkTable.addedAt])

            val tidalLinkId = links[1][ProviderLinkTable.id].value
            val spotifyLinkId = links[0][ProviderLinkTable.id].value

            val mappings = RecentReleaseLinkTable.selectAll()
                .map { it[RecentReleaseLinkTable.releaseId].value to it[RecentReleaseLinkTable.linkId].value }
            assertEquals(3, mappings.size)
            assertTrue(mappings.contains(groupA to tidalLinkId))
            assertTrue(mappings.contains(groupB to tidalLinkId))
            assertTrue(mappings.contains(groupA to spotifyLinkId))
            assertFalse(mappings.contains(groupB to spotifyLinkId))
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a database without the legacy table is left untouched`(dialect: DbDialect) {
        setup(dialect, withLegacyTable = false)

        transaction(database) {
            V1_94__ProviderLinkTables().copyLegacyLinks(connection.connection as Connection)
        }

        transaction(database) {
            assertEquals(0L, ProviderLinkTable.selectAll().count())
            assertEquals(0L, RecentReleaseLinkTable.selectAll().count())
        }
    }
}
