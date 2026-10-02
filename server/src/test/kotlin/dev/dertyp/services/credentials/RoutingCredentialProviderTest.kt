package dev.dertyp.services.credentials

import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.services.credentials.FakeCredentialServer.Companion.grant
import dev.dertyp.services.credentials.remote.RemoteCredentialProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutingCredentialProviderTest {
    private val server = FakeCredentialServer(listOf(grant(CredentialNames.YOUTUBE_API))).apply {
        credentials[CredentialNames.YOUTUBE_API] = ResolvedCredential.ApiKey(CredentialNames.YOUTUBE_API, "remote-key")
    }

    private val local = mockk<LocalCredentialProvider>().also {
        every { it.isAvailable(any()) } returns true
        coEvery { it.resolve(any()) } answers { ResolvedCredential.ApiKey(firstArg(), "local-key") }
    }

    private fun routing(remote: RemoteCredentialProvider) = RoutingCredentialProvider(local, remote)

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
