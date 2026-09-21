package dev.dertyp.services.metadata

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.time.LocalDate
import java.util.UUID

class AppleMusicArtistResolverTest : KoinTest {

    private lateinit var database: Database
    private lateinit var resolver: AppleMusicArtistResolver
    private lateinit var appleMusicService: AppleMusicService
    private lateinit var environment: ApplicationEnvironment

    private fun setup(dialect: DbDialect, catalogEnabled: Boolean = true) {
        startKoin { modules(module { }) }

        appleMusicService = mockk(relaxed = true)
        environment = mockk(relaxed = true)
        every { appleMusicService.catalogEnabled } returns catalogEnabled

        mockkObject(MetadataService.Companion)
        every {
            MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any())
        } returns appleMusicService

        database = TestDatabase.connect(dialect, "apple_artist_resolver_test")
        transaction(database) {
            SchemaUtils.create(
                ImageTable,
                AnimatedImageTable,
                ArtistTable,
                ArtistAliasTable,
                AlbumTable,
                SongTable,
                AlbumArtistTable,
                SongArtistTable,
                AlbumProviderTable,
                SongProviderTable,
                ArtistProviderTable,
                MBAreaTable,
                MBArtistTable,
                ArtistMusicBrainzTable,
                MBRelationProviderTable
            )
        }

