package dev.dertyp.services.credentials

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.HttpClientFactory
import dev.dertyp.core.db.dbQuery
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.db.PluginSettingTable
import dev.dertyp.services.metadata.AcoustIdCredentialSource
import dev.dertyp.services.podcast.index.PodcastIndexCredentialSource
import dev.dertyp.services.ui.PluginSettingsService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path
import java.security.interfaces.ECPublicKey
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.encoding.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalCredentialProviderTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    @Volatile
    private var tokenStatus = HttpStatusCode.OK

    private val engine = MockEngine { request ->
        requests += request
        respond(
            """{"access_token":"token-${requests.size}","token_type":"Bearer","expires_in":3600}""",
            tokenStatus,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private val httpClientFactory = mockk<HttpClientFactory>().also {
        val client = HttpClient(engine)
        every { it.api } returns client
    }

    private val cipher = CredentialCipher(MapApplicationConfig("credentials.encryptionKey" to "test-key"))
    private val settingsService = PluginSettingsService()

    private fun store(vararg values: Pair<String, String>): LocalCredentialStore {
        val config = MapApplicationConfig(*values)
        return LocalCredentialStore(
            settingsService,
            config,
            cipher,
            AcoustIdCredentialSource(settingsService, config, cipher)
        )
    }

    private fun provider(vararg values: Pair<String, String>): LocalCredentialProvider = provider(store(*values))

    private fun provider(store: LocalCredentialStore, signer: AppleDeveloperTokenSigner = AppleDeveloperTokenSigner()) =
        LocalCredentialProvider(
            ClientCredentialsExchange(httpClientFactory),
            signer,
            store,
            LocalPluginCredentialStore(settingsService, cipher),
        )

    private fun setup(dialect: DbDialect) = runBlocking {
        TestDatabase.connect(dialect, "local_credential_provider_test")
        dbQuery { SchemaUtils.create(PluginSettingTable) }
    }

    @AfterEach
    fun tearDown() = TestDatabase.cleanUp()

    @Test
    fun `availability follows the configuration`() {
        val provider = provider(
            "tidal.clientId" to "tid",
            "tidal.clientSecret" to "tsecret",
            "spotify.clientId" to "sid",
            "youtube.apiKey" to "yt",
            "linkresolver.apiKey" to "",
            "imageCache.token" to "img",
            "acoustid.apiKey" to "acoustic",
        )

        assertEquals(CredentialMode.LOCAL, provider.mode)
        assertTrue(provider.isAvailable(CredentialNames.TIDAL_API))
        assertFalse(provider.isAvailable(CredentialNames.SPOTIFY_API))
        assertTrue(provider.isAvailable(CredentialNames.YOUTUBE_API))
        assertFalse(provider.isAvailable(CredentialNames.LINKRESOLVER_API))
        assertTrue(provider.isAvailable(CredentialNames.IMAGE_CACHE_TOKEN))
        assertTrue(provider.isAvailable(CredentialNames.THEAUDIODB_API))
        assertTrue(provider.isAvailable(CredentialNames.ACOUSTID_API))
        assertFalse(provider.isAvailable(CredentialNames.PODCAST_INDEX_API))
        assertFalse(provider.isAvailable(CredentialNames.APPLE_MUSIC_DEVELOPER))
        assertFalse(provider.isAvailable(CredentialNames.IMPORTER_TIDDL))
        assertFalse(provider.isAvailable(CredentialNames.IMPORTER_GAMDL))
        assertFalse(provider.isAvailable(CredentialNames.plugin("alpha", "api")))
        assertFalse(provider.isManagedRemotely(CredentialNames.TIDAL_API))
    }

    @Test
    fun `api keys come from the configuration`() = runBlocking {
        val provider = provider("youtube.apiKey" to "yt", "linkresolver.apiKey" to "lr", "imageCache.token" to "img")

        assertEquals(
            ResolvedCredential.ApiKey(CredentialNames.YOUTUBE_API, "yt"),
            provider.resolve(CredentialNames.YOUTUBE_API)
        )
        assertEquals(
            ResolvedCredential.ApiKey(CredentialNames.LINKRESOLVER_API, "lr"),
            provider.resolve(CredentialNames.LINKRESOLVER_API)
        )
        assertEquals(
            ResolvedCredential.ApiKey(CredentialNames.IMAGE_CACHE_TOKEN, "img"),
            provider.resolve(CredentialNames.IMAGE_CACHE_TOKEN)
        )
        assertNull(provider.resolve(CredentialNames.IMPORTER_TIDDL))
        assertNull(provider.resolve(CredentialNames.APPLE_MUSIC_DEVELOPER))
    }

    @Test
    fun `theaudiodb falls back to the public key`() = runBlocking {
        assertEquals(
            ResolvedCredential.ApiKey(CredentialNames.THEAUDIODB_API, "123"),
            provider().resolve(CredentialNames.THEAUDIODB_API)
        )
        assertEquals(
            ResolvedCredential.ApiKey(CredentialNames.THEAUDIODB_API, "own"),
            provider("theaudiodb.apiKey" to "own").resolve(CredentialNames.THEAUDIODB_API),
        )
    }

    @Test
    fun `tidal exchanges with basic auth and the token is cached`() = runBlocking {
        val provider = provider("tidal.clientId" to "tid", "tidal.clientSecret" to "tsecret")

        val token = provider.resolve(CredentialNames.TIDAL_API) as ResolvedCredential.AccessToken
        provider.resolve(CredentialNames.TIDAL_API)

        assertEquals("token-1", token.accessToken)
        assertTrue(token.expiresAt!! > System.currentTimeMillis())
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals(LocalCredentialProvider.TIDAL_TOKEN_URL, request.url.toString())
        assertEquals("Basic ${Base64.encode("tid:tsecret".toByteArray())}", request.headers[HttpHeaders.Authorization])
        val form = (request.body as FormDataContent).formData
        assertEquals("client_credentials", form["grant_type"])
        assertNull(form["client_secret"])
    }

    @Test
    fun `spotify exchanges with form credentials`() = runBlocking {
        val provider = provider("spotify.clientId" to "sid", "spotify.clientSecret" to "ssecret")

        provider.resolve(CredentialNames.SPOTIFY_API)

        val request = requests.single()
        assertEquals(LocalCredentialProvider.SPOTIFY_TOKEN_URL, request.url.toString())
        assertNull(request.headers[HttpHeaders.Authorization])
        val form = (request.body as FormDataContent).formData
        assertEquals("client_credentials", form["grant_type"])
        assertEquals("sid", form["client_id"])
        assertEquals("ssecret", form["client_secret"])
    }

    @Test
    fun `a failed exchange resolves to null`() = runBlocking {
        tokenStatus = HttpStatusCode.BadRequest
        val provider = provider("tidal.clientId" to "tid", "tidal.clientSecret" to "tsecret")

        assertNull(provider.resolve(CredentialNames.TIDAL_API))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `acoustid and podcast index come from their credential sources`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val provider = provider("acoustid.apiKey" to "env-acoustid")

        assertEquals(
            ResolvedCredential.ApiKey(CredentialNames.ACOUSTID_API, "env-acoustid"),
            provider.resolve(CredentialNames.ACOUSTID_API)
        )
        assertNull(provider.resolve(CredentialNames.PODCAST_INDEX_API))

        AcoustIdCredentialSource(settingsService, MapApplicationConfig(), cipher)
            .store(mapOf(AcoustIdCredentialSource.KEY_API_KEY to "stored-acoustid"))
        PodcastIndexCredentialSource(
            settingsService.forPlugin(PodcastIndexCredentialSource.PLUGIN_ID),
            MapApplicationConfig(),
            cipher
        )
            .store(
                mapOf(
                    PodcastIndexCredentialSource.KEY_API_KEY to "pkey",
                    PodcastIndexCredentialSource.KEY_API_SECRET to "psecret"
                )
            )

        assertEquals(
            ResolvedCredential.ApiKey(CredentialNames.ACOUSTID_API, "stored-acoustid"),
            provider.resolve(CredentialNames.ACOUSTID_API)
        )
        assertEquals(
            ResolvedCredential.ApiKeyPair(CredentialNames.PODCAST_INDEX_API, "pkey", "psecret"),
            provider.resolve(CredentialNames.PODCAST_INDEX_API),
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `plugin names resolve from the local plugin store`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        LocalPluginCredentialStore(settingsService, cipher).store(
            "alpha",
            "api",
            ResolvedCredential.ApiKey("api", "key")
        )
        val provider = provider()
        provider.startService()

        assertTrue(provider.isAvailable(CredentialNames.plugin("alpha", "api")))
        assertEquals(ResolvedCredential.ApiKey("api", "key"), provider.resolve(CredentialNames.plugin("alpha", "api")))
        assertNull(provider.resolve(CredentialNames.plugin("alpha", "missing")))
        provider.stopService()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `stored values are encrypted and override the environment without a restart`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val store =
            store("youtube.apiKey" to "env-yt", "tidal.clientId" to "env-id", "tidal.clientSecret" to "env-secret")
        val provider = provider(store)
        assertFalse(provider.isAvailable(CredentialNames.LINKRESOLVER_API))
        assertEquals(CredentialOrigin.ENVIRONMENT, store.origin(CredentialNames.YOUTUBE_API))

        store.store(CredentialNames.YOUTUBE_API, mapOf(LocalCredentialStore.FIELD_API_KEY to "stored-yt"))
        store.store(CredentialNames.LINKRESOLVER_API, mapOf(LocalCredentialStore.FIELD_API_KEY to "stored-lr"))

        assertEquals(CredentialOrigin.STORED, store.origin(CredentialNames.YOUTUBE_API))
        assertTrue(provider.isAvailable(CredentialNames.LINKRESOLVER_API))
        assertEquals(
            ResolvedCredential.ApiKey(CredentialNames.YOUTUBE_API, "stored-yt"),
            provider.resolve(CredentialNames.YOUTUBE_API)
        )
        assertEquals(
            ResolvedCredential.ApiKey(CredentialNames.LINKRESOLVER_API, "stored-lr"),
            provider.resolve(CredentialNames.LINKRESOLVER_API)
        )

        val raw = settingsService.getAll(LocalCredentialStore.PLUGIN_ID)
        assertEquals(setOf("youtube.api.apiKey", "linkresolver.api.apiKey"), raw.keys)
        assertTrue(raw.values.all { it.startsWith(CredentialCipher.PREFIX) })
        assertTrue(raw.values.none { it.contains("stored-") })
        assertEquals("stored-yt", cipher.decrypt("youtube.api.apiKey", raw.getValue("youtube.api.apiKey")))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `clearing falls back to the environment`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val store = store("youtube.apiKey" to "env-yt")
        val provider = provider(store)
        store.store(CredentialNames.YOUTUBE_API, mapOf(LocalCredentialStore.FIELD_API_KEY to "stored-yt"))
        store.store(CredentialNames.IMAGE_CACHE_TOKEN, mapOf(LocalCredentialStore.FIELD_TOKEN to "stored-img"))
        assertTrue(provider.isAvailable(CredentialNames.IMAGE_CACHE_TOKEN))

        store.clear(CredentialNames.YOUTUBE_API)
        store.clear(CredentialNames.IMAGE_CACHE_TOKEN)

        assertEquals(CredentialOrigin.ENVIRONMENT, store.origin(CredentialNames.YOUTUBE_API))
        assertEquals(
            ResolvedCredential.ApiKey(CredentialNames.YOUTUBE_API, "env-yt"),
            provider.resolve(CredentialNames.YOUTUBE_API)
        )
        assertEquals(CredentialOrigin.NONE, store.origin(CredentialNames.IMAGE_CACHE_TOKEN))
        assertFalse(provider.isAvailable(CredentialNames.IMAGE_CACHE_TOKEN))
        assertNull(provider.resolve(CredentialNames.IMAGE_CACHE_TOKEN))
        assertTrue(settingsService.getAll(LocalCredentialStore.PLUGIN_ID).isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `stored client credentials replace the configured ones and drop the cached token`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val store = store("tidal.clientId" to "env-id", "tidal.clientSecret" to "env-secret")
            val provider = provider(store)

            assertEquals(
                "token-1",
                (provider.resolve(CredentialNames.TIDAL_API) as ResolvedCredential.AccessToken).accessToken
            )
            store.store(
                CredentialNames.TIDAL_API,
                mapOf(
                    LocalCredentialStore.FIELD_CLIENT_ID to "stored-id",
                    LocalCredentialStore.FIELD_CLIENT_SECRET to "stored-secret"
                ),
            )
            assertEquals(
                "token-2",
                (provider.resolve(CredentialNames.TIDAL_API) as ResolvedCredential.AccessToken).accessToken
            )
            assertEquals(
                "Basic ${Base64.encode("stored-id:stored-secret".toByteArray())}",
                requests.last().headers[HttpHeaders.Authorization]
            )

            store.store(CredentialNames.TIDAL_API, mapOf(LocalCredentialStore.FIELD_CLIENT_SECRET to "rotated-secret"))
            assertEquals(
                "token-3",
                (provider.resolve(CredentialNames.TIDAL_API) as ResolvedCredential.AccessToken).accessToken
            )
            assertEquals(
                "Basic ${Base64.encode("stored-id:rotated-secret".toByteArray())}",
                requests.last().headers[HttpHeaders.Authorization]
            )
            provider.resolve(CredentialNames.TIDAL_API)
            assertEquals(3, requests.size)
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `acoustid and podcast index are stored under their existing keys`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val store = store()
        val provider = provider(store)

        store.store(CredentialNames.ACOUSTID_API, mapOf(LocalCredentialStore.FIELD_API_KEY to "acoustid-key"))
        store.store(
            CredentialNames.PODCAST_INDEX_API,
            mapOf(LocalCredentialStore.FIELD_API_KEY to "pkey", LocalCredentialStore.FIELD_API_SECRET to "psecret"),
        )

        val acoustId = settingsService.getAll(AcoustIdCredentialSource.PLUGIN_ID)
        assertEquals(
            "acoustid-key",
            cipher.decrypt(
                AcoustIdCredentialSource.KEY_API_KEY,
                acoustId.getValue(AcoustIdCredentialSource.KEY_API_KEY)
            )
        )
        val podcastIndex = settingsService.getAll(PodcastIndexCredentialSource.PLUGIN_ID)
        assertEquals(
            setOf(PodcastIndexCredentialSource.KEY_API_KEY, PodcastIndexCredentialSource.KEY_API_SECRET),
            podcastIndex.keys
        )
        assertTrue(settingsService.getAll(LocalCredentialStore.PLUGIN_ID).isEmpty())
        assertTrue(provider.isAvailable(CredentialNames.ACOUSTID_API))
        assertTrue(provider.isAvailable(CredentialNames.PODCAST_INDEX_API))
        assertEquals(
            ResolvedCredential.ApiKey(CredentialNames.ACOUSTID_API, "acoustid-key"),
            provider.resolve(CredentialNames.ACOUSTID_API)
        )
        assertEquals(
            ResolvedCredential.ApiKeyPair(CredentialNames.PODCAST_INDEX_API, "pkey", "psecret"),
            provider.resolve(CredentialNames.PODCAST_INDEX_API)
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `values stored before under the old keys are listed as stored after a start`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        settingsService.setAll(
            AcoustIdCredentialSource.PLUGIN_ID,
            mapOf(AcoustIdCredentialSource.KEY_API_KEY to cipher.encrypt(AcoustIdCredentialSource.KEY_API_KEY, "old"))
        )
        val store = store()
        val provider = provider(store)
        assertFalse(store.hasStored(CredentialNames.ACOUSTID_API))

        store.refresh()

        assertTrue(store.hasStored(CredentialNames.ACOUSTID_API))
        assertEquals(CredentialOrigin.STORED, store.origin(CredentialNames.ACOUSTID_API))
        assertTrue(provider.isAvailable(CredentialNames.ACOUSTID_API))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `apple developer tokens are signed with the stored private key`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val keyPair = AppleTestKeys.keyPair()
        val store = store()
        val provider = provider(store)
        assertFalse(provider.isAvailable(CredentialNames.APPLE_MUSIC_DEVELOPER))

        store.store(
            CredentialNames.APPLE_MUSIC_DEVELOPER,
            mapOf(
                LocalCredentialStore.FIELD_TEAM_ID to "TEAM",
                LocalCredentialStore.FIELD_KEY_ID to "KEY",
                LocalCredentialStore.FIELD_P8 to AppleTestKeys.pem(keyPair),
            ),
        )

        assertTrue(provider.isAvailable(CredentialNames.APPLE_MUSIC_DEVELOPER))
        val token = provider.resolve(CredentialNames.APPLE_MUSIC_DEVELOPER) as ResolvedCredential.DeveloperToken
        val decoded = JWT.require(Algorithm.ECDSA256(keyPair.public as ECPublicKey, null)).build().verify(token.token)
        assertEquals("KEY", decoded.keyId)
        assertEquals("TEAM", decoded.issuer)

        store.store(CredentialNames.APPLE_MUSIC_DEVELOPER, mapOf(LocalCredentialStore.FIELD_KEY_ID to "NEWKEY"))
        val renewed = provider.resolve(CredentialNames.APPLE_MUSIC_DEVELOPER) as ResolvedCredential.DeveloperToken
        assertEquals("NEWKEY", JWT.decode(renewed.token).keyId)
    }

    @Test
    fun `apple developer tokens fall back to the configured p8 file`(@TempDir dir: Path) = runBlocking {
        val keyPair = AppleTestKeys.keyPair()
        val file = dir.resolve("AuthKey.p8")
        Files.writeString(file, AppleTestKeys.pem(keyPair))
        val provider =
            provider("appleMusic.teamId" to "TEAM", "appleMusic.keyId" to "KEY", "appleMusic.p8Path" to file.toString())

        assertTrue(provider.isAvailable(CredentialNames.APPLE_MUSIC_DEVELOPER))
        val token = provider.resolve(CredentialNames.APPLE_MUSIC_DEVELOPER) as ResolvedCredential.DeveloperToken
        JWT.require(Algorithm.ECDSA256(keyPair.public as ECPublicKey, null)).build().verify(token.token)
        assertNull(
            provider(
                "appleMusic.teamId" to "TEAM",
                "appleMusic.keyId" to "KEY",
                "appleMusic.p8Path" to dir.resolve("missing.p8").toString()
            )
                .resolve(CredentialNames.APPLE_MUSIC_DEVELOPER)
        )
    }

    @Test
    fun `the apple private key is validated`() {
        assertTrue(AppleDeveloperTokenSigner.isValidPrivateKey(AppleTestKeys.pem()))
        assertFalse(AppleDeveloperTokenSigner.isValidPrivateKey(""))
        assertFalse(AppleDeveloperTokenSigner.isValidPrivateKey("-----BEGIN PRIVATE KEY-----\nnot base64\n-----END PRIVATE KEY-----"))
        assertFalse(AppleDeveloperTokenSigner.isValidPrivateKey("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"))
        assertThrows<IllegalArgumentException> {
            AppleDeveloperTokenSigner().token(
                AppleDeveloperKey(
                    "TEAM",
                    "KEY",
                    "garbage"
                )
            )
        }
    }
}
