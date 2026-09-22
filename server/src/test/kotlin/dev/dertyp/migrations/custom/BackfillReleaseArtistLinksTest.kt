package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.services.ScheduledTaskLogService
import dev.dertyp.services.release.ReleaseArtistService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class BackfillReleaseArtistLinksTest : KoinTest {
    private lateinit var database: Database

    private fun setup(dialect: DbDialect) {
        val logService = mockk<ScheduledTaskLogService>(relaxed = true)
        every { logService.startLog(any(), any()) } returns EntityID(UUID.randomUUID(), ScheduledTaskLogTable)

        startKoin {
            modules(module {
                single { logService }
                single { ReleaseArtistService() }
            })
        }

        database = TestDatabase.connect(dialect, "backfill_release_artist_links_test")
        transaction(database) {
            SchemaUtils.create(
                ImageTable,
                ImageMetadataTable,
                ArtistTable,
                AlbumTable,
                SongTable, SongVariantTable,
                MBReleaseGroupTable,
                RecentReleaseTable,
                ProviderReleaseTable,
                ReleaseArtistTable,
                ScheduledTaskLogTable,
            )
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun insertArtist(artistId: UUID) {
        transaction(database) {
            ArtistTable.insert {
                it[ArtistTable.id] = artistId
                it[ArtistTable.name] = "Artist"
            }
        }
    }

    private fun insertReleaseGroup(groupId: UUID, artist: UUID) {
        transaction(database) {
            MBReleaseGroupTable.insert {
                it[MBReleaseGroupTable.id] = groupId
                it[MBReleaseGroupTable.title] = "Group"
            }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = groupId
                it[RecentReleaseTable.artistId] = artist
                it[RecentReleaseTable.artistName] = "Artist"
                it[RecentReleaseTable.title] = "Release"
            }
        }
    }

    private fun insertProviderRelease(rowId: UUID, artist: UUID) {
        transaction(database) {
            ProviderReleaseTable.insert {
                it[ProviderReleaseTable.id] = rowId
                it[ProviderReleaseTable.provider] = "apple"
                it[ProviderReleaseTable.externalId] = rowId.toString()
                it[ProviderReleaseTable.artistId] = artist
                it[ProviderReleaseTable.artistName] = "Artist"
                it[ProviderReleaseTable.title] = "Apple Release"
            }
        }
    }

    private fun links(): List<Pair<UUID?, UUID?>> = transaction(database) {
        ReleaseArtistTable.selectAll().map {
            it[ReleaseArtistTable.releaseGroupId]?.value to it[ReleaseArtistTable.providerReleaseId]?.value
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `links every release to its owning artist exactly once`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val groupId = UUID.randomUUID()
        val providerReleaseId = UUID.randomUUID()

        insertArtist(artistId)
        insertReleaseGroup(groupId, artistId)
        insertProviderRelease(providerReleaseId, artistId)

        BackfillReleaseArtistLinks().migrate()
        BackfillReleaseArtistLinks().migrate()

        val rows = links()
        assertEquals(2, rows.size)
        assertEquals(listOf(groupId), rows.mapNotNull { it.first })
        assertEquals(listOf(providerReleaseId), rows.mapNotNull { it.second })

        val artistIds = transaction(database) {
            ReleaseArtistTable.selectAll().map { it[ReleaseArtistTable.artistId].value }.toSet()
        }
        assertEquals(setOf(artistId), artistIds)
    }
}
