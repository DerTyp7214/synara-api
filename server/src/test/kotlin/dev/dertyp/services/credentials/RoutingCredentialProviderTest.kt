package dev.dertyp.services.credentials

import dev.dertyp.credentials.CredentialKind
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.services.credentials.FakeCredentialServer.Companion.grant
import dev.dertyp.services.credentials.remote.RemoteCredentialProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RoutingCredentialProviderTest {
    private val server = FakeCredentialServer(listOf(grant(CredentialNames.YOUTUBE_API))).apply {
        credentials[CredentialNames.YOUTUBE_API] = ResolvedCredential.ApiKey(CredentialNames.YOUTUBE_API, "remote-key")
    }

    private val local = mockk<LocalCredentialProvider>().also {
        every { it.isAvailable(any()) } returns true
        coEvery { it.resolve(any()) } answers { ResolvedCredential.ApiKey(firstArg(), "local-key") }
    }

    private val emptyLocal = mockk<LocalCredentialProvider>().also {
        every { it.isAvailable(any()) } returns false
        coEvery { it.resolve(any()) } returns null
    }

    private var now = Instant.fromEpochSeconds(1_700_000_000)

    @BeforeEach
    fun setUp() {
        mockkObject(Clock.System)
        every { Clock.System.now() } answers { now }
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(Clock.System)
    }

    private fun routing(remote: RemoteCredentialProvider, localProvider: LocalCredentialProvider = local) =
        RoutingCredentialProvider(localProvider, remote)

    @Test
    fun `a local miss on a newly granted name resolves remotely and updates the grants`() = runBlocking {
        val remote = server.provider()
        remote.connect()
        val routing = routing(remote, emptyLocal)
        server.grants = server.grants + grant(CredentialNames.PODCAST_INDEX_API, CredentialKind.API_KEY_PAIR)
        server.credentials[CredentialNames.PODCAST_INDEX_API] =
            ResolvedCredential.ApiKeyPair(CredentialNames.PODCAST_INDEX_API, "pi-key", "pi-secret")
        assertFalse(routing.isAvailable(CredentialNames.PODCAST_INDEX_API))

        val resolved = routing.resolve(CredentialNames.PODCAST_INDEX_API)

        assertEquals(ResolvedCredential.ApiKeyPair(CredentialNames.PODCAST_INDEX_API, "pi-key", "pi-secret"), resolved)
        assertTrue(routing.isManagedRemotely(CredentialNames.PODCAST_INDEX_API))
        assertTrue(routing.isAvailable(CredentialNames.PODCAST_INDEX_API))
        assertEquals(2, server.tokenCalls.get())
    }

    @Test
    fun `local misses on names that are not granted refresh at most once a minute`() = runBlocking {
        val remote = server.provider()
        remote.connect()
        val routing = routing(remote, emptyLocal)

        assertNull(routing.resolve(CredentialNames.SPOTIFY_API))
        assertEquals(2, server.tokenCalls.get())

        now += 59.seconds
        assertNull(routing.resolve(CredentialNames.SPOTIFY_API))
        assertNull(routing.resolve(CredentialNames.ACOUSTID_API))
        assertEquals(2, server.tokenCalls.get())

        now += 1.seconds
        assertNull(routing.resolve(CredentialNames.ACOUSTID_API))
        assertEquals(3, server.tokenCalls.get())
        assertEquals(0, server.fetches(CredentialNames.SPOTIFY_API))
        assertEquals(0, server.fetches(CredentialNames.ACOUSTID_API))
    }

    @Test
    fun `a local hit makes no call to the credential server`() = runBlocking {
        val remote = server.provider()
        remote.connect()
        val routing = routing(remote)
        server.grants = server.grants + grant(CredentialNames.SPOTIFY_API)

        assertEquals("local-key", (routing.resolve(CredentialNames.SPOTIFY_API) as ResolvedCredential.ApiKey).key)
        assertEquals(1, server.tokenCalls.get())
        assertEquals(0, server.fetches(CredentialNames.SPOTIFY_API))
    }

    @Test
    fun `granted names are resolved remotely`() = runBlocking {
        val remote = server.provider()
        remote.connect()
        val routing = routing(remote)

        assertEquals(CredentialMode.REMOTE, routing.mode)
        assertTrue(routing.isManagedRemotely(CredentialNames.YOUTUBE_API))
        assertEquals("remote-key", (routing.resolve(CredentialNames.YOUTUBE_API) as ResolvedCredential.ApiKey).key)
        coVerify(exactly = 0) { local.resolve(CredentialNames.YOUTUBE_API) }
    }

    @Test
    fun `ungranted names fall back to local`() = runBlocking {
        val remote = server.provider()
        remote.connect()
        val routing = routing(remote)

        assertFalse(routing.isManagedRemotely(CredentialNames.SPOTIFY_API))
        assertEquals("local-key", (routing.resolve(CredentialNames.SPOTIFY_API) as ResolvedCredential.ApiKey).key)
        assertEquals(0, server.fetches(CredentialNames.SPOTIFY_API))
    }

    @Test
    fun `without a configured connection everything is local`() = runBlocking {
        val remote = server.provider(server.client(server.connectionSource(clientId = null)))
        remote.connect()
        val routing = routing(remote)

        assertEquals(CredentialMode.LOCAL, routing.mode)
        assertFalse(routing.isManagedRemotely(CredentialNames.YOUTUBE_API))
        assertEquals("local-key", (routing.resolve(CredentialNames.YOUTUBE_API) as ResolvedCredential.ApiKey).key)
        assertEquals(0, server.tokenCalls.get())
    }

    @Test
    fun `an unreachable server yields null for granted names instead of the local value`() = runBlocking {
        val remote = server.provider()
        remote.connect()
        val routing = routing(remote)
        server.unreachable = true

        assertTrue(routing.isAvailable(CredentialNames.YOUTUBE_API))
        assertNull(routing.resolve(CredentialNames.YOUTUBE_API))
        coVerify(exactly = 0) { local.resolve(any()) }
    }
}
