package dev.dertyp.services.podcast.index

import dev.dertyp.core.HttpClientFactory
import dev.dertyp.credentials.CredentialKind
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.services.credentials.CredentialProvider
import dev.dertyp.services.credentials.FakeCredentialServer
import dev.dertyp.services.credentials.FakeCredentialServer.Companion.grant
import dev.dertyp.services.credentials.LocalCredentialProvider
import dev.dertyp.services.credentials.RoutingCredentialProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class PodcastIndexRemoteGrantTest {
    private val server = FakeCredentialServer(listOf(grant(CredentialNames.YOUTUBE_API))).apply {
        credentials[CredentialNames.PODCAST_INDEX_API] = ResolvedCredential.ApiKeyPair(CredentialNames.PODCAST_INDEX_API, "pi-key", "pi-secret")
    }
    private val local = mockk<LocalCredentialProvider> {
        every { isAvailable(any()) } returns false
        coEvery { resolve(any()) } returns null
    }
    private val remote = server.provider()
    private val routing = RoutingCredentialProvider(local, remote)

    @BeforeEach
    fun setUp() {
        startKoin {
            modules(module {
                single<HttpClientFactory> { server.httpClientFactory }
                single<CredentialProvider> { routing }
            })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `a newly granted podcast index credential configures the index without a restart`() = runBlocking {
        remote.connect()
        val index = PodcastIndexOrgIndex()
        assertFalse(index.isConfigured())

        server.grants = server.grants + grant(CredentialNames.PODCAST_INDEX_API, CredentialKind.API_KEY_PAIR)
        assertEquals(
            ResolvedCredential.ApiKeyPair(CredentialNames.PODCAST_INDEX_API, "pi-key", "pi-secret"),
            routing.resolve(CredentialNames.PODCAST_INDEX_API),
        )

        assertTrue(index.isConfigured())
    }
}
