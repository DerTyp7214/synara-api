package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.HttpClientQueueService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.test.KoinTest
import java.time.LocalDate

class AppleMusicServiceTest : KoinTest {

    private lateinit var environment: ApplicationEnvironment
    private lateinit var appleMusicService: AppleMusicService
    private lateinit var mockEngine: MockEngine
    private var queueService: HttpClientQueueService? = null

    @BeforeEach
    fun setup() {
        environment = mockk()
        every { environment.config } returns mockk(relaxed = true)

        mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/search" -> {
                    val entity = request.url.parameters["entity"]
                    val content = when {
                        entity == "musicArtist" -> """
                            {
                              "resultCount": 1,
                              "results": [
                                {
                                  "wrapperType": "artist",
                                  "artistId": 12345,
                                  "artistName": "Test Artist",
                                  "artistLinkUrl": "https://music.apple.com/artist/test-artist/12345"
                                }
                              ]
                            }
                        """.trimIndent()
                        entity == "album" -> """
                            {
                              "resultCount": 1,
                              "results": [
                                {
                                  "wrapperType": "collection",
                                  "collectionId": 67890,
                                  "artistName": "Test Artist",
                                  "collectionName": "Test Album",
                                  "artworkUrl100": "https://example.com/100x100bb.jpg",
                                  "trackCount": 10
                                }
                              ]
                            }
                        """.trimIndent()
                        entity?.contains("song") == true -> """
                            {
                              "resultCount": 2,
                              "results": [
                                {
                                  "wrapperType": "collection",
                                  "collectionId": 67890,
                                  "artistName": "Test Artist",
                                  "collectionName": "Test Album",
                                  "artworkUrl100": "https://example.com/100x100bb.jpg",
                                  "trackCount": 1
                                },
                                {
                                  "wrapperType": "track",
                                  "collectionId": 67890,
                                  "artistName": "Test Artist",
                                  "collectionName": "Test Album",
                                  "trackName": "Test Song",
                                  "artworkUrl100": "https://example.com/100x100bb.jpg",
                                  "trackCount": 1,
                                  "trackTimeMillis": 180000
                                }
                              ]
                            }
                        """.trimIndent()
                        else -> """{"resultCount": 0, "results": []}"""
                    }
                    respond(
                        content = content,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/javascript; charset=utf-8")
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val mockHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(ApplicationScope.json)
            }
        }

        mockkObject(ApiClient)
        every { ApiClient.instance } returns mockHttpClient

