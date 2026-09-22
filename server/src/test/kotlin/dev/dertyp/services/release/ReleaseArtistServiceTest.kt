package dev.dertyp.services.release

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID

class ReleaseArtistServiceTest {
    private lateinit var database: Database
    private lateinit var service: ReleaseArtistService

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "release_artist_test")
        transaction(database) {
            SchemaUtils.create(
                ImageTable,
                ArtistTable,
                AlbumTable,
                SongTable, SongVariantTable,
                MBReleaseGroupTable,
                RecentReleaseTable,
                ProviderReleaseTable,
                ReleaseArtistTable
            )
        }

        service = ReleaseArtistService()
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    private fun insertArtist(artistId: UUID, name: String = "Artist") {
        transaction(database) {
            ArtistTable.insert { it[ArtistTable.id] = artistId; it[ArtistTable.name] = name }
        }
    }

    private fun insertReleaseGroup(groupId: UUID, artistId: UUID) {
        transaction(database) {
            MBReleaseGroupTable.insert { it[MBReleaseGroupTable.id] = groupId; it[title] = "Group" }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = groupId
                it[RecentReleaseTable.artistId] = artistId
                it[title] = "Release"
            }
        }
    }

    private fun insertProviderRelease(rowId: UUID, artistId: UUID) {
        transaction(database) {
            ProviderReleaseTable.insert {
                it[ProviderReleaseTable.id] = rowId
                it[ProviderReleaseTable.provider] = "apple"
                it[ProviderReleaseTable.externalId] = rowId.toString()
                it[ProviderReleaseTable.artistId] = artistId
                it[ProviderReleaseTable.title] = "Apple Release"
                it[ProviderReleaseTable.url] = "https://music.apple.com/album/$rowId"
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `linking a release group is idempotent`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val ownerId = UUID.randomUUID()
        insertArtist(ownerId)
        val groupId = UUID.randomUUID()
        insertReleaseGroup(groupId, ownerId)

        service.linkGroup(groupId, listOf(ownerId, ownerId))
        service.linkGroup(groupId, listOf(ownerId))

        transaction(database) {
            assertEquals(1L, ReleaseArtistTable.selectAll().where { ReleaseArtistTable.artistId eq ownerId }.count())
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `linking a provider release is idempotent`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val ownerId = UUID.randomUUID()
        insertArtist(ownerId)
        val rowId = UUID.randomUUID()
        insertProviderRelease(rowId, ownerId)

        service.linkProviderRelease(rowId, listOf(ownerId, ownerId))
        service.linkProviderRelease(rowId, listOf(ownerId))

        transaction(database) {
            assertEquals(1L, ReleaseArtistTable.selectAll().where { ReleaseArtistTable.artistId eq ownerId }.count())
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `groupArtistIds and providerReleaseArtistIds return the linked artists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistA = UUID.randomUUID()
        val artistB = UUID.randomUUID()
        insertArtist(artistA, "A")
        insertArtist(artistB, "B")

        val groupId = UUID.randomUUID()
        insertReleaseGroup(groupId, artistA)
        val rowId = UUID.randomUUID()
        insertProviderRelease(rowId, artistA)

        service.linkGroup(groupId, listOf(artistA, artistB))
        service.linkProviderRelease(rowId, listOf(artistA, artistB))

        assertEquals(setOf(artistA, artistB), service.groupArtistIds(listOf(groupId))[groupId]?.toSet())
        assertEquals(setOf(artistA, artistB), service.providerReleaseArtistIds(listOf(rowId))[rowId]?.toSet())
        assertTrue(service.groupArtistIds(emptyList()).isEmpty())
        assertTrue(service.providerReleaseArtistIds(emptyList()).isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `groupIdsForArtist and providerReleaseIdsForArtist return the linked releases`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        insertArtist(artistId)

        val groupId = UUID.randomUUID()
        insertReleaseGroup(groupId, artistId)
        val rowId = UUID.randomUUID()
        insertProviderRelease(rowId, artistId)

        service.linkGroup(groupId, listOf(artistId))
        service.linkProviderRelease(rowId, listOf(artistId))

        assertEquals(listOf(groupId), service.groupIdsForArtist(artistId))
        assertEquals(listOf(rowId), service.providerReleaseIdsForArtist(artistId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleting the artist cascades its links`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        assumeTrue(dialect == DbDialect.POSTGRES, "FK cascade requires foreign key enforcement")

        val artistId = UUID.randomUUID()
        insertArtist(artistId)

        val groupId = UUID.randomUUID()
        insertReleaseGroup(groupId, artistId)
        val rowId = UUID.randomUUID()
        insertProviderRelease(rowId, artistId)

        service.linkGroup(groupId, listOf(artistId))
        service.linkProviderRelease(rowId, listOf(artistId))

        transaction(database) {
            ArtistTable.deleteWhere { ArtistTable.id eq artistId }
        }

        assertTrue(service.groupIdsForArtist(artistId).isEmpty())
        assertTrue(service.providerReleaseIdsForArtist(artistId).isEmpty())
        transaction(database) {
            assertEquals(0L, ReleaseArtistTable.selectAll().count())
        }
    }
}
