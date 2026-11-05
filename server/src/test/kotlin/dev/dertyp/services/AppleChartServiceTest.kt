package dev.dertyp.services

import dev.dertyp.ApiClient
import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.HttpClientQueueService
import dev.dertyp.data.AppleChart
import dev.dertyp.data.AppleChartKind
import dev.dertyp.data.User
import dev.dertyp.db.*
import dev.dertyp.services.import.Type
import dev.dertyp.services.metadata.*
import dev.dertyp.services.lyrics.LyricsCandidateService
import dev.dertyp.testing.RecordedEntityEvents
import dev.dertyp.testing.entityChangeTables
import dev.dertyp.testing.entityEventsModule
import dev.dertyp.testing.insertAlbum
import dev.dertyp.testing.insertSong
import dev.dertyp.testing.insertUser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.hours

class AppleChartServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: AppleChartService
    private lateinit var queue: HttpClientQueueService
    private lateinit var originalApple: MetadataService
    private val apple = mockk<AppleMusicService>()
    private val environment = mockk<ApplicationEnvironment>(relaxed = true)
    private val events = RecordedEntityEvents().subscribeLibraryReactions()
    private val requests = mutableListOf<String>()
    private var feedStatus = HttpStatusCode.OK
    private var feedBody = ""
    private var catalogEnabled = true
    private var catalogIsrcs = emptyMap<String, String?>()
    private var catalogUpcs = emptyMap<String, String?>()

    @BeforeEach
    fun prepareHttp() {
        requests.clear()
        feedStatus = HttpStatusCode.OK
        feedBody = ""
        catalogEnabled = true
        catalogIsrcs = emptyMap()
        catalogUpcs = emptyMap()
    }

    private fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                includes(entityEventsModule(events))
                single { environment }
                single { SongService() }
                single { AlbumService() }
                single { ArtistService() }
                single { GenreService() }
                single { LibraryMergeService() }
                single { LibraryFileDeleter() }
                single { mockk<ImageService>(relaxed = true) }
                single { mockk<StorageService>(relaxed = true) }
                single { mockk<MetadataFetchingService>(relaxed = true) }
                single { mockk<RedisSearchService>(relaxed = true) }
                single { mockk<LyricsCandidateService>(relaxed = true) }
                single { mockk<MusicBrainzService>(relaxed = true) }
                single { MusicBrainzCacheService() }
                single { CachedMusicBrainzService(get(), get()) }
            })
        }
        database = TestDatabase.connect(
            dialect, "apple_chart_test",
            *entityChangeTables,
            UserTable,
            SongTable, SongVariantTable,
            AlbumTable,
            ArtistTable,
            ArtistMemberTable,
            SongArtistTable,
            AlbumArtistTable,
            SongMusicBrainzTable,
            SongAcoustIdTable,
            SongTitleTagTable,
            AlbumTitleTagTable,
            AlbumMusicBrainzTable,
            ArtistMusicBrainzTable,
            UserSongTable,
            UserCapabilityTable,
            ArtistAliasTable,
            FollowedArtistTable,
            PlaylistSongTable,
            UserPlaylistSongTable,
            UserPlaylistShareTable,
            ImageTable,
            ImageMetadataTable,
            AnimatedImageTable,
            ArtistSplitAliasTable,
            GenreTable,
            ArtistGenreTable,
            SongGenreTable,
            AlbumGenreTable,
            SongProviderTable,
            AlbumProviderTable,
            SongAudioDataTable,
            TimecodeTagTable,
            SyncedLyricsTable,
            LyricsCandidateTable,
            MBRecordingTable,
            MBRecordingIsrcTable,
            AppleChartEntryTable,
            AppleChartFetchTable,
        )

        mockkObject(ApiClient)
        val engine = MockEngine { request ->
            requests += request.url.toString()
            respond(
                content = feedBody,
                status = feedStatus,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        every { ApiClient.instance } returns HttpClient(engine) {
            install(ContentNegotiation) { json(ApplicationScope.json) }
        }
        queue = HttpClientQueueService()
        runBlocking { queue.startService() }
        every { ApiClient.queueInstance } returns queue

        originalApple = MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, environment)
        every { apple.catalogEnabled } answers { catalogEnabled }
        coEvery { apple.getCatalogSongsByIds(any(), any(), any()) } answers {
            firstArg<List<String>>().map {
                AppleMusicService.CatalogSongRef(it, emptyList(), emptyList(), catalogIsrcs[it])
            }
        }
        coEvery { apple.getCatalogAlbumsByIds(any(), any(), any()) } answers {
            firstArg<List<String>>().map { catalogAlbum(it, catalogUpcs[it]) }
        }
        MetadataService.register(IMetadataService.MetadataType.appleMusic, apple)

        service = AppleChartService()
    }

    @AfterEach
    fun tearDown() {
        if (::originalApple.isInitialized) {
            MetadataService.register(IMetadataService.MetadataType.appleMusic, originalApple)
        }
        runBlocking { events.stop() }
        if (::queue.isInitialized) runBlocking { queue.stopService() }
        stopKoin()
        unmockkAll()
        TestDatabase.cleanUp()
    }

    private fun catalogAlbum(id: String, upc: String?) = AppleMusicService.CatalogAlbum(
        id = id,
        title = "Album",
        artistName = "Artist",
        artistIds = emptyList(),
        releaseDate = null,
        isSingle = false,
        isComplete = true,
        isCompilation = false,
        upc = upc,
        url = null,
        trackCount = 1,
        image = null,
    )

    private data class FeedItem(
        val id: String,
        val name: String = "Name $id",
        val artist: String = "Artist $id",
        val releaseDate: String? = "2025-10-17",
        val kind: AppleChartKind = AppleChartKind.SONGS,
        val genres: List<Pair<String, String>> = listOf("6" to "Country", "34" to "Music"),
    ) {
        val url: String
            get() = if (kind == AppleChartKind.SONGS) {
                "https://music.apple.com/us/album/x/1$id?i=$id"
            } else {
                "https://music.apple.com/us/album/x/$id"
            }

        fun json(): String {
            val release = releaseDate?.let { """"releaseDate":"$it",""" } ?: ""
            val genreJson = genres.joinToString(",") { """{"genreId":"${it.first}","name":"${it.second}","url":"https://example.com/g"}""" }
            return """{"artistName":"$artist","id":"$id","name":"$name",$release"kind":"${kind.name.lowercase()}","artistId":"9$id","artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music/ab/cd/$id/100x100bb.jpg","genres":[$genreJson],"url":"$url"}"""
        }
    }

    private fun feed(items: List<FeedItem>, updated: String = UPDATED): String =
        """{"feed":{"title":"Top Songs","updated":"$updated","results":[${items.joinToString(",") { it.json() }}]}}"""

    private fun serveFeed(vararg items: FeedItem) {
        feedStatus = HttpStatusCode.OK
        feedBody = feed(items.toList())
    }

    private fun storedRows(country: String, kind: AppleChartKind): List<Pair<Int, String>> = transaction(database) {
        AppleChartEntryTable.selectAll()
            .where { (AppleChartEntryTable.country eq country) and (AppleChartEntryTable.kind eq kind) }
            .orderBy(AppleChartEntryTable.rank)
            .map { it[AppleChartEntryTable.rank] to it[AppleChartEntryTable.appleId] }
    }

    private fun seedCache(country: String, kind: AppleChartKind, fetchedAt: Long, vararg ids: String) {
        transaction(database) {
            ids.forEachIndexed { index, id ->
                AppleChartEntryTable.insert {
                    it[AppleChartEntryTable.country] = country
                    it[AppleChartEntryTable.kind] = kind
                    it[rank] = index + 1
                    it[appleId] = id
                    it[name] = "Cached $id"
                    it[artistName] = "Cached artist"
                    it[url] = "https://music.apple.com/us/album/x/1$id?i=$id"
                    it[genres] = "Country"
                }
            }
            AppleChartFetchTable.insert {
                it[AppleChartFetchTable.country] = country
                it[AppleChartFetchTable.kind] = kind
                it[AppleChartFetchTable.fetchedAt] = fetchedAt
                it[feedUpdatedAt] = CACHED_UPDATED_AT
            }
        }
    }

    private fun linkSong(songId: UUID, appleId: String) {
        SongProviderTable.insert {
            it[SongProviderTable.songId] = songId
            it[provider] = "apple"
            it[externalId] = appleId
            it[type] = Type.SONG.value
            it[rawUrl] = "https://music.apple.com/us/song/$appleId"
        }
    }

    private fun linkAlbum(albumId: UUID, appleId: String) {
        AlbumProviderTable.insert {
            it[AlbumProviderTable.albumId] = albumId
            it[provider] = "apple"
            it[externalId] = appleId
            it[type] = Type.ALBUM.value
            it[rawUrl] = "https://music.apple.com/us/album/$appleId"
        }
    }

    private fun songChart(userId: UUID, country: String = "de", limit: Int = 100): AppleChart = runBlocking {
        service.chart(userId, country, AppleChartKind.SONGS, limit)
    }

    private fun albumChart(userId: UUID, country: String = "de", limit: Int = 100): AppleChart = runBlocking {
        service.chart(userId, country, AppleChartKind.ALBUMS, limit)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the feed is mapped with rank as position, generic genre dropped, larger artwork and the feed time`(
        dialect: DbDialect
    ): Unit = runBlocking {
        setup(dialect)
        catalogEnabled = false
        val user = transaction(database) { insertUser() }
        serveFeed(FeedItem("101"), FeedItem("102", releaseDate = null, genres = listOf("34" to "Music")), FeedItem("103"))

        val chart = songChart(user, limit = 2)

        assertEquals("de", chart.country)
        assertEquals(AppleChartKind.SONGS, chart.kind)
        assertEquals(Instant.parse("2026-11-05T11:06:13Z").toEpochMilli(), chart.updatedAt)
        assertEquals(listOf(1, 2), chart.entries.map { it.rank })
        val first = chart.entries[0]
        assertEquals("101", first.appleId)
        assertEquals("Name 101", first.name)
        assertEquals("Artist 101", first.artistName)
        assertEquals("2025-10-17", first.releaseDate)
        assertEquals("https://music.apple.com/us/album/x/1101?i=101", first.url)
        assertEquals(listOf("Country"), first.genres)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music/ab/cd/101/600x600bb.jpg", first.artworkUrl)
        assertNull(first.song)
        assertNull(first.album)
        val second = chart.entries[1]
        assertNull(second.releaseDate)
        assertEquals(emptyList<String>(), second.genres)
        assertEquals(
            listOf("https://rss.marketingtools.apple.com/api/v2/de/music/most-played/100/songs.json"),
            requests
        )
        assertEquals(listOf(1 to "101", 2 to "102", 3 to "103"), storedRows("de", AppleChartKind.SONGS))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a fresh cache is served without a request`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val user = transaction(database) { insertUser() }
        seedCache("de", AppleChartKind.SONGS, System.currentTimeMillis() - 1.hours.inWholeMilliseconds, "201", "202")

        val chart = songChart(user)

        assertEquals(emptyList<String>(), requests)
        assertEquals(listOf("201", "202"), chart.entries.map { it.appleId })
        assertEquals(listOf("Cached 201", "Cached 202"), chart.entries.map { it.name })
        assertEquals(CACHED_UPDATED_AT, chart.updatedAt)
        coVerify(exactly = 0) { apple.getCatalogSongsByIds(any(), any(), any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a stale cache is fetched again and its rows are replaced`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val user = transaction(database) { insertUser() }
        seedCache("de", AppleChartKind.SONGS, System.currentTimeMillis() - 7.hours.inWholeMilliseconds, "201", "202", "203")
        seedCache("fr", AppleChartKind.SONGS, System.currentTimeMillis() - 7.hours.inWholeMilliseconds, "901")
        serveFeed(FeedItem("301"), FeedItem("302"))

        val chart = songChart(user)

        assertEquals(1, requests.size)
        assertEquals(listOf("301", "302"), chart.entries.map { it.appleId })
        assertEquals(Instant.parse("2026-11-05T11:06:13Z").toEpochMilli(), chart.updatedAt)
        assertEquals(listOf(1 to "301", 2 to "302"), storedRows("de", AppleChartKind.SONGS))
        assertEquals(listOf(1 to "901"), storedRows("fr", AppleChartKind.SONGS))
        val fetchedAt = transaction(database) {
            AppleChartFetchTable.selectAll()
                .where { AppleChartFetchTable.country eq "de" }
                .single()[AppleChartFetchTable.fetchedAt]
        }
        assertTrue(System.currentTimeMillis() - fetchedAt < 1.hours.inWholeMilliseconds)

        val again = songChart(user)

        assertEquals(1, requests.size)
        assertEquals(listOf("301", "302"), again.entries.map { it.appleId })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a failed fetch serves the cached rows and is not retried within the backoff`(
        dialect: DbDialect
    ): Unit = runBlocking {
        setup(dialect)
        val user = transaction(database) { insertUser() }
        seedCache("de", AppleChartKind.SONGS, System.currentTimeMillis() - 7.hours.inWholeMilliseconds, "201", "202")
        feedStatus = HttpStatusCode.InternalServerError

        val first = songChart(user)
        val second = songChart(user)

        assertEquals(1, requests.size)
        assertEquals(listOf("201", "202"), first.entries.map { it.appleId })
        assertEquals(listOf("201", "202"), second.entries.map { it.appleId })
        assertEquals(CACHED_UPDATED_AT, first.updatedAt)
        assertEquals(listOf(1 to "201", 2 to "202"), storedRows("de", AppleChartKind.SONGS))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a failed fetch without a cache gives an empty chart`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val user = transaction(database) { insertUser() }
        feedStatus = HttpStatusCode.InternalServerError

        val first = songChart(user)
        val second = songChart(user)

        assertEquals(emptyList<Any>(), first.entries)
        assertEquals(0L, first.updatedAt)
        assertEquals(emptyList<Any>(), second.entries)
        assertEquals(1, requests.size)
        assertEquals(emptyList<Pair<Int, String>>(), storedRows("de", AppleChartKind.SONGS))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `songs are matched by Apple id and by the catalog ISRC, the rest has no song`(
        dialect: DbDialect
    ): Unit = runBlocking {
        setup(dialect)
        data class Fixture(val user: UUID, val byAppleId: UUID, val byIsrc: UUID)
        val f = transaction(database) {
            val user = insertUser()
            val album = insertAlbum()
            val byAppleId = insertSong(album, "By Apple id")
            linkSong(byAppleId, "401")
            val byIsrc = insertSong(album, "By ISRC", isrc = "USABC1234567")
            insertSong(album, "Unrelated", isrc = "USZZZ0000001")
            Fixture(user, byAppleId, byIsrc)
        }
        serveFeed(FeedItem("401"), FeedItem("402"), FeedItem("403"))
        catalogIsrcs = mapOf("401" to "USABC1234567", "402" to " usabc1234567 ", "403" to "GBNOTINLIB01")

        val chart = songChart(f.user)

        assertEquals(listOf(f.byAppleId, f.byIsrc, null), chart.entries.map { it.song?.id })
        assertEquals("By Apple id", chart.entries[0].song?.title)
        assertEquals(listOf("401", "402", "403"), chart.entries.map { it.appleId })
        assertTrue(chart.entries.all { it.album == null })
        coVerify(exactly = 1) { apple.getCatalogSongsByIds(listOf("401", "402", "403"), any(), "de") }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `albums are matched by Apple id and by the UPC with a differently zero-padded barcode`(
        dialect: DbDialect
    ): Unit = runBlocking {
        setup(dialect)
        data class Fixture(val user: UUID, val byAppleId: UUID, val byUpc: UUID)
        val f = transaction(database) {
            val user = insertUser()
            val byAppleId = insertAlbum("By Apple id")
            linkAlbum(byAppleId, "501")
            val byUpc = insertAlbum("By UPC")
            AlbumTable.update({ AlbumTable.id eq byUpc }) { it[barcode] = "0602445790234" }
            val other = insertAlbum("Other")
            AlbumTable.update({ AlbumTable.id eq other }) { it[barcode] = "0000000099999" }
            insertSong(byAppleId)
            insertSong(byUpc)
            insertSong(other)
            Fixture(user, byAppleId, byUpc)
        }
        serveFeed(
            FeedItem("501", kind = AppleChartKind.ALBUMS),
            FeedItem("502", kind = AppleChartKind.ALBUMS),
            FeedItem("503", kind = AppleChartKind.ALBUMS),
        )
        catalogUpcs = mapOf("502" to "00602445790234", "503" to "00602445700000")

        val chart = albumChart(f.user, "us")

        assertEquals(listOf(f.byAppleId, f.byUpc, null), chart.entries.map { it.album?.id })
        assertTrue(chart.entries.all { it.song == null })
        assertEquals(
            listOf("https://rss.marketingtools.apple.com/api/v2/us/music/most-played/100/albums.json"),
            requests
        )
        coVerify(exactly = 1) { apple.getCatalogAlbumsByIds(listOf("501", "502", "503"), any(), "us") }
        coVerify(exactly = 0) { apple.getCatalogSongsByIds(any(), any(), any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `without the catalog no catalog call is made and only Apple ids match`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        catalogEnabled = false
        data class Fixture(val user: UUID, val byAppleId: UUID)
        val f = transaction(database) {
            val user = insertUser()
            val album = insertAlbum()
            val byAppleId = insertSong(album)
            linkSong(byAppleId, "601")
            insertSong(album, isrc = "USABC1234567")
            Fixture(user, byAppleId)
        }
        serveFeed(FeedItem("601"), FeedItem("602"))
        catalogIsrcs = mapOf("602" to "USABC1234567")

        val chart = songChart(f.user)

        assertEquals(listOf(f.byAppleId, null), chart.entries.map { it.song?.id })
        coVerify(exactly = 0) { apple.getCatalogSongsByIds(any(), any(), any()) }
        coVerify(exactly = 0) { apple.getCatalogAlbumsByIds(any(), any(), any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `entries that are not in the library keep all their fields`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val user = transaction(database) { insertUser() }
        serveFeed(FeedItem("701", name = "Lonely", artist = "Nobody", genres = listOf("14" to "Pop", "34" to "Music")))
        catalogIsrcs = mapOf("701" to "USNONE000001")

        val chart = songChart(user)

        val entry = chart.entries.single()
        assertNull(entry.song)
        assertNull(entry.album)
        assertEquals(1, entry.rank)
        assertEquals("701", entry.appleId)
        assertEquals("Lonely", entry.name)
        assertEquals("Nobody", entry.artistName)
        assertEquals("2025-10-17", entry.releaseDate)
        assertNotNull(entry.artworkUrl)
        assertEquals("https://music.apple.com/us/album/x/1701?i=701", entry.url)
        assertEquals(listOf("Pop"), entry.genres)
    }

    private fun adapter(userId: UUID, chartService: AppleChartService) = RpcAppleChartService(
        User(id = userId, username = "tester", isAdmin = false),
        chartService
    )

    @ParameterizedTest
    @ValueSource(strings = ["zzz", "1a", "", "d", " ", "d1", "d-"])
    fun `the adapter rejects an invalid country`(country: String): Unit = runBlocking {
        val chartService = mockk<AppleChartService>()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { adapter(UUID.randomUUID(), chartService).appleChart(country, AppleChartKind.SONGS, 10) }
        }

        coVerify(exactly = 0) { chartService.chart(any(), any(), any(), any()) }
    }

    @Test
    fun `the adapter normalizes the country and passes the user`(): Unit = runBlocking {
        val userId = UUID.randomUUID()
        val expected = AppleChart("de", AppleChartKind.ALBUMS, 0L, emptyList())
        val chartService = mockk<AppleChartService>()
        coEvery { chartService.chart(any(), any(), any(), any()) } returns expected

        val result = adapter(userId, chartService).appleChart("DE ", AppleChartKind.ALBUMS, 10)

        assertEquals(expected, result)
        coVerify(exactly = 1) { chartService.chart(userId, "de", AppleChartKind.ALBUMS, 10) }
    }

    @Test
    fun `the adapter clamps the limit to 1 through 100`(): Unit = runBlocking {
        val userId = UUID.randomUUID()
        val chartService = mockk<AppleChartService>()
        coEvery { chartService.chart(any(), any(), any(), any()) } returns AppleChart("us", AppleChartKind.SONGS, 0L, emptyList())
        val rpc = adapter(userId, chartService)

        rpc.appleChart("us", AppleChartKind.SONGS, 0)
        rpc.appleChart("us", AppleChartKind.SONGS, -5)
        rpc.appleChart("us", AppleChartKind.SONGS, 500)
        rpc.appleChart("us", AppleChartKind.SONGS, 100)
        rpc.appleChart("us", AppleChartKind.SONGS, 42)

        coVerify(exactly = 2) { chartService.chart(userId, "us", AppleChartKind.SONGS, 1) }
        coVerify(exactly = 2) { chartService.chart(userId, "us", AppleChartKind.SONGS, 100) }
        coVerify(exactly = 1) { chartService.chart(userId, "us", AppleChartKind.SONGS, 42) }
    }

    private companion object {
        const val UPDATED = "Thu, 5 Nov 2026 11:06:13 +0000"
        const val CACHED_UPDATED_AT = 1_700_000_000_000L
    }
}
