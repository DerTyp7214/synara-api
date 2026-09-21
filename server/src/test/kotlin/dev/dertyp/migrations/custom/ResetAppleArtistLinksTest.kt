package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.services.ImageService
import dev.dertyp.services.ScheduledTaskLogService
import dev.dertyp.services.StorageService
import dev.dertyp.services.import.Type
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
import kotlin.io.path.Path

class ResetAppleArtistLinksTest : KoinTest {
    private lateinit var database: Database
    private lateinit var imageService: ImageService
    private lateinit var tempDir: File

    private fun setup(dialect: DbDialect) {
        tempDir = Files.createTempDirectory("reset_apple_links_test").toFile()

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

        database = TestDatabase.connect(dialect, "reset_apple_links_test")
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

        imageService = ImageService(storageService, redisConfig)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
        tempDir.deleteRecursively()
    }

    private suspend fun createStoredImage(content: String, origin: String): Pair<UUID, File> {
        val id = imageService.createImage(content.toByteArray(), origin)
        val path = transaction(database) {
            ImageTable.select(ImageTable.path).where { ImageTable.id eq id }.single()[ImageTable.path]
        }
        return id to Path(tempDir.absolutePath, path).toFile()
    }

    private fun insertArtist(id: UUID, artistName: String) {
        transaction(database) {
            ArtistTable.insert {
                it[ArtistTable.id] = id
                it[ArtistTable.name] = artistName
            }
        }
    }

    private fun insertArtistProvider(artist: UUID, providerName: String, external: String, linkType: String?) {
        transaction(database) {
            ArtistProviderTable.insert {
                it[ArtistProviderTable.artistId] = artist
                it[ArtistProviderTable.provider] = providerName
                it[ArtistProviderTable.externalId] = external
                it[ArtistProviderTable.type] = linkType
                it[ArtistProviderTable.rawUrl] = "https://music.apple.com/artist/$external"
            }
        }
    }

