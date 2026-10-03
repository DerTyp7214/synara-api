package dev.dertyp.services.hue

import dev.dertyp.core.HttpClientFactory
import dev.dertyp.core.apiDefaults
import dev.dertyp.data.HueBridgeCandidate
import dev.dertyp.plugins.JmDNSHolder
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class HueDiscoveryServiceTest {
    private val httpClientFactory = mockk<HttpClientFactory>(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkObject(MdnsQuery)
        mockkObject(JmDNSHolder)
        every { JmDNSHolder.instance } returns null
        startKoin { modules(module { single { httpClientFactory } }) }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        unmockkAll()
    }

    private fun mdnsAnswers(block: () -> Set<String>) {
        every { MdnsQuery.responders(HueDiscoveryService.SERVICE_TYPE, 3.seconds) } answers { block() }
    }

    private fun cloudAnswers(candidates: List<HueBridgeCandidate>) {
        val body = buildJsonArray {
            candidates.forEach { candidate ->
                add(buildJsonObject {
                    put("id", candidate.bridgeId)
                    put("internalipaddress", candidate.ip)
                })
            }
        }.toString()
        val engine =
            MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        every { httpClientFactory.api } returns HttpClient(engine) { apiDefaults() }
    }

    private fun probeAnswers(block: (String) -> HueBridgeConfig?) {
        val engine = MockEngine { request ->
            val config = block(request.url.host)
            if (config == null) {
                respondError(HttpStatusCode.ServiceUnavailable)
            } else {
                respond(
                    Json.encodeToString(HueBridgeConfig.serializer(), config),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }
        every { httpClientFactory.create(any<HttpClientEngineFactory<OkHttpConfig>>(), any(), any()) } answers {
            val configure = thirdArg<HttpClientConfig<*>.() -> Unit>()
            HttpClient(engine) { configure() }
        }
    }

    @Test
    fun `stale cloud entries are dropped and reachable ones are verified and deduplicated`() = runBlocking {
        val service = HueDiscoveryService()
        mdnsAnswers { setOf("192.168.178.21") }
        cloudAnswers(
            listOf("192.168.178.21", "192.168.178.20", "192.168.178.184", "192.168.178.137", "192.168.178.46")
                .map { HueBridgeCandidate(bridgeId = "001788fffe0000aa", ip = it) }
        )
        probeAnswers { ip ->
            when (ip) {
                "192.168.178.21" -> HueBridgeConfig(
                    name = "Living room",
                    bridgeid = "001788FFFE0000AA",
                    modelid = "BSB002"
                )

                "192.168.178.46" -> HueBridgeConfig(name = "Old", bridgeid = "001788FFFE0000AA", modelid = "BSB002")
                else -> null
            }
        }

        val found = service.discover(force = true)

        assertEquals(1, found.size)
        val bridge = found.single()
        assertEquals("192.168.178.21", bridge.ip)
        assertEquals("001788fffe0000aa", bridge.bridgeId)
        assertEquals("Living room", bridge.name)
        assertEquals(found, service.cached())
    }

    @Test
    fun `results are cached until forced`() = runBlocking {
        val service = HueDiscoveryService()
        val calls = AtomicInteger()
        mdnsAnswers { calls.incrementAndGet(); setOf("10.0.0.5") }
        cloudAnswers(emptyList())
        probeAnswers { HueBridgeConfig(bridgeid = "abc") }

        assertEquals(1, service.discover().size)
        assertEquals(1, service.discover().size)
        assertEquals(1, calls.get())
        assertEquals(1, service.discover(force = true).size)
        assertEquals(2, calls.get())
    }

    @Test
    fun `candidates that never answer are excluded and no probe answer means empty`() = runBlocking {
        val service = HueDiscoveryService()
        mdnsAnswers { emptySet() }
        cloudAnswers(
            listOf(
                HueBridgeCandidate(bridgeId = "x", ip = "10.0.0.1"),
                HueBridgeCandidate(bridgeId = "y", ip = "10.0.0.2")
            )
        )
        probeAnswers { null }
        assertTrue(service.discover(force = true).isEmpty())
    }
}
