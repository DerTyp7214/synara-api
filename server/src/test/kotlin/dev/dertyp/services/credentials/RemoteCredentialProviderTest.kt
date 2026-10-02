package dev.dertyp.services.credentials

import dev.dertyp.credentials.CredentialFile
import dev.dertyp.credentials.CredentialKind
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.services.credentials.FakeCredentialServer.Companion.grant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteCredentialProviderTest {
    private val server = FakeCredentialServer(
        listOf(
            grant(CredentialNames.YOUTUBE_API),
            grant(CredentialNames.LINKRESOLVER_API),
            grant(CredentialNames.TIDAL_API, CredentialKind.OAUTH_CLIENT_CREDENTIALS),
            grant(CredentialNames.IMPORTER_TIDDL, CredentialKind.TIDAL_DEVICE_SESSION, writeBack = true),
        )
    ).apply {
        credentials[CredentialNames.YOUTUBE_API] = ResolvedCredential.ApiKey(CredentialNames.YOUTUBE_API, "yt-key")
        credentials[CredentialNames.LINKRESOLVER_API] = ResolvedCredential.ApiKey(CredentialNames.LINKRESOLVER_API, "lr-key")
        credentials[CredentialNames.IMPORTER_TIDDL] = ResolvedCredential.Files(
            CredentialNames.IMPORTER_TIDDL,
            listOf(CredentialFile("auth.json", "e30=")),
            "fp-1",
        )
    }

    private fun accessToken(expiresInMs: Long) = ResolvedCredential.AccessToken(
        CredentialNames.TIDAL_API,
        "access",
        expiresAt = System.currentTimeMillis() + expiresInMs,
    )

    @Test
    fun `the token is exchanged once and reused across names`() = runBlocking {
        val provider = server.provider()

        assertEquals("yt-key", (provider.resolve(CredentialNames.YOUTUBE_API) as ResolvedCredential.ApiKey).key)
        assertEquals("lr-key", (provider.resolve(CredentialNames.LINKRESOLVER_API) as ResolvedCredential.ApiKey).key)

        assertEquals(1, server.tokenCalls.get())
        assertEquals(listOf("Bearer token-1", "Bearer token-1"), server.bearers.toList())
    }

    @Test
    fun `grants come from the token exchange`() = runBlocking {
        val provider = server.provider()
        assertFalse(provider.isManagedRemotely(CredentialNames.YOUTUBE_API))
        assertEquals(CredentialMode.REMOTE, provider.mode)

        provider.connect()

        assertTrue(provider.isManagedRemotely(CredentialNames.YOUTUBE_API))
        assertTrue(provider.isAvailable(CredentialNames.IMPORTER_TIDDL))
        assertFalse(provider.isManagedRemotely(CredentialNames.SPOTIFY_API))
    }

    @Test
    fun `an expired token is exchanged again`() = runBlocking {
        server.tokenTtlMs = 30_000
        val provider = server.provider()

        provider.resolve(CredentialNames.YOUTUBE_API)
        provider.resolve(CredentialNames.LINKRESOLVER_API)

        assertEquals(2, server.tokenCalls.get())
    }

    @Test
    fun `a rejected token is exchanged once more and the fetch is retried`() = runBlocking {
        val provider = server.provider()
        provider.connect()
        server.unauthorizedFetches = 1

        val resolved = provider.resolve(CredentialNames.YOUTUBE_API) as ResolvedCredential.ApiKey

        assertEquals("yt-key", resolved.key)
        assertEquals(2, server.tokenCalls.get())
        assertEquals(listOf("Bearer token-1", "Bearer token-2"), server.bearers.toList())
    }

    @Test
    fun `a second rejection gives up`() = runBlocking {
        val provider = server.provider()
        server.unauthorizedFetches = 2

        assertNull(provider.resolve(CredentialNames.YOUTUBE_API))
        assertEquals(2, server.tokenCalls.get())
        assertEquals(2, server.fetches(CredentialNames.YOUTUBE_API))
    }

    @Test
    fun `api keys are cached`() = runBlocking {
        val provider = server.provider()

        provider.resolve(CredentialNames.YOUTUBE_API)
        provider.resolve(CredentialNames.YOUTUBE_API)

        assertEquals(1, server.fetches(CredentialNames.YOUTUBE_API))
    }

    @Test
    fun `access tokens are cached until shortly before they expire`() = runBlocking {
        val provider = server.provider()

        server.credentials[CredentialNames.TIDAL_API] = accessToken(10 * 60_000L)
        provider.resolve(CredentialNames.TIDAL_API)
        provider.resolve(CredentialNames.TIDAL_API)
        assertEquals(1, server.fetches(CredentialNames.TIDAL_API))

        val other = server.provider()
        server.credentials[CredentialNames.TIDAL_API] = accessToken(30_000L)
        other.resolve(CredentialNames.TIDAL_API)
        other.resolve(CredentialNames.TIDAL_API)
        assertEquals(3, server.fetches(CredentialNames.TIDAL_API))
    }

    @Test
    fun `files are never cached`() = runBlocking {
        val provider = server.provider()

        val first = provider.resolve(CredentialNames.IMPORTER_TIDDL)
        provider.resolve(CredentialNames.IMPORTER_TIDDL)

        assertEquals("fp-1", (first as ResolvedCredential.Files).fingerprint)
        assertEquals(2, server.fetches(CredentialNames.IMPORTER_TIDDL))
    }

    @Test
    fun `a connection change drops cached credentials and grants`() = runBlocking {
        val client = server.client()
        val provider = server.provider(client)
        provider.resolve(CredentialNames.YOUTUBE_API)

        assertTrue(client.updateConnection(CredentialServerConnection(FakeCredentialServer.URL + "/", FakeCredentialServer.CLIENT_ID, FakeCredentialServer.CLIENT_SECRET, null)))
        assertFalse(provider.isManagedRemotely(CredentialNames.YOUTUBE_API))
        provider.resolve(CredentialNames.YOUTUBE_API)

        assertEquals(2, server.fetches(CredentialNames.YOUTUBE_API))
        assertEquals(2, server.tokenCalls.get())
    }

    @Test
    fun `an unconfigured connection resolves nothing`() = runBlocking {
        val provider = server.provider(server.client(server.connectionSource(clientSecret = null)))

        assertEquals(CredentialMode.LOCAL, provider.mode)
        provider.connect()
        assertNull(provider.resolve(CredentialNames.YOUTUBE_API))
        assertEquals(0, server.tokenCalls.get())
    }

    @Test
    fun `an unreachable server resolves to null`() = runBlocking {
        val provider = server.provider()
        server.unreachable = true

        assertNull(provider.resolve(CredentialNames.YOUTUBE_API))
    }

    @Test
    fun `write-back reports conflicts`() = runBlocking {
        val provider = server.provider()
        val files = listOf(CredentialFile("auth.json", "e30="))

        assertTrue(provider.writeBack(CredentialNames.IMPORTER_TIDDL, "fp-1", files))
        server.writeBackConflict = true
        assertFalse(provider.writeBack(CredentialNames.IMPORTER_TIDDL, "fp-1", files))

        assertEquals(2, server.writeBacks.size)
        assertEquals("fp-1", server.writeBacks.first().second.expectedFingerprint)
    }
}
