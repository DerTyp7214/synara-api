package dev.dertyp.services.release

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID

class ProviderLinkServiceTest {
    private lateinit var database: Database
    private lateinit var service: ProviderLinkService

    private val artistId = UUID.randomUUID()

    fun setup(dialect: DbDialect) {
        val owner = artistId
        database = TestDatabase.connect(dialect, "provider_link_test")
        transaction(database) {
            SchemaUtils.create(
                ImageTable,
                ArtistTable,
                AlbumTable,
                SongTable, SongVariantTable,
                MBReleaseGroupTable,
                ProviderReleaseTable,
                ProviderLinkTable,
                RecentReleaseLinkTable,
                ProviderReleaseLinkTable
            )
            ArtistTable.insert { it[ArtistTable.id] = owner; it[ArtistTable.name] = "Artist" }
        }

        service = ProviderLinkService()
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    private fun insertReleaseGroup(groupId: UUID) {
        transaction(database) {
            MBReleaseGroupTable.insert { it[MBReleaseGroupTable.id] = groupId; it[title] = "Group" }
        }
    }

    private fun insertProviderRelease(rowId: UUID) {
        val owner = artistId
        transaction(database) {
            ProviderReleaseTable.insert {
                it[ProviderReleaseTable.id] = rowId
                it[ProviderReleaseTable.provider] = "apple"
                it[ProviderReleaseTable.externalId] = rowId.toString()
                it[ProviderReleaseTable.artistId] = owner
                it[ProviderReleaseTable.title] = "Apple Release"
                it[ProviderReleaseTable.url] = "https://music.apple.com/album/$rowId"
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the same url is stored once`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val first = dbQuery { service.linkIdTx("https://tidal.com/album/456") }
        val second = dbQuery { service.linkIdTx("https://tidal.com/album/456") }

        assertEquals(first, second)

        transaction(database) {
            val rows = ProviderLinkTable.selectAll().toList()
            assertEquals(1, rows.size)
            assertEquals("tidal", rows[0][ProviderLinkTable.provider])
            assertEquals("456", rows[0][ProviderLinkTable.externalId])
            assertEquals("https://tidal.com/album/456", rows[0][ProviderLinkTable.rawUrl])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `linkIdsTx skips blanks and duplicates`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val ids = dbQuery {
            service.linkIdsTx(
                listOf(
                    "https://tidal.com/album/456",
                    "",
                    "  ",
                    "https://tidal.com/album/456",
                    "https://spotify.com/album/123"
                )
            )
        }

        assertEquals(2, ids.size)
        transaction(database) {
            assertEquals(2L, ProviderLinkTable.selectAll().count())
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `attaching and detaching recent release links`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val groupId = UUID.randomUUID()
        insertReleaseGroup(groupId)

        val linkIds = dbQuery {
            val ids = service.linkIdsTx(listOf("https://tidal.com/album/456", "https://spotify.com/album/123"))
            service.attachRecentReleaseTx(groupId, ids)
            service.attachRecentReleaseTx(groupId, ids)
            ids
        }

        assertEquals(2, linkIds.size)
        transaction(database) {
            assertEquals(2L, RecentReleaseLinkTable.selectAll().where { RecentReleaseLinkTable.releaseId eq groupId }.count())
        }

        assertEquals(
            listOf("https://spotify.com/album/123", "https://tidal.com/album/456"),
            service.recentReleaseUrls(listOf(groupId))[groupId]
        )

        dbQuery { service.detachRecentReleaseTx(groupId) }

        transaction(database) {
            assertEquals(0L, RecentReleaseLinkTable.selectAll().count())
            assertEquals(2L, ProviderLinkTable.selectAll().count())
        }
        assertTrue(service.recentReleaseUrls(listOf(groupId)).isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `provider release urls and keys are ordered`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val rowId = UUID.randomUUID()
        val otherRowId = UUID.randomUUID()
        insertProviderRelease(rowId)
        insertProviderRelease(otherRowId)

        dbQuery {
            service.attachProviderReleaseTx(
                rowId,
                service.linkIdsTx(
                    listOf(
                        "https://tidal.com/album/456",
                        "https://music.apple.com/album/789",
                        "https://spotify.com/album/123"
                    )
                )
            )
            service.attachProviderReleaseTx(otherRowId, service.linkIdsTx(listOf("https://spotify.com/album/999")))
        }

        val urls = service.providerReleaseUrls(listOf(rowId, otherRowId))
        assertEquals(
            listOf(
                "https://music.apple.com/album/789",
                "https://spotify.com/album/123",
                "https://tidal.com/album/456"
            ),
            urls[rowId]
        )
        assertEquals(listOf("https://spotify.com/album/999"), urls[otherRowId])

        val keys = service.providerReleaseLinkKeys(listOf(rowId))
        assertEquals(
            listOf("apple" to "789", "spotify" to "123", "tidal" to "456"),
            keys[rowId]
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `empty inputs return empty maps`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        assertTrue(service.recentReleaseUrls(emptyList()).isEmpty())
        assertTrue(service.providerReleaseUrls(emptyList()).isEmpty())
        assertTrue(service.providerReleaseLinkKeys(emptyList()).isEmpty())
    }
}
