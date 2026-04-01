package dev.dertyp.core

import dev.dertyp.ApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class HttpClientQueueServiceTest {

    private lateinit var mockEngine: MockEngine
    private lateinit var mockHttpClient: HttpClient
    private lateinit var queueService: HttpClientQueueService

    @BeforeEach
    fun setup() = runBlocking {
        mockEngine = MockEngine { _ ->
            delay(100)
            respond(
                content = "OK",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Text.Plain.toString())
            )
        }

        mockHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(ApplicationScope.json)
            }
        }

        mockkObject(ApiClient)
        every { ApiClient.instance } returns mockHttpClient

        queueService = HttpClientQueueService()
        queueService.startService()
    }

    @AfterEach
    fun tearDown() = runBlocking {
        queueService.stopService()
        unmockkAll()
    }

    @Test
    fun `should process high priority requests before normal and low`() = runBlocking {
        val results = CopyOnWriteArrayList<String>()
        val host = "https://example.com"

        val jobs = mutableListOf<Job>()

        jobs.add(launch {
            queueService.enqueue("$host/first", HttpClientPriority.NORMAL)
            results.add("first")
        })

        delay(50)

        jobs.add(launch {
            queueService.enqueue("$host/low", HttpClientPriority.LOW)
            results.add("low")
        })
        jobs.add(launch {
            queueService.enqueue("$host/high", HttpClientPriority.HIGH)
            results.add("high")
        })
        jobs.add(launch {
            queueService.enqueue("$host/normal", HttpClientPriority.NORMAL)
            results.add("normal")
        })

        withTimeout(10000) {
            jobs.joinAll()
        }

        assertEquals(listOf("first", "high", "normal", "low"), results)
    }

    @Test
    fun `should process requests in FIFO order for same priority`() = runBlocking {
        val results = CopyOnWriteArrayList<String>()
        val host = "https://example.com"

        val jobs = mutableListOf<Job>()

        jobs.add(launch {
            queueService.enqueue("$host/first", HttpClientPriority.NORMAL)
            results.add("first")
        })

        delay(50)

        jobs.add(launch {
            queueService.enqueue("$host/second", HttpClientPriority.NORMAL)
            results.add("second")
        })
        jobs.add(launch {
            queueService.enqueue("$host/third", HttpClientPriority.NORMAL)
            results.add("third")
        })

        withTimeout(10000) {
            jobs.joinAll()
        }

        assertEquals(listOf("first", "second", "third"), results)
    }
}