        appleMusicService = AppleMusicService(environment)
    }

    @AfterEach
    fun tearDown() {
        runBlocking { queueService?.stopService() }
        queueService = null
        unmockkAll()
    }

    private fun enableCatalog(storefront: String? = null) {
        every { environment.config.propertyOrNull("appleMusic.teamId") } returns mockk { every { getString() } returns "TEAM1" }
        every { environment.config.propertyOrNull("appleMusic.keyId") } returns mockk { every { getString() } returns "KEY1" }
        every { environment.config.propertyOrNull("appleMusic.p8Path") } returns mockk { every { getString() } returns "/tmp/apple.p8" }
        every { environment.config.propertyOrNull("appleMusic.storefront") } returns
                storefront?.let { value -> mockk { every { getString() } returns value } }

        val tokenField = appleMusicService.javaClass.getDeclaredField("appleMusicToken")
        tokenField.isAccessible = true
        tokenField.set(appleMusicService, "mock-token")
        val expirationField = appleMusicService.javaClass.getDeclaredField("tokenExpiration")
        expirationField.isAccessible = true
        expirationField.set(appleMusicService, System.currentTimeMillis() + 100000)
    }

    private fun disableCatalog() {
        every { environment.config.propertyOrNull("appleMusic.teamId") } returns null
        every { environment.config.propertyOrNull("appleMusic.keyId") } returns null
        every { environment.config.propertyOrNull("appleMusic.p8Path") } returns null
    }

    private suspend fun useEngine(handler: MockRequestHandler) {
        mockEngine = MockEngine(handler)
        every { ApiClient.instance } returns HttpClient(mockEngine) {
            install(ContentNegotiation) { json(ApplicationScope.json) }
        }
        val queue = HttpClientQueueService()
        queue.startService()
        queueService = queue
        every { ApiClient.queueInstance } returns queue
    }

    private fun MockRequestHandleScope.respondJson(content: String): HttpResponseData = respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json")
    )

    private fun albumJson(
        id: String = "1440857781",
        releaseDate: String = "2024-05-17",
        isSingle: Boolean = false,
        isComplete: Boolean = true,
        isCompilation: Boolean = false,
        artistIds: List<String> = listOf("111"),
        withArtwork: Boolean = true,
        recordLabel: String? = "Division Recordings",
        copyright: String? = "℗ 2024 Division Recordings",
        genreNames: List<String>? = listOf("Hip-Hop/Rap", "Music")
    ): String {
        val artwork = if (withArtwork) {
            """, "artwork": {"url": "https://example.com/aa/{w}x{h}bb.jpg", "width": 1200, "height": 1200}"""
        } else ""
        val relationships = if (artistIds.isEmpty()) "" else """,
                  "relationships": {
                    "artists": {"data": [${artistIds.joinToString(",") { """{"id": "$it", "type": "artists"}""" }}]}
                  }"""
        val recordLabelJson = recordLabel?.let { ""","recordLabel": "$it"""" } ?: ""
        val copyrightJson = copyright?.let { ""","copyright": "$it"""" } ?: ""
        val genreNamesJson = genreNames?.let { names ->
            ""","genreNames": [${names.joinToString(",") { "\"$it\"" }}]"""
        } ?: ""
        return """
            {
              "id": "$id",
              "type": "albums",
              "attributes": {
                "name": "Test Album",
                "artistName": "Test Artist",
                "releaseDate": "$releaseDate",
                "isSingle": $isSingle,
                "isComplete": $isComplete,
                "isCompilation": $isCompilation,
                "upc": "00602445790234",
                "url": "https://music.apple.com/us/album/test-album/$id",
                "trackCount": 12$artwork$recordLabelJson$copyrightJson$genreNamesJson
              }$relationships
            }
        """.trimIndent()
    }

    @Test
    fun `catalogEnabled is false when the credentials are unset`() {
        disableCatalog()

        assertFalse(appleMusicService.catalogEnabled)
    }

    @Test
    fun `catalogEnabled is false when the configuration yields blank values`() {
        assertFalse(appleMusicService.catalogEnabled)
    }

    @Test
    fun `catalogEnabled is true when team, key and p8 path are configured`() {
        enableCatalog()

        assertTrue(appleMusicService.catalogEnabled)
    }

    @Test
    fun `getArtistCatalogAlbums uses the configured storefront`() = runBlocking {
        enableCatalog(storefront = "de")
        val paths = mutableListOf<String>()
        useEngine { request ->
            paths += request.url.encodedPath
            respondJson("""{"data": []}""")
        }

        appleMusicService.getArtistCatalogAlbums("111")

        assertEquals(listOf("/v1/catalog/de/artists/111/albums"), paths)
    }

    @Test
    fun `getArtistCatalogAlbums pages via next and maps every field`() = runBlocking {
        enableCatalog()
        val paths = mutableListOf<String>()
        useEngine { request ->
            paths += request.url.toString()
            if (request.url.parameters["offset"] == "100") {
                respondJson("""{"data": [${albumJson(id = "222", releaseDate = "2023-01-02")}]}""")
            } else {
                respondJson(
                    """
                        {
                          "data": [${albumJson()}],
                          "next": "/v1/catalog/us/artists/111/albums?offset=100"
                        }
                    """.trimIndent()
                )
            }
        }

        val albums = appleMusicService.getArtistCatalogAlbums("111")
        assertNotNull(albums)
        val mapped = albums.orEmpty()

        assertEquals(2, paths.size)
        assertEquals(2, mapped.size)
        val first = mapped[0]
        assertEquals("1440857781", first.id)
        assertEquals("Test Album", first.title)
        assertEquals("Test Artist", first.artistName)
        assertEquals(listOf("111"), first.artistIds)
        assertEquals(LocalDate.of(2024, 5, 17), first.releaseDate)
        assertFalse(first.isSingle)
        assertTrue(first.isComplete)
        assertFalse(first.isCompilation)
        assertEquals("00602445790234", first.upc)
        assertEquals("https://music.apple.com/us/album/test-album/1440857781", first.url)
        assertEquals(12, first.trackCount)
        assertEquals("https://example.com/aa/10000x0w-999.jpg", first.image?.url)
        assertEquals("Division Recordings", first.recordLabel)
        assertEquals("℗ 2024 Division Recordings", first.copyright)
        assertEquals(listOf("Hip-Hop/Rap", "Music"), first.genreNames)
        assertEquals("222", mapped[1].id)
    }

    @Test
    fun `catalogAlbumFrom tolerates missing label, copyright and genres`() = runBlocking {
        enableCatalog()
        useEngine {
            respondJson(
                """{"data": [${
                    albumJson(recordLabel = null, copyright = null, genreNames = null)
                }]}"""
            )
        }

        val albums = appleMusicService.getArtistCatalogAlbums("111")

        val first = albums.orEmpty().first()
        assertNull(first.recordLabel)
        assertNull(first.copyright)
        assertEquals(emptyList<String>(), first.genreNames)
    }

    @Test
    fun `getArtistCatalogAlbums keeps future dated pre-releases`() = runBlocking {
        enableCatalog()
        useEngine {
            respondJson("""{"data": [${albumJson(releaseDate = "2099-12-24", isComplete = false, isSingle = true)}]}""")
        }

        val albums = appleMusicService.getArtistCatalogAlbums("111").orEmpty()

        assertEquals(1, albums.size)
        assertEquals(LocalDate.of(2099, 12, 24), albums.first().releaseDate)
        assertFalse(albums.first().isComplete)
        assertTrue(albums.first().isSingle)
    }

    @Test
    fun `getArtistCatalogAlbums accepts a year only release date`() = runBlocking {
        enableCatalog()
        useEngine { respondJson("""{"data": [${albumJson(releaseDate = "2019")}]}""") }

        val albums = appleMusicService.getArtistCatalogAlbums("111")

        assertEquals(LocalDate.of(2019, 1, 1), albums?.first()?.releaseDate)
    }

    @Test
    fun `getArtistCatalogAlbums maps a missing artwork to a null image`() = runBlocking {
        enableCatalog()
        useEngine { respondJson("""{"data": [${albumJson(withArtwork = false)}]}""") }

        val albums = appleMusicService.getArtistCatalogAlbums("111")

        assertNull(albums?.first()?.image)
    }

    @Test
    fun `getArtistCatalogAlbums maps missing relationships to empty artist ids`() = runBlocking {
        enableCatalog()
        useEngine { respondJson("""{"data": [${albumJson(artistIds = emptyList())}]}""") }

        val albums = appleMusicService.getArtistCatalogAlbums("111")

        assertEquals(emptyList<String>(), albums?.first()?.artistIds)
    }

    @Test
    fun `getArtistCatalogAlbums returns null when the request fails`() = runBlocking {
        enableCatalog()
        useEngine { respondError(HttpStatusCode.NotFound) }

        assertNull(appleMusicService.getArtistCatalogAlbums("111"))
    }

    @Test
    fun `getArtistCatalogAlbums returns an empty list when the artist has no albums`() = runBlocking {
        enableCatalog()
        useEngine { respondJson("""{"data": []}""") }

        assertEquals(emptyList<AppleMusicService.CatalogAlbum>(), appleMusicService.getArtistCatalogAlbums("111"))
    }

    @Test
    fun `getArtistCatalogAlbums does not call the catalog when it is disabled`() = runBlocking {
        disableCatalog()
        useEngine { respondJson("""{"data": []}""") }

        assertNull(appleMusicService.getArtistCatalogAlbums("111"))
        assertEquals(0, mockEngine.requestHistory.size)
    }

    @Test
    fun `getAlbumIsrcs pages via next, trims and dedupes codes`() = runBlocking {
        enableCatalog()
        val paths = mutableListOf<String>()
        useEngine { request ->
            paths += request.url.toString()
            if (request.url.parameters["offset"] == "100") {
                respondJson(
                    """
                        {
                          "data": [
                            {"id": "3", "type": "songs", "attributes": {"name": "C", "artistName": "X", "durationInMillis": 1000, "trackNumber": 1, "discNumber": 1, "isrc": "QZK6P2600001"}},
                            {"id": "4", "type": "songs", "attributes": {"name": "D", "artistName": "X", "durationInMillis": 1000, "trackNumber": 2, "discNumber": 1, "isrc": "DEUM72400123"}}
                          ]
                        }
                    """.trimIndent()
                )
            } else {
                respondJson(
                    """
                        {
                          "data": [
                            {"id": "1", "type": "songs", "attributes": {"name": "A", "artistName": "X", "durationInMillis": 1000, "trackNumber": 1, "discNumber": 1, "isrc": " QZK6P2600001 "}},
                            {"id": "2", "type": "songs", "attributes": {"name": "B", "artistName": "X", "durationInMillis": 1000, "trackNumber": 2, "discNumber": 1}}
                          ],
                          "next": "/v1/catalog/us/albums/1440857781/tracks?offset=100"
                        }
                    """.trimIndent()
                )
            }
        }

        val isrcs = appleMusicService.getAlbumIsrcs("1440857781")

        assertEquals(2, paths.size)
        assertEquals(listOf("QZK6P2600001", "DEUM72400123"), isrcs)
    }

    @Test
    fun `getAlbumIsrcs returns null when a page fails`() = runBlocking {
        enableCatalog()
        var calls = 0
        useEngine {
            calls++
            if (calls == 1) {
                respondJson(
                    """
                        {
                          "data": [
                            {"id": "1", "type": "songs", "attributes": {"name": "A", "artistName": "X", "durationInMillis": 1000, "trackNumber": 1, "discNumber": 1, "isrc": "QZK6P2600001"}}
                          ],
                          "next": "/v1/catalog/us/albums/1440857781/tracks?offset=100"
                        }
                    """.trimIndent()
                )
            } else {
                respondError(HttpStatusCode.InternalServerError)
            }
        }

        assertNull(appleMusicService.getAlbumIsrcs("1440857781"))
    }

    @Test
    fun `getAlbumIsrcs returns an empty list when no track carries an isrc`() = runBlocking {
        enableCatalog()
        useEngine {
            respondJson(
                """
                    {
                      "data": [
                        {"id": "1", "type": "songs", "attributes": {"name": "A", "artistName": "X", "durationInMillis": 1000, "trackNumber": 1, "discNumber": 1}}
                      ]
                    }
                """.trimIndent()
            )
        }

        assertEquals(emptyList<String>(), appleMusicService.getAlbumIsrcs("1440857781"))
    }

    @Test
    fun `getAlbumIsrcs returns null when the catalog is disabled`() = runBlocking {
        disableCatalog()
        useEngine { respondJson("""{"data": []}""") }

        assertNull(appleMusicService.getAlbumIsrcs("1440857781"))
        assertEquals(0, mockEngine.requestHistory.size)
    }

    @Test
    fun `getCatalogAlbumsByUpc passes the upc filter`() = runBlocking {
        enableCatalog()
        var upcParameter: String? = null
        useEngine { request ->
            upcParameter = request.url.parameters["filter[upc]"]
            respondJson("""{"data": [${albumJson()}]}""")
        }

        val albums = appleMusicService.getCatalogAlbumsByUpc("00602445790234")

        assertEquals("00602445790234", upcParameter)
        assertEquals("/v1/catalog/us/albums", mockEngine.requestHistory.first().url.encodedPath)
        assertEquals(1, albums.size)
        assertEquals("1440857781", albums.first().id)
    }

    @Test
    fun `getCatalogAlbumsByIds chunks the ids at one hundred per request`() = runBlocking {
        enableCatalog()
        val chunkSizes = mutableListOf<Int>()
        useEngine { request ->
            chunkSizes += request.url.parameters["ids"]!!.split(",").size
            respondJson("""{"data": []}""")
        }

        appleMusicService.getCatalogAlbumsByIds((1..150).map { it.toString() })

        assertEquals(listOf(100, 50), chunkSizes)
    }

    @Test
    fun `getCatalogSongsByIsrc returns the song references`() = runBlocking {
        enableCatalog()
        var isrcParameter: String? = null
        useEngine { request ->
            isrcParameter = request.url.parameters["filter[isrc]"]
            respondJson(
                """
                    {
                      "data": [
                        {
                          "id": "555",
                          "type": "songs",
                          "relationships": {
                            "artists": {"data": [{"id": "111", "type": "artists"}]},
                            "albums": {"data": [{"id": "999", "type": "albums"}]}
                          }
                        }
                      ]
                    }
                """.trimIndent()
            )
        }

        val songs = appleMusicService.getCatalogSongsByIsrc("USUM71900764")

        assertEquals("USUM71900764", isrcParameter)
        assertEquals("/v1/catalog/us/songs", mockEngine.requestHistory.first().url.encodedPath)
        assertEquals(1, songs.size)
        assertEquals("555", songs.first().id)
        assertEquals(listOf("111"), songs.first().artistIds)
        assertEquals(listOf("999"), songs.first().albumIds)
    }

    @Test
    fun `getCatalogSongsByIds chunks the ids at one hundred per request`() = runBlocking {
        enableCatalog()
        val chunkSizes = mutableListOf<Int>()
        useEngine { request ->
            chunkSizes += request.url.parameters["ids"]!!.split(",").size
            respondJson("""{"data": []}""")
        }

        appleMusicService.getCatalogSongsByIds((1..150).map { it.toString() })

        assertEquals(listOf(100, 50), chunkSizes)
    }

    @Test
    fun `catalog lookups return empty results and issue no request when disabled`() = runBlocking {
        disableCatalog()
        useEngine { respondJson("""{"data": []}""") }

        assertEquals(emptyList<AppleMusicService.CatalogAlbum>(), appleMusicService.getCatalogAlbumsByUpc("123"))
        assertEquals(emptyList<AppleMusicService.CatalogAlbum>(), appleMusicService.getCatalogAlbumsByIds(listOf("1")))
        assertEquals(emptyList<AppleMusicService.CatalogSongRef>(), appleMusicService.getCatalogSongsByIsrc("ISRC"))
        assertEquals(emptyList<AppleMusicService.CatalogSongRef>(), appleMusicService.getCatalogSongsByIds(listOf("1")))
        assertEquals(0, mockEngine.requestHistory.size)
    }

    @Test
    fun `artworkUrlForSize rewrites the size tail and falls back to the native maximum`() {
        val base = "https://is1-ssl.mzstatic.com/image/thumb/Music/v4/aa/bb/cc/xyz"

        assertEquals("$base/512x512bb.jpg", AppleMusicService.artworkUrlForSize("$base/{w}x{h}bb.jpg", 512))
        assertEquals("$base/512x512bb.jpg", AppleMusicService.artworkUrlForSize("$base/1200x630bb.jpg", 512))
        assertEquals("$base/10000x0w-999.jpg", AppleMusicService.artworkUrlForSize("$base/{w}x{h}bb.jpg", 0))
        assertEquals("$base/10000x0w-999.jpg", AppleMusicService.artworkUrlForSize("$base/{w}x{h}bb.jpg", -1))
    }

    @Test
    fun `parseReleaseDate accepts year and year-month values`() {
        assertEquals(LocalDate.of(2019, 1, 1), AppleMusicService.parseReleaseDate("2019"))
        assertEquals(LocalDate.of(2019, 6, 1), AppleMusicService.parseReleaseDate("2019-06"))
        assertNull(AppleMusicService.parseReleaseDate("20xx"))
        assertNull(AppleMusicService.parseReleaseDate("2019-99"))
    }

    @Test
    fun `searchArtists should return list of artists`() = runBlocking {
        val artists = appleMusicService.searchArtists("test", 10)

        assertEquals(1, artists.size)
        assertEquals("12345", artists[0].id)
        assertEquals("Test Artist", artists[0].name)
        assertEquals("https://music.apple.com/artist/test-artist/12345", artists[0].url)
    }

    @Test
    fun `search should return list of tracks`() = runBlocking {
        val tracks = appleMusicService.search("test", 10)

        assertEquals(1, tracks.size)
        assertEquals("67890", tracks[0].id)
        assertEquals("Test Song", tracks[0].title)
        assertEquals(listOf("Test Artist"), tracks[0].artists)
    }

    @Test
    fun `searchAlbums should return list of albums with 600x600 image`() = runBlocking {
        val albums = appleMusicService.searchAlbums("test", 10, includeTracks = false)

        assertEquals(1, albums.size)
        assertEquals("67890", albums[0].id)
        assertEquals("Test Album", albums[0].title)
        assertEquals(listOf("Test Artist"), albums[0].artists)
        assertEquals(10, albums[0].trackCount)
        assertEquals("https://example.com/600x600bb.jpg", albums[0].images[0].url)
    }

    @Test
    fun `searchAlbums with includeTracks should return unique albums from mixed results`() = runBlocking {
        val albums = appleMusicService.searchAlbums("test", 10, includeTracks = true)

        assertEquals(1, albums.size)
        assertEquals("67890", albums[0].id)
        assertEquals("Test Album", albums[0].title)
    }

    @Test
    fun `searchAlbums with real-world mixed response should return unique albums`() = runBlocking {
        mockEngine = MockEngine { _ ->
            respond(
                content = """
                    {
                     "resultCount":6,
                     "results": [
                    {"wrapperType":"track", "kind":"song", "artistId":445782702, "collectionId":1712105785, "trackId":1712106051, "artistName":"Bonez MC", "collectionName":"LOVELINE EP 💔", "trackName":"Das ist Bonez 💀", "artworkUrl100":"https://example.com/image1.jpg", "trackCount":6}, 
                    {"wrapperType":"track", "kind":"song", "artistId":445782702, "collectionId":1528317784, "trackId":1528318708, "artistName":"Bonez MC", "collectionName":"Hollywood", "trackName":"Papa ist in Hollywood", "artworkUrl100":"https://example.com/image2.jpg", "trackCount":13}, 
                    {"wrapperType":"track", "kind":"song", "artistId":445782702, "collectionId":1545129420, "trackId":1545129708, "artistName":"Bonez MC", "collectionName":"Hollywood Uncut", "trackName":"Angeklagt", "artworkUrl100":"https://example.com/image3.jpg", "trackCount":13}, 
                    {"wrapperType":"track", "kind":"song", "artistId":445782702, "collectionId":1528317784, "trackId":1528318175, "artistName":"Bonez MC", "collectionName":"Hollywood", "trackName":"Tilidin Weg", "artworkUrl100":"https://example.com/image2.jpg", "trackCount":13}, 
                    {"wrapperType":"track", "kind":"song", "artistId":445782702, "collectionId":1525233607, "trackId":1525233615, "artistName":"Bonez MC", "collectionName":"Tilidin Weg - Single", "trackName":"Tilidin Weg", "artworkUrl100":"https://example.com/image4.jpg", "trackCount":1}, 
                    {"wrapperType":"collection", "collectionType":"Album", "artistId":445782702, "collectionId":1712105785, "artistName":"Bonez MC", "collectionName":"LOVELINE EP 💔", "artworkUrl100":"https://example.com/image1.jpg", "trackCount":8}]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/javascript; charset=utf-8")
            )
        }

        mockkObject(ApiClient)
        every { ApiClient.instance } returns HttpClient(mockEngine) {
            install(ContentNegotiation) { json(ApplicationScope.json) }
        }

        val albums = appleMusicService.searchAlbums("Bonez MC", 10, includeTracks = true)

        assertEquals(4, albums.size)
        
        val titles = albums.map { it.title }.toSet()
        assertTrue(titles.contains("LOVELINE EP 💔"))
        assertTrue(titles.contains("Hollywood"))
        assertTrue(titles.contains("Hollywood Uncut"))
        assertTrue(titles.contains("Tilidin Weg - Single"))

        val hollywoodAlbum = albums.first { it.title == "Hollywood" }
        assertTrue(hollywoodAlbum.additionalTitles.contains("Papa ist in Hollywood"))
        assertTrue(hollywoodAlbum.additionalTitles.contains("Tilidin Weg"))
    }

    @Test
    fun `getTrackByIsrc should return track from catalog API when token is provided`() = runBlocking {
        val isrc = "USUM71900764"

        val tokenField = appleMusicService.javaClass.getDeclaredField("appleMusicToken")
        tokenField.isAccessible = true
        tokenField.set(appleMusicService, "mock-token")
        val expirationField = appleMusicService.javaClass.getDeclaredField("tokenExpiration")
        expirationField.isAccessible = true
        expirationField.set(appleMusicService, System.currentTimeMillis() + 100000)

        mockEngine = MockEngine { request ->
            if (request.url.toString().contains("api.music.apple.com")) {
                assertEquals("Bearer mock-token", request.headers[HttpHeaders.Authorization])
                assertEquals(isrc, request.url.parameters["filter[isrc]"])
                respond(
                    content = """
                        {
                          "data": [
                            {
                              "id": "1471758375",
                              "type": "songs",
                              "attributes": {
                                "name": "Lover",
                                "artistName": "Taylor Swift",
                                "durationInMillis": 221000,
                                "artwork": {
                                  "url": "https://example.com/image-{w}x{h}-{f}.jpg"
                                }
                              }
                            }
                          ]
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        }
        every { ApiClient.instance } returns HttpClient(mockEngine) {
            install(ContentNegotiation) { json(ApplicationScope.json) }
        }

        val track = appleMusicService.getTrackByIsrc(isrc)

        assertEquals("1471758375", track?.id)
        assertEquals("Lover", track?.title)
        assertEquals("https://example.com/image-600x600-jpg.jpg", track?.images?.firstOrNull()?.url)
    }

    @Test
    fun `getTrackByIsrc should return track for valid ISRC using iTunes fallback`() = runBlocking {
        every { environment.config.propertyOrNull("appleMusic.teamId") } returns null
        every { environment.config.propertyOrNull("appleMusic.keyId") } returns null
        every { environment.config.propertyOrNull("appleMusic.p8Path") } returns null

        val isrc = "USUM71900764"
        mockEngine = MockEngine { request ->
            assertEquals("/lookup", request.url.encodedPath)
            assertEquals(isrc, request.url.parameters["isrc"])
            respond(
                content = """
                    {
                      "resultCount": 1,
                      "results": [
                        {
                          "wrapperType": "track",
                          "kind": "song",
                          "collectionId": 1471758375,
                          "artistName": "Taylor Swift",
                          "collectionName": "Lover",
                          "trackName": "Lover",
                          "artworkUrl100": "https://example.com/100x100bb.jpg",
                          "trackCount": 18,
                          "trackTimeMillis": 221000,
                          "primaryIsrc": "$isrc"
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/javascript; charset=utf-8")
            )
        }
        every { ApiClient.instance } returns HttpClient(mockEngine) {
            install(ContentNegotiation) { json(ApplicationScope.json) }
        }

        val track = appleMusicService.getTrackByIsrc(isrc)

        assertEquals("1471758375", track?.id)
        assertEquals("Lover", track?.title)
        assertEquals(isrc, track?.isrc)
    }

    @Test
    fun `maxArtworkUrl rewrites catalog templates and thumbnails to the native-max request`() {
        val base = "https://is1-ssl.mzstatic.com/image/thumb/Music/v4/aa/bb/cc/xyz"

        assertEquals("$base/10000x0w-999.jpg", AppleMusicService.maxArtworkUrl("$base/{w}x{h}bb.jpg"))
        assertEquals("$base/10000x0w-999.jpg", AppleMusicService.maxArtworkUrl("$base/{w}x{h}{c}.{f}"))
        assertEquals("$base/10000x0w-999.jpg", AppleMusicService.maxArtworkUrl("$base/1200x630bb.jpg"))
        assertEquals("$base/source/10000x0w-999.jpg", AppleMusicService.maxArtworkUrl("$base/source/165x165bb.jpg"))

        assertEquals(
            "https://x/image/thumb/Podcasts123/v4/xx/mza_348.jpg/10000x0w-999.jpg",
            AppleMusicService.maxArtworkUrl("https://x/image/thumb/Podcasts123/v4/xx/mza_348.jpg/100x100bb.jpg")
        )
    }

    @Test
    fun `maxArtworkUrl is idempotent on an already-maximised url`() {
        val maxed = "https://is1-ssl.mzstatic.com/image/thumb/Music/v4/aa/bb/cc/xyz/10000x0w-999.jpg"
        assertEquals(maxed, AppleMusicService.maxArtworkUrl(maxed))
    }

    @Test
    fun `parseReleaseDate accepts catalog date-only and iTunes datetime, and rejects garbage`() {
        assertEquals(LocalDate.of(2019, 6, 21), AppleMusicService.parseReleaseDate("2019-06-21"))
        assertEquals(LocalDate.of(2019, 6, 21), AppleMusicService.parseReleaseDate("2019-06-21T12:00:00Z"))
        assertNull(AppleMusicService.parseReleaseDate(null))
        assertNull(AppleMusicService.parseReleaseDate("not-a-date"))
    }

    @Test
    fun `getAlbumsByIds maximises artwork and parses date via the iTunes fallback`() = runBlocking {
        every { environment.config.propertyOrNull("appleMusic.teamId") } returns null
        every { environment.config.propertyOrNull("appleMusic.keyId") } returns null
        every { environment.config.propertyOrNull("appleMusic.p8Path") } returns null

        mockEngine = MockEngine { request ->
            assertEquals("/lookup", request.url.encodedPath)
            assertEquals("67890", request.url.parameters["id"])
            respond(
                content = """
                    {
                      "resultCount": 2,
                      "results": [
                        {"wrapperType":"collection","collectionId":67890,"artistName":"Test Artist","collectionName":"Test Album","artworkUrl100":"https://example.com/100x100bb.jpg","trackCount":12,"releaseDate":"2019-06-21T12:00:00Z"},
                        {"wrapperType":"track","collectionId":67890,"trackId":1,"trackName":"Song","artworkUrl100":"https://example.com/100x100bb.jpg","trackCount":12}
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/javascript; charset=utf-8")
            )
        }
        every { ApiClient.instance } returns HttpClient(mockEngine) {
            install(ContentNegotiation) { json(ApplicationScope.json) }
        }

        val albums = appleMusicService.getAlbumsByIds(
            IMetadataService.MetadataType.appleMusic,
            listOf("appleMusic:67890")
        )

        assertEquals(1, albums.size)
        assertEquals("appleMusic:67890", albums[0].id)
        assertEquals("Test Album", albums[0].title)
        assertEquals(12, albums[0].trackCount)
        assertEquals(LocalDate.of(2019, 6, 21), albums[0].releaseDate)
        assertEquals("https://example.com/10000x0w-999.jpg", albums[0].images[0].url)
    }
}