    private fun insertProviderRelease(
        rowId: UUID,
        providerName: String,
        external: String,
        artist: UUID,
        image: UUID?
    ) {
        transaction(database) {
            ProviderReleaseTable.insert {
                it[ProviderReleaseTable.id] = rowId
                it[ProviderReleaseTable.provider] = providerName
                it[ProviderReleaseTable.externalId] = external
                it[ProviderReleaseTable.artistId] = artist
                it[ProviderReleaseTable.title] = "Release $external"
                it[ProviderReleaseTable.imageId] = image?.let { id -> EntityID(id, ImageTable) }
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

    private fun insertProviderLink(providerName: String, external: String): UUID {
        val linkRowId = UUID.randomUUID()
        transaction(database) {
            ProviderLinkTable.insert {
                it[ProviderLinkTable.id] = linkRowId
                it[ProviderLinkTable.provider] = providerName
                it[ProviderLinkTable.externalId] = external
                it[ProviderLinkTable.type] = Type.ALBUM.value
                it[ProviderLinkTable.rawUrl] = "https://$providerName.example/album/$external"
            }
        }
        return linkRowId
    }

    private fun attachToGroup(releaseGroupId: UUID, link: UUID) {
        transaction(database) {
            RecentReleaseLinkTable.insert {
                it[RecentReleaseLinkTable.releaseId] = releaseGroupId
                it[RecentReleaseLinkTable.linkId] = link
            }
        }
    }

    private fun attachToProviderRelease(rowId: UUID, link: UUID) {
        transaction(database) {
            ProviderReleaseLinkTable.insert {
                it[ProviderReleaseLinkTable.providerReleaseId] = rowId
                it[ProviderReleaseLinkTable.linkId] = link
            }
        }
    }

    private fun artistProviderRows() = transaction(database) {
        ArtistProviderTable.selectAll().map {
            Triple(
                it[ArtistProviderTable.provider],
                it[ArtistProviderTable.externalId],
                it[ArtistProviderTable.type]
            )
        }.toSet()
    }

    private fun providerReleaseRows() = transaction(database) {
        ProviderReleaseTable.selectAll().map {
            it[ProviderReleaseTable.provider] to it[ProviderReleaseTable.externalId]
        }.toSet()
    }

    private fun groupLinkRows() = transaction(database) {
        RecentReleaseLinkTable
            .innerJoin(ProviderLinkTable, { RecentReleaseLinkTable.linkId }, { ProviderLinkTable.id })
            .select(ProviderLinkTable.provider, ProviderLinkTable.externalId)
            .map { it[ProviderLinkTable.provider] to it[ProviderLinkTable.externalId] }
            .toSet()
    }

    private fun providerReleaseLinkRows() = transaction(database) {
        ProviderReleaseLinkTable
            .innerJoin(ProviderLinkTable, { ProviderReleaseLinkTable.linkId }, { ProviderLinkTable.id })
            .select(ProviderLinkTable.provider, ProviderLinkTable.externalId)
            .map { it[ProviderLinkTable.provider] to it[ProviderLinkTable.externalId] }
            .toSet()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `removes apple artist links, provider releases, mappings and their unreferenced images`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val artist = UUID.randomUUID()
            val otherArtist = UUID.randomUUID()
            val appleReleaseId = UUID.randomUUID()
            val appleSharedReleaseId = UUID.randomUUID()
            val tidalReleaseId = UUID.randomUUID()
            val matchedGroup = UUID.randomUUID()
            val unrelatedGroup = UUID.randomUUID()

            val (appleImage, appleFile) = createStoredImage("apple cover", "https://apple.example/1")
            val (sharedImage, sharedFile) = createStoredImage("shared cover", "https://apple.example/2")
            val (tidalImage, tidalFile) = createStoredImage("tidal cover", "https://tidal.example/1")

            insertArtist(artist, "Artist")
            insertArtist(otherArtist, "Other Artist")
            transaction(database) {
                AlbumTable.insert {
                    it[AlbumTable.name] = "Album"
                    it[AlbumTable.cover] = EntityID(sharedImage, ImageTable)
                }
            }

            insertArtistProvider(artist, "apple", "1234", Type.ARTIST.value)
            insertArtistProvider(artist, "apple", "5678", Type.ALBUM.value)
            insertArtistProvider(artist, "tidal", "9999", Type.ARTIST.value)
            insertArtistProvider(otherArtist, "apple", "4321", Type.ARTIST.value)

            insertProviderRelease(appleReleaseId, "apple", "111", artist, appleImage)
            insertProviderRelease(appleSharedReleaseId, "apple", "222", artist, sharedImage)
            insertProviderRelease(tidalReleaseId, "tidal", "333", otherArtist, tidalImage)

            insertReleaseGroup(matchedGroup)
            insertReleaseGroup(unrelatedGroup)

            val appleLink = insertProviderLink("apple", "111")
            val appleOtherLink = insertProviderLink("apple", "999")
            val tidalLink = insertProviderLink("tidal", "111")
            val tidalReleaseLink = insertProviderLink("tidal", "333")

            attachToGroup(matchedGroup, appleLink)
            attachToGroup(unrelatedGroup, appleOtherLink)
            attachToGroup(unrelatedGroup, tidalLink)

            attachToProviderRelease(appleReleaseId, appleLink)
            attachToProviderRelease(appleReleaseId, tidalLink)
            attachToProviderRelease(tidalReleaseId, tidalReleaseLink)

            assertTrue(appleFile.exists())

            ResetAppleArtistLinks().migrate()

            assertEquals(
                setOf(
                    Triple("apple", "5678", Type.ALBUM.value),
                    Triple("tidal", "9999", Type.ARTIST.value)
                ),
                artistProviderRows()
            )
            assertEquals(setOf("tidal" to "333"), providerReleaseRows())
            assertEquals(setOf("apple" to "999", "tidal" to "111"), groupLinkRows())
            assertEquals(setOf("tidal" to "333"), providerReleaseLinkRows())

            transaction(database) {
                val remainingImages = ImageTable.selectAll().map { it[ImageTable.id].value }.toSet()
                assertFalse(appleImage in remainingImages)
                assertTrue(sharedImage in remainingImages)
                assertTrue(tidalImage in remainingImages)
            }

            assertFalse(appleFile.exists())
            assertTrue(sharedFile.exists())
            assertTrue(tidalFile.exists())
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `re-running the migration is a no-op`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artist = UUID.randomUUID()
        val appleReleaseId = UUID.randomUUID()
        val matchedGroup = UUID.randomUUID()
        val (appleImage, appleFile) = createStoredImage("apple cover", "https://apple.example/3")

        insertArtist(artist, "Artist")
        insertArtistProvider(artist, "apple", "1234", Type.ARTIST.value)
        insertProviderRelease(appleReleaseId, "apple", "111", artist, appleImage)
        insertReleaseGroup(matchedGroup)

        val appleLink = insertProviderLink("apple", "111")
        attachToGroup(matchedGroup, appleLink)
        attachToProviderRelease(appleReleaseId, appleLink)

        ResetAppleArtistLinks().migrate()
        ResetAppleArtistLinks().migrate()

        assertTrue(artistProviderRows().isEmpty())
        assertTrue(providerReleaseRows().isEmpty())
        assertTrue(groupLinkRows().isEmpty())
        assertTrue(providerReleaseLinkRows().isEmpty())

        transaction(database) {
            assertEquals(0, ImageTable.selectAll().count().toInt())
        }
        assertFalse(appleFile.exists())
    }
}
