package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.services.ImageService
import dev.dertyp.services.ScheduledTaskLogService
import dev.dertyp.services.StorageService
import io.mockk.every
import io.mockk.justRun
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
import java.io.File
import java.nio.file.Files
import java.util.UUID

class ResetUnmatchedProviderReleaseResolvesTest : KoinTest {
    private lateinit var database: Database
    private lateinit var tempDir: File

    private fun setup(dialect: DbDialect) {
        tempDir = Files.createTempDirectory("reset_unmatched_provider_release_resolves_test").toFile()

        val storageService = mockk<StorageService>()
        val redisConfig = mockk<RedisCacheProvider.Config>()
        every { storageService.imagesPath } returns tempDir.absolutePath
        justRun { storageService.invalidate(any()) }
        every { redisConfig.host } returns "none"

        val logService = mockk<ScheduledTaskLogService>(relaxed = true)
        every { logService.startLog(any(), any()) } returns EntityID(UUID.randomUUID(), ScheduledTaskLogTable)

        startKoin {
            modules(module {
                single { logService }
                single { storageService }
                single { redisConfig }
                single { ImageService(get(), get()) }
            })
        }

        database = TestDatabase.connect(dialect, "reset_unmatched_provider_release_resolves_test")
        transaction(database) {
            SchemaUtils.create(
                *allMusicBrainzTables,
                ImageTable,
                ImageMetadataTable,
                AlbumTable,
                ArtistTable,
                ArtistProviderTable,
                SongTable, SongVariantTable,
                PlaylistTable,
                UserPlaylistTable,
                UserTable,
                MBReleaseGroupCoverTable,
                FollowedArtistTable,
                RecentReleaseTable,
                ProviderReleaseTable,
                ProviderLinkTable,
                RecentReleaseLinkTable,
                ProviderReleaseLinkTable,
                AnimatedImageTable,
                CollectionTable,
                RadioChannelTable,
                ScheduledTaskLogTable,
                PodcastShowTable,
                PodcastEpisodeTable,
            )
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
        tempDir.deleteRecursively()
    }

    private fun insertArtist(id: UUID, artistName: String) {
        transaction(database) {
            ArtistTable.insert {
                it[ArtistTable.id] = id
                it[ArtistTable.name] = artistName
            }
        }
    }

    private fun insertReleaseGroup(releaseGroupId: UUID) {
        transaction(database) {
            MBReleaseGroupTable.insert {
                it[MBReleaseGroupTable.id] = releaseGroupId
                it[MBReleaseGroupTable.title] = "Group $releaseGroupId"
            }
        }
    }

    private fun insertProviderRelease(
        rowId: UUID,
        external: String,
        artist: UUID,
        releaseGroup: UUID?,
        linksResolvedAt: Long?
    ) {
        transaction(database) {
            ProviderReleaseTable.insert {
                it[ProviderReleaseTable.id] = rowId
                it[ProviderReleaseTable.provider] = "apple"
                it[ProviderReleaseTable.externalId] = external
                it[ProviderReleaseTable.artistId] = artist
                it[ProviderReleaseTable.artistName] = "Artist"
                it[ProviderReleaseTable.title] = "Release $external"
                it[ProviderReleaseTable.releaseGroupId] = releaseGroup?.let { id -> EntityID(id, MBReleaseGroupTable) }
                it[ProviderReleaseTable.linksResolvedAt] = linksResolvedAt
            }
        }
    }

    private fun linksResolvedAt(rowId: UUID): Long? = transaction(database) {
        ProviderReleaseTable
            .select(ProviderReleaseTable.linksResolvedAt)
            .where { ProviderReleaseTable.id eq rowId }
            .single()[ProviderReleaseTable.linksResolvedAt]
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `clears links_resolved_at only for unmatched provider releases that were resolved`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val artist = UUID.randomUUID()
            val releaseGroup = UUID.randomUUID()
            val unmatchedResolvedId = UUID.randomUUID()
            val matchedResolvedId = UUID.randomUUID()
            val unmatchedUnresolvedId = UUID.randomUUID()

            insertArtist(artist, "Artist")
            insertReleaseGroup(releaseGroup)

            insertProviderRelease(unmatchedResolvedId, "111", artist, null, 123L)
            insertProviderRelease(matchedResolvedId, "222", artist, releaseGroup, 456L)
            insertProviderRelease(unmatchedUnresolvedId, "333", artist, null, null)

            ResetUnmatchedProviderReleaseResolves().migrate()

            assertNull(linksResolvedAt(unmatchedResolvedId))
            assertEquals(456L, linksResolvedAt(matchedResolvedId))
            assertNull(linksResolvedAt(unmatchedUnresolvedId))
        }
}
