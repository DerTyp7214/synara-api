package dev.dertyp.services.release

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.data.MusicBrainzRelease
import dev.dertyp.data.MusicBrainzReleaseGroup
import dev.dertyp.data.ReleaseType
import dev.dertyp.db.*
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.services.ImageService
import dev.dertyp.services.StorageService
import dev.dertyp.services.import.Type
import dev.dertyp.services.metadata.*
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.*
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
import org.koin.test.get
import java.time.LocalDate
import java.util.UUID

class AppleMusicReleaseServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: AppleMusicReleaseService

    private lateinit var imageService: ImageService
    private lateinit var artistResolver: AppleMusicArtistResolver
    private lateinit var linkResolverService: LinkResolverService
    private lateinit var musicBrainzService: MusicBrainzService
    private lateinit var musicBrainzCacheService: MusicBrainzCacheService
    private lateinit var environment: ApplicationEnvironment
    private lateinit var appleMusicService: AppleMusicService

    private val testUserId = UUID.randomUUID()
    private val testArtistId = UUID.randomUUID()
    private val appleArtistId = "1100"

    fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                single<StorageService> { mockk(relaxed = true) }
                single<RedisCacheProvider.Config> { mockk(relaxed = true) }
                single { mockk<ImageService>(relaxed = true) }
                single { mockk<AppleMusicArtistResolver>(relaxed = true) }
                single { mockk<LinkResolverService>(relaxed = true) }
                single { mockk<MusicBrainzService>(relaxed = true) }
                single { mockk<MusicBrainzCacheService>(relaxed = true) }
                single { mockk<ApplicationEnvironment>(relaxed = true) }
                single { ProviderLinkService() }
            })
        }

        imageService = get()
        artistResolver = get()
        linkResolverService = get()
        musicBrainzService = get()
        musicBrainzCacheService = get()
        environment = get()

        coEvery { linkResolverService.batchResolve(any(), any(), any(), any()) } returns emptyList()
        coEvery { musicBrainzService.fetchReleasesByBarcode(any(), any()) } returns emptyList()

        appleMusicService = mockk(relaxed = true)
        mockkObject(MetadataService.Companion)
        every {
            MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any())
        } returns appleMusicService
        every { appleMusicService.catalogEnabled } returns true
        coEvery { artistResolver.resolve(testArtistId, any()) } returns appleArtistId

        database = TestDatabase.connect(dialect, "apple_release_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                ImageTable,
                ImageMetadataTable,
                ArtistTable,
                ArtistMusicBrainzTable,
                ArtistProviderTable,
                AlbumTable,
                AlbumArtistTable,
                AlbumMusicBrainzTable,
                AlbumProviderTable,
                SongTable,
                SongVariantTable,
                SongArtistTable,
                SongMusicBrainzTable,
                SongProviderTable,
                FollowedArtistTable,
                RecentReleaseTable,
                ProviderReleaseTable,
                ProviderLinkTable,
                RecentReleaseLinkTable,
                ProviderReleaseLinkTable,
                *allMusicBrainzTables
            )
        }

        service = AppleMusicReleaseService(environment)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        unmockkAll()
        TestDatabase.cleanUp()
    }

    private fun seedFollowedArtist(name: String = "Test Artist") {
        transaction(database) {
            UserTable.insert { it[id] = testUserId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = testArtistId; it[ArtistTable.name] = name }
            FollowedArtistTable.insert {
                it[FollowedArtistTable.userId] = testUserId
                it[FollowedArtistTable.artistId] = testArtistId
            }
        }
    }

    private fun catalogAlbum(
        id: String,
        title: String,
        releaseDate: LocalDate?,
        isSingle: Boolean = false,
        isComplete: Boolean = true,
        isCompilation: Boolean = false,
        upc: String? = null,
        url: String? = null,
        trackCount: Int = 10,
        image: IMetadataService.Image? = null,
        artistName: String = "Test Artist"
    ) = AppleMusicService.CatalogAlbum(
        id = id,
        title = title,
        artistName = artistName,
        artistIds = listOf(appleArtistId),
        releaseDate = releaseDate,
        isSingle = isSingle,
        isComplete = isComplete,
        isCompilation = isCompilation,
        upc = upc,
        url = url,
        trackCount = trackCount,
        image = image
    )

    private fun providerRows() = transaction(database) {
        ProviderReleaseTable.selectAll().map { row ->
            row[ProviderReleaseTable.externalId] to row
        }.toMap()
    }

    private fun seedRecentRelease(releaseGroupId: UUID, groupTitle: String) {
        transaction(database) {
            MBReleaseGroupTable.insert { it[id] = releaseGroupId; it[title] = groupTitle }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = releaseGroupId
                it[RecentReleaseTable.artistId] = testArtistId
                it[RecentReleaseTable.title] = groupTitle
            }
        }
    }

    private fun insertProviderLink(
        providerName: String,
        external: String,
        linkUrl: String,
        linkType: String? = Type.ALBUM.value
    ): UUID {
        val linkId = UUID.randomUUID()
        transaction(database) {
            ProviderLinkTable.insert {
                it[ProviderLinkTable.id] = linkId
                it[ProviderLinkTable.provider] = providerName
                it[ProviderLinkTable.externalId] = external
                it[ProviderLinkTable.type] = linkType
                it[ProviderLinkTable.rawUrl] = linkUrl
            }
        }
        return linkId
    }

    private fun seedGroupLink(releaseGroupId: UUID, providerName: String, external: String, linkUrl: String) {
        val linkRowId = insertProviderLink(providerName, external, linkUrl)
        transaction(database) {
            RecentReleaseLinkTable.insert {
                it[RecentReleaseLinkTable.releaseId] = releaseGroupId
                it[RecentReleaseLinkTable.linkId] = linkRowId
            }
        }
    }

    private fun groupLinkKeys(releaseGroupId: UUID): Map<String, String> = transaction(database) {
        RecentReleaseLinkTable
            .innerJoin(ProviderLinkTable, { RecentReleaseLinkTable.linkId }, { ProviderLinkTable.id })
            .select(ProviderLinkTable.provider, ProviderLinkTable.externalId)
            .where { RecentReleaseLinkTable.releaseId eq releaseGroupId }
            .associate { it[ProviderLinkTable.provider] to it[ProviderLinkTable.externalId] }
    }

    private fun releaseLinkUrls(externalId: String): List<String> = transaction(database) {
        val rowId = AppleMusicReleaseService.providerReleaseId("apple", externalId)
        ProviderReleaseLinkTable
            .innerJoin(ProviderLinkTable, { ProviderReleaseLinkTable.linkId }, { ProviderLinkTable.id })
            .select(ProviderLinkTable.rawUrl, ProviderLinkTable.provider)
            .where { ProviderReleaseLinkTable.providerReleaseId eq rowId }
            .orderBy(ProviderLinkTable.provider to SortOrder.ASC)
            .map { it[ProviderLinkTable.rawUrl] }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `skips everything when the catalog is disabled`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()
        every { appleMusicService.catalogEnabled } returns false

        val result = service.fetchFollowedArtistReleases()

        assertEquals(true, result["skipped"])
        assertEquals("catalog disabled", result["reason"])
        coVerify(exactly = 0) { artistResolver.resolve(any(), any()) }
        coVerify(exactly = 0) { appleMusicService.getArtistCatalogAlbums(any(), any()) }
        assertTrue(providerRows().isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `stores recent and upcoming releases and drops the ones outside the window`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()

        coEvery { appleMusicService.getArtistCatalogAlbums(appleArtistId, any()) } returns listOf(
            catalogAlbum("1", "Recent Album", LocalDate.now().minusDays(10)),
            catalogAlbum("2", "Upcoming Album", LocalDate.now().plusDays(30), isComplete = false),
            catalogAlbum("3", "Ancient Album", LocalDate.now().minusYears(3)),
            catalogAlbum("4", "Undated Album", null),
            catalogAlbum("5", "Far Future Album", LocalDate.now().plusDays(400))
        )

        val result = service.fetchFollowedArtistReleases()

        assertEquals(false, result["skipped"])
        assertEquals(1, result["artists"])
        assertEquals(2, result["stored"])
        assertEquals(1, result["upcoming"])

        val rows = providerRows()
        assertEquals(setOf("1", "2"), rows.keys)
        assertEquals("Recent Album", rows.getValue("1")[ProviderReleaseTable.title])
        assertFalse(rows.getValue("2")[ProviderReleaseTable.complete])
        assertEquals("Test Artist", rows.getValue("1")[ProviderReleaseTable.artistName])
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `keeps stored rows when the catalog request fails`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()

        coEvery { appleMusicService.getArtistCatalogAlbums(appleArtistId, any()) } returns listOf(
            catalogAlbum("1", "Recent Album", LocalDate.now().minusDays(10))
        )
        service.fetchFollowedArtistReleases()
        assertEquals(1, providerRows().size)

        coEvery { appleMusicService.getArtistCatalogAlbums(appleArtistId, any()) } returns null
        val result = service.fetchFollowedArtistReleases()

        assertEquals(0, result["stored"])
        assertEquals(1, providerRows().size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `matches the release group through an existing apple mapping`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()

        val releaseGroupId = UUID.randomUUID()
        seedRecentRelease(releaseGroupId, "Linked Album")
        seedGroupLink(releaseGroupId, "apple", "1", "https://music.apple.com/album/1")

        coEvery { appleMusicService.getArtistCatalogAlbums(appleArtistId, any()) } returns listOf(
            catalogAlbum("1", "Totally Different Title", LocalDate.now().minusDays(5))
        )

        val result = service.fetchFollowedArtistReleases()

        assertEquals(1, result["matched"])
        assertEquals(releaseGroupId, providerRows().getValue("1")[ProviderReleaseTable.releaseGroupId]?.value)
        coVerify(exactly = 0) { linkResolverService.batchResolve(any(), any(), any(), any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `matches the release group through the cached UPC and attaches the apple link`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            seedFollowedArtist()

            val mbGroupId = UUID.randomUUID()
            val mbReleaseId = UUID.randomUUID()
            transaction(database) {
                MBReleaseGroupTable.insert { it[id] = mbGroupId; it[title] = "Barcoded Album" }
                MBReleaseTable.insert {
                    it[id] = mbReleaseId
                    it[title] = "Barcoded Album"
                    it[barcode] = "00602445790000"
                    it[MBReleaseTable.releaseGroupId] = mbGroupId
                }
            }

            coEvery { appleMusicService.getArtistCatalogAlbums(appleArtistId, any()) } returns listOf(
                catalogAlbum(
                    "1",
                    "Barcoded Album",
                    LocalDate.now().minusDays(5),
                    upc = "00602445790000",
                    url = "https://music.apple.com/album/1"
                )
            )

            service.fetchFollowedArtistReleases()

            assertEquals(mbGroupId, providerRows().getValue("1")[ProviderReleaseTable.releaseGroupId]?.value)
            assertEquals("1", groupLinkKeys(mbGroupId)["apple"])
            assertEquals(listOf("https://music.apple.com/album/1"), releaseLinkUrls("1"))
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `matches the release group through the MusicBrainz barcode lookup`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()

        val releaseGroupId = UUID.randomUUID()
        seedRecentRelease(releaseGroupId, "Radar Album")

        val mbRelease = MusicBrainzRelease(
            id = UUID.randomUUID(),
            title = "Radar Album",
            barcode = "00602445790000",
            releaseGroup = MusicBrainzReleaseGroup(id = releaseGroupId, title = "Radar Album")
        )
        coEvery { musicBrainzService.fetchReleasesByBarcode(any(), any()) } returns listOf(mbRelease)

        coEvery { appleMusicService.getArtistCatalogAlbums(appleArtistId, any()) } returns listOf(
            catalogAlbum(
                "1",
                "Apple Only Title",
                LocalDate.now().minusDays(5),
                upc = "00602445790000",
                url = "https://music.apple.com/album/1"
            )
        )

        val result = service.fetchFollowedArtistReleases()

        assertEquals(1, result["matched"])
        assertEquals(releaseGroupId, providerRows().getValue("1")[ProviderReleaseTable.releaseGroupId]?.value)
        coVerify(exactly = 1) { musicBrainzCacheService.updateReleaseCache(mbRelease) }
        assertEquals("1", groupLinkKeys(releaseGroupId)["apple"])
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `keeps the row unmatched when the barcode release group is not on the radar`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            seedFollowedArtist()

            val radarGroupId = UUID.randomUUID()
            seedRecentRelease(radarGroupId, "Radar Album")

            val mbRelease = MusicBrainzRelease(
                id = UUID.randomUUID(),
                title = "Unknown Album",
                barcode = "00602445790000",
                releaseGroup = MusicBrainzReleaseGroup(id = UUID.randomUUID(), title = "Unknown Album")
            )
            coEvery { musicBrainzService.fetchReleasesByBarcode(any(), any()) } returns listOf(mbRelease)

            coEvery { appleMusicService.getArtistCatalogAlbums(appleArtistId, any()) } returns listOf(
                catalogAlbum(
                    "1",
                    "Apple Only Title",
                    LocalDate.now().minusDays(5),
                    upc = "00602445790000",
                    url = "https://music.apple.com/album/1"
                )
            )

            val result = service.fetchFollowedArtistReleases()

            assertEquals(0, result["matched"])
            assertNull(providerRows().getValue("1")[ProviderReleaseTable.releaseGroupId])
            coVerify(exactly = 1) { musicBrainzCacheService.updateReleaseCache(mbRelease) }
            assertTrue(groupLinkKeys(radarGroupId).isEmpty())
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `matches the release group through a resolved provider link`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()

        val releaseGroupId = UUID.randomUUID()
        seedRecentRelease(releaseGroupId, "Radar Album")
        seedGroupLink(releaseGroupId, "tidal", "456", "https://tidal.com/album/456")

        coEvery { appleMusicService.getArtistCatalogAlbums(appleArtistId, any()) } returns listOf(
            catalogAlbum(
                "1",
                "Apple Only Title",
                LocalDate.now().minusDays(5),
                upc = "00602445790000",
                url = "https://music.apple.com/album/1"
            )
        )
        coEvery { linkResolverService.batchResolve(any(), any(), any(), any()) } returns
                listOf("https://tidal.com/album/456")

        val result = service.fetchFollowedArtistReleases()

        assertEquals(1, result["matched"])
        assertEquals(releaseGroupId, providerRows().getValue("1")[ProviderReleaseTable.releaseGroupId]?.value)
        coVerify(exactly = 1) {
            linkResolverService.batchResolve(
                listOf("https://music.apple.com/album/1"),
                any(),
                "00602445790000",
                any()
            )
        }

        val links = groupLinkKeys(releaseGroupId)
        assertEquals("1", links["apple"])
        assertEquals("456", links["tidal"])
        assertEquals(
            listOf("https://music.apple.com/album/1", "https://tidal.com/album/456"),
            releaseLinkUrls("1")
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `matches the release group through a MusicBrainz relation of one of its releases`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            seedFollowedArtist()

            val releaseGroupId = UUID.randomUUID()
            val mbReleaseId = UUID.randomUUID()
            seedRecentRelease(releaseGroupId, "Radar Album")
            transaction(database) {
                MBReleaseTable.insert {
                    it[id] = mbReleaseId
                    it[title] = "Radar Album"
                    it[MBReleaseTable.releaseGroupId] = releaseGroupId
                }
                MBRelationProviderTable.insert {
                    it[MBRelationProviderTable.ownerId] = mbReleaseId
                    it[MBRelationProviderTable.provider] = "tidal"
                    it[MBRelationProviderTable.externalId] = "789"
                    it[MBRelationProviderTable.rawUrl] = "https://tidal.com/album/789"
                }
            }

            coEvery { appleMusicService.getArtistCatalogAlbums(appleArtistId, any()) } returns listOf(
                catalogAlbum(
                    "1",
                    "Apple Only Title",
                    LocalDate.now().minusDays(5),
                    url = "https://music.apple.com/album/1"
                )
            )
            coEvery { linkResolverService.batchResolve(any(), any(), any(), any()) } returns
                    listOf("https://tidal.com/album/789")

            service.fetchFollowedArtistReleases()

            assertEquals(releaseGroupId, providerRows().getValue("1")[ProviderReleaseTable.releaseGroupId]?.value)

            val links = groupLinkKeys(releaseGroupId)
            assertEquals("1", links["apple"])
            assertEquals("789", links["tidal"])
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `stores the mappings and the resolve time of an unmatched release`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()

        coEvery { appleMusicService.getArtistCatalogAlbums(appleArtistId, any()) } returns listOf(
            catalogAlbum(
                "1",
                "Lonely Album",
                LocalDate.now().minusDays(5),
                url = "https://music.apple.com/album/1"
            )
        )
        coEvery { linkResolverService.batchResolve(any(), any(), any(), any()) } returns
                listOf("https://open.spotify.com/album/abc")

        service.fetchFollowedArtistReleases()

        val row = providerRows().getValue("1")
        assertNull(row[ProviderReleaseTable.releaseGroupId])
        assertNotNull(row[ProviderReleaseTable.linksResolvedAt])
        assertEquals(
            listOf("https://music.apple.com/album/1", "https://open.spotify.com/album/abc"),
            releaseLinkUrls("1")
        )
        transaction(database) {
            assertEquals(0, RecentReleaseLinkTable.selectAll().count().toInt())
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `does not resolve the links again within the retry window`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()

        coEvery { appleMusicService.getArtistCatalogAlbums(appleArtistId, any()) } returns listOf(
            catalogAlbum(
                "1",
                "Lonely Album",
                LocalDate.now().minusDays(5),
                upc = "00602445790000",
                url = "https://music.apple.com/album/1"
            )
        )
        coEvery { linkResolverService.batchResolve(any(), any(), any(), any()) } returns
                listOf("https://open.spotify.com/album/abc")

        service.fetchFollowedArtistReleases()
        service.fetchFollowedArtistReleases()

        coVerify(exactly = 1) { linkResolverService.batchResolve(any(), any(), any(), any()) }
        coVerify(exactly = 1) { musicBrainzService.fetchReleasesByBarcode(any(), any()) }
        assertEquals(
            listOf("https://music.apple.com/album/1", "https://open.spotify.com/album/abc"),
            releaseLinkUrls("1")
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `never resolves the links of an already matched release`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()

        val releaseGroupId = UUID.randomUUID()
        seedRecentRelease(releaseGroupId, "Night Drive")
        seedGroupLink(releaseGroupId, "apple", "1", "https://music.apple.com/album/1")

        coEvery { appleMusicService.getArtistCatalogAlbums(appleArtistId, any()) } returns listOf(
            catalogAlbum("1", "Night Drive", LocalDate.now().minusDays(5), upc = "00602445790000")
        )

        service.fetchFollowedArtistReleases()

        coVerify(exactly = 0) { linkResolverService.batchResolve(any(), any(), any(), any()) }
        coVerify(exactly = 0) { musicBrainzService.fetchReleasesByBarcode(any(), any()) }
        assertNull(providerRows().getValue("1")[ProviderReleaseTable.linksResolvedAt])
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `keeps a release with the same title but different identifiers separate`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()

        val releaseGroupId = UUID.randomUUID()
        seedRecentRelease(releaseGroupId, "Night Drive")
        seedGroupLink(releaseGroupId, "tidal", "111", "https://tidal.com/album/111")

        coEvery { appleMusicService.getArtistCatalogAlbums(appleArtistId, any()) } returns listOf(
            catalogAlbum(
                "1",
                "Night Drive",
                LocalDate.now().minusDays(5),
                upc = "00602445790000",
                url = "https://music.apple.com/album/1"
            )
        )
        coEvery { linkResolverService.batchResolve(any(), any(), any(), any()) } returns
                listOf("https://tidal.com/album/222")

        val result = service.fetchFollowedArtistReleases()

        assertEquals(0, result["matched"])
        assertNull(providerRows().getValue("1")[ProviderReleaseTable.releaseGroupId])
        assertEquals(mapOf("tidal" to "111"), groupLinkKeys(releaseGroupId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `merging fills the missing cover and release date only`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()

        val emptyGroupId = UUID.randomUUID()
        val filledGroupId = UUID.randomUUID()
        val providerImageId = UUID.randomUUID()
        val groupImageId = UUID.randomUUID()
        val emptyRowId = AppleMusicReleaseService.providerReleaseId("apple", "10")
        val filledRowId = AppleMusicReleaseService.providerReleaseId("apple", "11")

        transaction(database) {
            ImageTable.insert { it[id] = providerImageId; it[path] = "a"; it[imageHash] = "h1"; it[origin] = "a" }
            ImageTable.insert { it[id] = groupImageId; it[path] = "b"; it[imageHash] = "h2"; it[origin] = "b" }
            listOf(emptyRowId to "10", filledRowId to "11").forEach { (rowId, external) ->
                ProviderReleaseTable.insert {
                    it[id] = rowId
                    it[provider] = "apple"
                    it[externalId] = external
                    it[ProviderReleaseTable.artistId] = testArtistId
                    it[title] = "Merged Release $external"
                    it[imageId] = EntityID(providerImageId, ImageTable)
                    it[releaseDate] = 4000L
                }
            }
        }

        seedRecentRelease(emptyGroupId, "Empty Group")
        seedRecentRelease(filledGroupId, "Filled Group")
        transaction(database) {
            RecentReleaseTable.update({ RecentReleaseTable.releaseId eq filledGroupId }) {
                it[RecentReleaseTable.imageId] = EntityID(groupImageId, ImageTable)
                it[RecentReleaseTable.releaseDate] = 9000L
            }
        }

        val appleLink = insertProviderLink("apple", "10", "https://music.apple.com/album/10")
        transaction(database) {
            ProviderReleaseLinkTable.insert {
                it[ProviderReleaseLinkTable.providerReleaseId] = emptyRowId
                it[ProviderReleaseLinkTable.linkId] = appleLink
            }
        }

        service.mergeIntoReleaseGroup(emptyRowId, emptyGroupId)
        service.mergeIntoReleaseGroup(emptyRowId, emptyGroupId)
        service.mergeIntoReleaseGroup(filledRowId, filledGroupId)

        transaction(database) {
            val empty = RecentReleaseTable.selectAll()
                .where { RecentReleaseTable.releaseId eq emptyGroupId }
                .single()
            assertEquals(providerImageId, empty[RecentReleaseTable.imageId]?.value)
            assertEquals(4000L, empty[RecentReleaseTable.releaseDate])

            val filled = RecentReleaseTable.selectAll()
                .where { RecentReleaseTable.releaseId eq filledGroupId }
                .single()
            assertEquals(groupImageId, filled[RecentReleaseTable.imageId]?.value)
            assertEquals(9000L, filled[RecentReleaseTable.releaseDate])
        }

        assertEquals(emptyGroupId, providerRows().getValue("10")[ProviderReleaseTable.releaseGroupId]?.value)
        assertEquals(mapOf("apple" to "10"), groupLinkKeys(emptyGroupId))
        assertTrue(groupLinkKeys(filledGroupId).isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `linkedReleaseUrls returns the apple url and the attached links`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()

        val releaseGroupId = UUID.randomUUID()
        seedRecentRelease(releaseGroupId, "Merged Group")

        val rowId = AppleMusicReleaseService.providerReleaseId("apple", "10")
        transaction(database) {
            ProviderReleaseTable.insert {
                it[id] = rowId
                it[provider] = "apple"
                it[externalId] = "10"
                it[ProviderReleaseTable.artistId] = testArtistId
                it[title] = "Merged Release"
                it[url] = "https://music.apple.com/album/10"
                it[ProviderReleaseTable.releaseGroupId] = releaseGroupId
            }
        }
        val tidalLink = insertProviderLink("tidal", "99", "https://tidal.com/album/99")
        transaction(database) {
            ProviderReleaseLinkTable.insert {
                it[ProviderReleaseLinkTable.providerReleaseId] = rowId
                it[ProviderReleaseLinkTable.linkId] = tidalLink
            }
        }

        assertEquals(
            listOf("https://music.apple.com/album/10", "https://tidal.com/album/99"),
            service.linkedReleaseUrls(releaseGroupId)
        )
        assertTrue(service.linkedReleaseUrls(UUID.randomUUID()).isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `findUnlinkedAppleRelease matches by album id, barcode and link key`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()

        val byIdRow = AppleMusicReleaseService.providerReleaseId("apple", "10")
        val byBarcodeRow = AppleMusicReleaseService.providerReleaseId("apple", "11")
        val byLinkRow = AppleMusicReleaseService.providerReleaseId("apple", "12")
        val linkedRow = AppleMusicReleaseService.providerReleaseId("apple", "13")
        val releaseGroupId = UUID.randomUUID()
        seedRecentRelease(releaseGroupId, "Taken Group")

        transaction(database) {
            listOf(
                Triple(byIdRow, "10", null),
                Triple(byBarcodeRow, "11", "00602445790000"),
                Triple(byLinkRow, "12", null),
                Triple(linkedRow, "13", null)
            ).forEach { (rowId, external, barcode) ->
                ProviderReleaseTable.insert {
                    it[id] = rowId
                    it[provider] = "apple"
                    it[externalId] = external
                    it[ProviderReleaseTable.artistId] = testArtistId
                    it[title] = "Release $external"
                    it[upc] = barcode
                    if (rowId == linkedRow) it[ProviderReleaseTable.releaseGroupId] = releaseGroupId
                }
            }
        }
        val tidalLink = insertProviderLink("tidal", "99", "https://tidal.com/album/99")
        transaction(database) {
            ProviderReleaseLinkTable.insert {
                it[ProviderReleaseLinkTable.providerReleaseId] = byLinkRow
                it[ProviderReleaseLinkTable.linkId] = tidalLink
            }
        }

        assertEquals(
            byIdRow,
            service.findUnlinkedAppleRelease(testArtistId, setOf("10"), emptySet(), emptySet())
        )
        assertEquals(
            byBarcodeRow,
            service.findUnlinkedAppleRelease(testArtistId, emptySet(), setOf("00602445790000"), emptySet())
        )
        assertEquals(
            byLinkRow,
            service.findUnlinkedAppleRelease(testArtistId, emptySet(), emptySet(), setOf("tidal" to "99"))
        )
        assertNull(service.findUnlinkedAppleRelease(testArtistId, setOf("13"), emptySet(), emptySet()))
        assertNull(service.findUnlinkedAppleRelease(UUID.randomUUID(), setOf("10"), emptySet(), emptySet()))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `links the library album through the album provider table`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()

        val libraryAlbumId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert { it[id] = libraryAlbumId; it[name] = "Owned Album" }
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = libraryAlbumId
                it[AlbumArtistTable.artistId] = testArtistId
            }
            AlbumProviderTable.insert {
                it[AlbumProviderTable.albumId] = libraryAlbumId
                it[AlbumProviderTable.provider] = "apple"
                it[AlbumProviderTable.externalId] = "1"
                it[AlbumProviderTable.rawUrl] = "https://music.apple.com/album/1"
            }
        }

        coEvery { appleMusicService.getArtistCatalogAlbums(appleArtistId, any()) } returns listOf(
            catalogAlbum("1", "Owned Album", LocalDate.now().minusDays(5))
        )

        service.fetchFollowedArtistReleases()

        assertEquals(libraryAlbumId, providerRows().getValue("1")[ProviderReleaseTable.albumId]?.value)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `links the library album through the gamdl originalId`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()

        val libraryAlbumId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[id] = libraryAlbumId
                it[name] = "Imported Album"
                it[originalId] = "appleMusic:1"
            }
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = libraryAlbumId
                it[AlbumArtistTable.artistId] = testArtistId
            }
        }

        coEvery { appleMusicService.getArtistCatalogAlbums(appleArtistId, any()) } returns listOf(
            catalogAlbum("1", "Imported Album", LocalDate.now().minusDays(5))
        )

        service.fetchFollowedArtistReleases()

        assertEquals(libraryAlbumId, providerRows().getValue("1")[ProviderReleaseTable.albumId]?.value)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `links the library song for a single`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()

        val libraryAlbumId = UUID.randomUUID()
        val librarySongId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert { it[id] = libraryAlbumId; it[name] = "Host Album" }
            SongTable.insert {
                it[id] = librarySongId
                it[title] = "Owned Single"
                it[SongTable.albumId] = libraryAlbumId
            }
            SongArtistTable.insert {
                it[SongArtistTable.songId] = librarySongId
                it[SongArtistTable.artistId] = testArtistId
            }
            SongProviderTable.insert {
                it[SongProviderTable.songId] = librarySongId
                it[SongProviderTable.provider] = "apple"
                it[SongProviderTable.externalId] = "1"
                it[SongProviderTable.rawUrl] = "https://music.apple.com/album/1"
            }
        }

        coEvery { appleMusicService.getArtistCatalogAlbums(appleArtistId, any()) } returns listOf(
            catalogAlbum("1", "Owned Single", LocalDate.now().minusDays(5), isSingle = true)
        )

        service.fetchFollowedArtistReleases()

        val row = providerRows().getValue("1")
        assertEquals(librarySongId, row[ProviderReleaseTable.songId]?.value)
        assertNull(row[ProviderReleaseTable.albumId])
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `derives the release type from the title and the single flag`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()

        coEvery { appleMusicService.getArtistCatalogAlbums(appleArtistId, any()) } returns listOf(
            catalogAlbum("1", "Sun Sessions - EP", LocalDate.now().minusDays(5)),
            catalogAlbum("2", "Solo Track - Single", LocalDate.now().minusDays(5), isSingle = true),
            catalogAlbum("3", "The Long Play", LocalDate.now().minusDays(5))
        )

        service.fetchFollowedArtistReleases()

        val rows = providerRows()
        assertEquals(ReleaseType.EP, rows.getValue("1")[ProviderReleaseTable.type])
        assertEquals(ReleaseType.Single, rows.getValue("2")[ProviderReleaseTable.type])
        assertEquals(ReleaseType.Album, rows.getValue("3")[ProviderReleaseTable.type])
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `persists the artwork once and keeps it on the next run`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()

        val imageId = UUID.randomUUID()
        transaction(database) {
            ImageTable.insert {
                it[id] = imageId
                it[path] = "cover.jpg"
                it[imageHash] = "hash"
                it[origin] = "https://is1-ssl.mzstatic.com/image/thumb/cover/9999x9999bb.jpg"
            }
        }

        coEvery { appleMusicService.getArtistCatalogAlbums(appleArtistId, any()) } returns listOf(
            catalogAlbum(
                "1",
                "Artwork Album",
                LocalDate.now().minusDays(5),
                image = IMetadataService.Image(
                    url = "https://is1-ssl.mzstatic.com/image/thumb/cover/9999x9999bb.jpg",
                    width = 9999,
                    height = 9999
                )
            )
        )
        coEvery { imageService.createBatch(any()) } returns mapOf("hash" to imageId)

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchArtworkBytes(any()) } returns byteArrayOf(1, 2, 3)

        spiedService.fetchFollowedArtistReleases()
        spiedService.fetchFollowedArtistReleases()

        coVerify(exactly = 1) { imageService.createBatch(any()) }

        val rows = providerRows()
        assertEquals(1, rows.size)
        assertEquals(imageId, rows.getValue("1")[ProviderReleaseTable.imageId]?.value)
        assertNotNull(rows.getValue("1")[ProviderReleaseTable.lastImageFetch])
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `unlinks provider release images of unfollowed artists only`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()

        val unfollowedArtistId = UUID.randomUUID()
        val followedImageId = UUID.randomUUID()
        val unfollowedImageId = UUID.randomUUID()
        val followedRelease = UUID.randomUUID()
        val unfollowedRelease = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = unfollowedArtistId; it[name] = "Unfollowed" }
            ImageTable.insert { it[id] = followedImageId; it[path] = "a"; it[imageHash] = "h1"; it[origin] = "a" }
            ImageTable.insert { it[id] = unfollowedImageId; it[path] = "b"; it[imageHash] = "h2"; it[origin] = "b" }
            ProviderReleaseTable.insert {
                it[id] = followedRelease
                it[provider] = "apple"
                it[externalId] = "10"
                it[ProviderReleaseTable.artistId] = testArtistId
                it[title] = "Followed Release"
                it[imageId] = EntityID(followedImageId, ImageTable)
                it[lastImageFetch] = 1000L
            }
            ProviderReleaseTable.insert {
                it[id] = unfollowedRelease
                it[provider] = "apple"
                it[externalId] = "11"
                it[ProviderReleaseTable.artistId] = unfollowedArtistId
                it[title] = "Unfollowed Release"
                it[imageId] = EntityID(unfollowedImageId, ImageTable)
                it[lastImageFetch] = 1000L
            }
        }

        val unlinked = service.unlinkUnfollowedProviderReleaseImages()
        assertEquals(1, unlinked)

        transaction(database) {
            val rows = ProviderReleaseTable.selectAll().associate {
                it[ProviderReleaseTable.id].value to (it[ProviderReleaseTable.imageId]?.value to it[ProviderReleaseTable.lastImageFetch])
            }
            assertEquals(followedImageId, rows.getValue(followedRelease).first)
            assertEquals(1000L, rows.getValue(followedRelease).second)
            assertNull(rows.getValue(unfollowedRelease).first)
            assertNull(rows.getValue(unfollowedRelease).second)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `returns zero when the artist cannot be resolved`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()
        coEvery { artistResolver.resolve(testArtistId, any()) } returns null

        assertEquals(0, service.fetchArtistReleases(testArtistId))
        coVerify(exactly = 0) { appleMusicService.getArtistCatalogAlbums(any(), any()) }
        assertTrue(providerRows().isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchArtistReleases returns zero for an unknown artist and when disabled`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()

        assertEquals(0, service.fetchArtistReleases(UUID.randomUUID()))

        every { appleMusicService.catalogEnabled } returns false
        assertEquals(0, service.fetchArtistReleases(testArtistId))
        coVerify(exactly = 0) { appleMusicService.getArtistCatalogAlbums(any(), any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchArtistReleases stores the releases of a single artist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()

        coEvery { appleMusicService.getArtistCatalogAlbums(appleArtistId, HttpClientPriority.LOW) } returns listOf(
            catalogAlbum("1", "Direct Album", LocalDate.now().minusDays(5))
        )

        assertEquals(1, service.fetchArtistReleases(testArtistId))
        assertEquals(setOf("1"), providerRows().keys)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `re-running keeps a single row per album`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedFollowedArtist()

        coEvery { appleMusicService.getArtistCatalogAlbums(appleArtistId, any()) } returns listOf(
            catalogAlbum("1", "Stable Album", LocalDate.now().minusDays(5), url = "https://music.apple.com/album/1")
        )

        service.fetchFollowedArtistReleases()
        val firstAddedAt = providerRows().getValue("1")[ProviderReleaseTable.addedAt]

        service.fetchFollowedArtistReleases()

        val rows = providerRows()
        assertEquals(1, rows.size)
        assertEquals(firstAddedAt, rows.getValue("1")[ProviderReleaseTable.addedAt])
        assertNotNull(rows.getValue("1")[ProviderReleaseTable.lastUpdate])
        assertEquals(listOf("https://music.apple.com/album/1"), releaseLinkUrls("1"))
        transaction(database) {
            assertEquals(1, ProviderLinkTable.selectAll().count().toInt())
            assertEquals(1, ProviderReleaseLinkTable.selectAll().count().toInt())
        }
    }
}