        resolver = AppleMusicArtistResolver(environment)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        unmockkAll()
        TestDatabase.cleanUp()
    }

    private fun insertArtist(name: String = "Test Artist"): UUID {
        val id = UUID.randomUUID()
        ArtistTable.insert {
            it[ArtistTable.id] = id
            it[ArtistTable.name] = name
        }
        return id
    }

    private fun insertAlbum(
        artists: List<UUID>,
        barcode: String? = null,
        original: String? = null,
        released: String? = null
    ): UUID {
        val id = UUID.randomUUID()
        AlbumTable.insert {
            it[AlbumTable.id] = id
            it[AlbumTable.name] = "Album"
            it[AlbumTable.barcode] = barcode
            it[AlbumTable.originalId] = original
            it[AlbumTable.releaseDate] = released
        }
        artists.forEach { artist ->
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = id
                it[AlbumArtistTable.artistId] = artist
            }
        }
        return id
    }

    private fun insertSong(album: UUID, artists: List<UUID>, code: String? = null, at: Long = 1_000L): UUID {
        val id = UUID.randomUUID()
        SongTable.insert {
            it[SongTable.id] = id
            it[SongTable.title] = "Song"
            it[SongTable.albumId] = album
            it[SongTable.isrc] = code
            it[SongTable.inserted] = at
        }
        artists.forEach { artist ->
            SongArtistTable.insert {
                it[SongArtistTable.songId] = id
                it[SongArtistTable.artistId] = artist
            }
        }
        return id
    }

    private fun linkAlbumProvider(album: UUID, external: String, name: String = "apple", kind: String? = "album") {
        AlbumProviderTable.insert {
            it[AlbumProviderTable.albumId] = album
            it[AlbumProviderTable.provider] = name
            it[AlbumProviderTable.externalId] = external
            it[AlbumProviderTable.type] = kind
            it[AlbumProviderTable.rawUrl] = "https://music.apple.com/album/$external"
        }
    }

    private fun linkSongProvider(song: UUID, external: String, name: String = "apple", kind: String? = "track") {
        SongProviderTable.insert {
            it[SongProviderTable.songId] = song
            it[SongProviderTable.provider] = name
            it[SongProviderTable.externalId] = external
            it[SongProviderTable.type] = kind
            it[SongProviderTable.rawUrl] = "https://music.apple.com/song/$external"
        }
    }

    private fun linkMusicBrainz(artist: UUID, name: String = "Test Artist"): UUID {
        val mbId = UUID.randomUUID()
        MBArtistTable.insert {
            it[MBArtistTable.id] = mbId
            it[MBArtistTable.name] = name
            it[MBArtistTable.sortName] = name
        }
        ArtistMusicBrainzTable.insert {
            it[ArtistMusicBrainzTable.artistId] = artist
            it[ArtistMusicBrainzTable.musicBrainzId] = mbId
        }
        return mbId
    }

    private fun insertRelation(owner: UUID, external: String, name: String = "apple", kind: String? = "artist") {
        MBRelationProviderTable.insert {
            it[MBRelationProviderTable.ownerId] = owner
            it[MBRelationProviderTable.provider] = name
            it[MBRelationProviderTable.externalId] = external
            it[MBRelationProviderTable.type] = kind
            it[MBRelationProviderTable.rawUrl] = "https://music.apple.com/artist/$external"
        }
    }

    private fun storedRow(artist: UUID): ResultRow? = transaction(database) {
        ArtistProviderTable.selectAll()
            .where { ArtistProviderTable.artistId eq artist }
            .singleOrNull()
    }

    private fun catalogAlbum(artistIds: List<String>, id: String = "1") = AppleMusicService.CatalogAlbum(
        id = id,
        title = "Album",
        artistName = "Test Artist",
        artistIds = artistIds,
        releaseDate = LocalDate.of(2024, 1, 1),
        isSingle = false,
        isComplete = true,
        isCompilation = false,
        upc = null,
        url = "https://music.apple.com/us/album/album/$id",
        trackCount = 10,
        image = null
    )

    private fun catalogSong(artistIds: List<String>, id: String = "2") = AppleMusicService.CatalogSongRef(
        id = id,
        artistIds = artistIds,
        albumIds = listOf("1")
    )

    private fun verifyNoCatalogLookups() {
        coVerify(exactly = 0) { appleMusicService.getCatalogAlbumsByIds(any(), any()) }
        coVerify(exactly = 0) { appleMusicService.getCatalogSongsByIds(any(), any()) }
        coVerify(exactly = 0) { appleMusicService.getCatalogAlbumsByUpc(any(), any()) }
        coVerify(exactly = 0) { appleMusicService.getCatalogSongsByIsrc(any(), any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `resolve returns the persisted apple id without calling the catalog`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = transaction(database) {
            val id = insertArtist()
            ArtistProviderTable.insert {
                it[ArtistProviderTable.artistId] = id
                it[ArtistProviderTable.provider] = "apple"
                it[ArtistProviderTable.externalId] = "555"
                it[ArtistProviderTable.type] = "artist"
                it[ArtistProviderTable.rawUrl] = "https://music.apple.com/artist/555"
            }
            id
        }

        assertEquals("555", resolver.resolve(artistId))
        verifyNoCatalogLookups()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `resolve uses the MusicBrainz relation and persists the link`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = transaction(database) {
            val id = insertArtist()
            val mbId = linkMusicBrainz(id)
            insertRelation(mbId, "1234")
            id
        }

        assertEquals("1234", resolver.resolve(artistId))

        val row = storedRow(artistId)
        assertEquals("apple", row?.get(ArtistProviderTable.provider))
        assertEquals("1234", row?.get(ArtistProviderTable.externalId))
        assertEquals("artist", row?.get(ArtistProviderTable.type))
        assertEquals("https://music.apple.com/artist/1234", row?.get(ArtistProviderTable.rawUrl))
        verifyNoCatalogLookups()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `resolve ignores relations of other providers and non artist types`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = transaction(database) {
            val id = insertArtist()
            val mbId = linkMusicBrainz(id)
            insertRelation(mbId, "999", name = "spotify")
            insertRelation(mbId, "888", kind = "album")
            id
        }

        assertNull(resolver.resolve(artistId))
        assertNull(storedRow(artistId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `resolve accepts the single apple artist of a sole credited song and reuses it afterwards`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val artistId = transaction(database) {
            val id = insertArtist()
            val albumId = insertAlbum(listOf(id))
            insertSong(albumId, listOf(id), code = "USUM71900764")
            id
        }
        coEvery { appleMusicService.getCatalogSongsByIsrc("USUM71900764", any()) } returns
                listOf(catalogSong(listOf("111")))

        assertEquals("111", resolver.resolve(artistId))
        assertEquals("111", storedRow(artistId)?.get(ArtistProviderTable.externalId))

        assertEquals("111", resolver.resolve(artistId))
        coVerify(exactly = 1) { appleMusicService.getCatalogSongsByIsrc(any(), any()) }
        coVerify(exactly = 0) { appleMusicService.getCatalogAlbumsByUpc(any(), any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `resolve accepts the single apple artist of a sole credited album barcode`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = transaction(database) {
            val id = insertArtist()
            insertAlbum(listOf(id), barcode = "00602445790234")
            id
        }
        coEvery { appleMusicService.getCatalogAlbumsByUpc("00602445790234", any()) } returns
                listOf(catalogAlbum(listOf("111")))

        assertEquals("111", resolver.resolve(artistId))
        assertEquals("111", storedRow(artistId)?.get(ArtistProviderTable.externalId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `resolve rejects a compilation whose apple credits are fewer than ours`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = transaction(database) {
            val id = insertArtist()
            val others = (1..4).map { insertArtist("Other $it") }
            insertAlbum(listOf(id) + others, barcode = "00602445790234")
            id
        }
        coEvery { appleMusicService.getCatalogAlbumsByUpc("00602445790234", any()) } returns
                listOf(catalogAlbum(listOf("999")))

        assertNull(resolver.resolve(artistId))
        assertNull(storedRow(artistId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `resolve accepts the artist shared by two credit consistent songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = transaction(database) {
            val id = insertArtist()
            val first = insertArtist("First Partner")
            val second = insertArtist("Second Partner")
            val albumId = insertAlbum(listOf(id))
            insertSong(albumId, listOf(id, first), code = "USUM71900001", at = 2_000L)
            insertSong(albumId, listOf(id, second), code = "USUM71900002", at = 1_000L)
            id
        }
        coEvery { appleMusicService.getCatalogSongsByIsrc("USUM71900001", any()) } returns
                listOf(catalogSong(listOf("111", "999")))
        coEvery { appleMusicService.getCatalogSongsByIsrc("USUM71900002", any()) } returns
                listOf(catalogSong(listOf("222", "999")))

        assertEquals("999", resolver.resolve(artistId))
        assertEquals("999", storedRow(artistId)?.get(ArtistProviderTable.externalId))
        coVerify(exactly = 2) { appleMusicService.getCatalogSongsByIsrc(any(), any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `resolve rejects songs where apple lists only the primary artist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = transaction(database) {
            val id = insertArtist()
            val first = insertArtist("First Partner")
            val second = insertArtist("Second Partner")
            val albumId = insertAlbum(listOf(id))
            insertSong(albumId, listOf(id, first), code = "USUM71900001", at = 2_000L)
            insertSong(albumId, listOf(id, second), code = "USUM71900002", at = 1_000L)
            id
        }
        coEvery { appleMusicService.getCatalogSongsByIsrc("USUM71900001", any()) } returns
                listOf(catalogSong(listOf("111")))
        coEvery { appleMusicService.getCatalogSongsByIsrc("USUM71900002", any()) } returns
                listOf(catalogSong(listOf("222")))

        assertNull(resolver.resolve(artistId))
        assertNull(storedRow(artistId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `resolve rejects a single two artist song because one resource is not enough`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val artistId = transaction(database) {
                val id = insertArtist()
                val partner = insertArtist("Partner")
                val albumId = insertAlbum(listOf(id))
                insertSong(albumId, listOf(id, partner), code = "USUM71900001")
                id
            }
            coEvery { appleMusicService.getCatalogSongsByIsrc("USUM71900001", any()) } returns
                    listOf(catalogSong(listOf("111", "999")))

            assertNull(resolver.resolve(artistId))
            assertNull(storedRow(artistId))
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `resolve accepts a sole credited album imported by gamdl through its originalId`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val artistId = transaction(database) {
                val id = insertArtist()
                insertAlbum(listOf(id), original = "appleMusic:12345")
                id
            }
            coEvery { appleMusicService.getCatalogAlbumsByIds(listOf("12345"), any()) } returns
                    listOf(catalogAlbum(listOf("111"), id = "12345"))

            assertEquals("111", resolver.resolve(artistId))
            assertEquals("111", storedRow(artistId)?.get(ArtistProviderTable.externalId))
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `resolve rejects a gamdl album credited to more than one artist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = transaction(database) {
            val id = insertArtist()
            val partner = insertArtist("Partner")
            insertAlbum(listOf(id, partner), original = "appleMusic:12345")
            id
        }
        coEvery { appleMusicService.getCatalogAlbumsByIds(listOf("12345"), any()) } returns
                listOf(catalogAlbum(listOf("111", "999"), id = "12345"))

        assertNull(resolver.resolve(artistId))
        assertNull(storedRow(artistId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `resolve ignores album and song provider links entirely`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = transaction(database) {
            val id = insertArtist()
            val albumId = insertAlbum(listOf(id))
            val songId = insertSong(albumId, listOf(id))
            linkAlbumProvider(albumId, "777")
            linkSongProvider(songId, "654")
            id
        }

        assertNull(resolver.resolve(artistId))
        assertNull(storedRow(artistId))
        verifyNoCatalogLookups()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `resolve persists nothing when the catalog returns no resource`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = transaction(database) {
            val id = insertArtist()
            val albumId = insertAlbum(listOf(id), barcode = "00602445790234")
            insertSong(albumId, listOf(id), code = "USUM71900764")
            id
        }
        coEvery { appleMusicService.getCatalogAlbumsByUpc(any(), any()) } returns emptyList()
        coEvery { appleMusicService.getCatalogSongsByIsrc(any(), any()) } returns emptyList()

        assertNull(resolver.resolve(artistId))
        assertNull(storedRow(artistId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `resolve stops after the catalog call budget is spent`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = transaction(database) {
            val id = insertArtist()
            val albumId = insertAlbum(listOf(id))
            (1..10).forEach { index ->
                insertSong(albumId, listOf(id), code = "isrc$index", at = index.toLong())
            }
            (1..8).forEach { index ->
                insertAlbum(listOf(id), barcode = "barcode$index", released = "20%02d-01-01".format(index))
            }
            id
        }
        coEvery { appleMusicService.getCatalogSongsByIsrc(any(), any()) } returns emptyList()
        coEvery { appleMusicService.getCatalogAlbumsByUpc(any(), any()) } returns emptyList()

        assertNull(resolver.resolve(artistId))
        coVerify(exactly = 8) { appleMusicService.getCatalogSongsByIsrc(any(), any()) }
        coVerify(exactly = 4) { appleMusicService.getCatalogAlbumsByUpc(any(), any()) }
        coVerify(exactly = 0) { appleMusicService.getCatalogAlbumsByIds(any(), any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `resolve returns null when the catalog is disabled`(dialect: DbDialect) = runBlocking {
        setup(dialect, catalogEnabled = false)
        val artistId = transaction(database) {
            val id = insertArtist()
            insertAlbum(listOf(id), barcode = "00602445790234")
            id
        }

        assertNull(resolver.resolve(artistId))
        assertNull(storedRow(artistId))
        verifyNoCatalogLookups()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `resolve returns null for an unknown artist`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        assertNull(resolver.resolve(UUID.randomUUID()))
        verifyNoCatalogLookups()
    }
}
