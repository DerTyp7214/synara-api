package dev.dertyp.routing.rest

import dev.dertyp.StreamInfo
import dev.dertyp.core.UnauthorizedException
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

@Serializable
data class Probe(val name: String, val count: Int = 0)

enum class Mode { FAST, SLOW }

interface ProbeApi {
    suspend fun find(id: UUID): Probe?
    suspend fun page(query: String, page: Int = 3, pageSize: Int = 50): String
    suspend fun secret(): Probe
    suspend fun explode(): Probe
    suspend fun touch()
    fun watch(): Flow<Probe?>
    fun stream(probeId: UUID, offset: Long = 0): Flow<ByteArray>?
}

private object ProbeDefaults : ProbeApi {
    override suspend fun find(id: UUID): Probe? = throw CapturedArguments(id)
    override suspend fun page(query: String, page: Int, pageSize: Int): String = throw CapturedArguments(query, page, pageSize)
    override suspend fun secret(): Probe = throw CapturedArguments()
    override suspend fun explode(): Probe = throw CapturedArguments()
    override suspend fun touch(): Unit = throw CapturedArguments()
    override fun watch(): Flow<Probe?> = throw CapturedArguments()
    override fun stream(probeId: UUID, offset: Long): Flow<ByteArray>? = throw CapturedArguments(probeId, offset)
}

private class FakeProbeService(private val file: File?) : ProbeApi, RestFileProvider {
    var touched = 0

    override suspend fun find(id: UUID): Probe? = if (id == KNOWN) Probe("known", 7) else null
    override suspend fun page(query: String, page: Int, pageSize: Int): String = "$query/$page/$pageSize"
    override suspend fun secret(): Probe = throw UnauthorizedException("no capability")
    override suspend fun explode(): Probe = throw IllegalStateException("kaboom")
    override suspend fun touch() {
        touched++
    }

    override fun watch(): Flow<Probe?> = flowOf(Probe("a", 1), null, Probe("b", 2))
    override fun stream(probeId: UUID, offset: Long): Flow<ByteArray> = flowOf("fallback".toByteArray())

    override suspend fun getFile(methodName: String, args: List<Any?>): StreamInfo? {
        if (methodName != "stream") return null
        val target = file ?: return null
        return StreamInfo(target, ContentType.Application.OctetStream, target.length(), target.name)
    }

    companion object {
        val KNOWN: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    }
}

class RestCallTest {
    private lateinit var directory: File
    private lateinit var file: File
    private val fileBytes = ByteArray(300) { (it % 97).toByte() }
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x11)
    private lateinit var fake: FakeProbeService

    @BeforeEach
    fun setUp() {
        directory = Files.createTempDirectory("rest-call").toFile()
        file = File(directory, "probe.bin")
        file.writeBytes(fileBytes)
        fake = FakeProbeService(file)
    }

    @AfterEach
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun ApplicationTestBuilder.setUpProbeApplication() {
        environment { config = MapApplicationConfig() }
        application {
            install(SSE)
            routing {
                val factory: suspend RoutingContext.() -> ProbeApi = { fake }
                suspend fun RoutingContext.probe(body: suspend RestCall<ProbeApi>.() -> Unit) =
                    restRoute(requireUser = false, ProbeApi::class.java, factory, body)

                get("/probe/find/{id}") {
                    probe {
                        val id = pathParam("id", RestConvert.uuid)
                        val result = restInvoke { service.find(id) } ?: return@probe
                        respondJson(result)
                    }
                }
                get("/probe/page") {
                    probe {
                        val query = queryParam("query", RestConvert.string)
                        val page = queryParam("page", RestConvert.int)
                        val pageSize = queryParam("pageSize", RestConvert.int)
                        val result = restInvoke {
                            val requiredQuery = required("query", query)
                            val defaults = if (page == null || pageSize == null) captureDefaults { ProbeDefaults.page(requiredQuery) } else null
                            service.page(requiredQuery, page ?: defaults!!.arg(1), pageSize ?: defaults!!.arg(2))
                        } ?: return@probe
                        respondJson(result)
                    }
                }
                get("/probe/list") {
                    probe {
                        val ids = queryList("ids", RestConvert.int)
                        val modes = querySet("modes", RestConvert.enum(Mode.entries.toTypedArray()))
                        respondJson(
                            mapOf(
                                "ids" to (ids?.map { it.toString() } ?: listOf("none")),
                                "modes" to (modes?.map { it.name } ?: listOf("none")),
                            ),
                        )
                    }
                }
                get("/probe/mode") {
                    probe {
                        val mode = queryParam("mode", RestConvert.enum(Mode.entries.toTypedArray()))
                        respondJson(mode?.name)
                    }
                }
                get("/probe/json") {
                    probe {
                        val probe = queryJson<Probe>("probe")
                        respondJson(probe)
                    }
                }
                get("/probe/secret") {
                    probe {
                        val result = restInvoke { service.secret() } ?: return@probe
                        respondJson(result)
                    }
                }
                get("/probe/explode") {
                    probe {
                        val result = restInvoke { service.explode() } ?: return@probe
                        respondJson(result)
                    }
                }
                post("/probe/touch") {
                    probe {
                        restInvoke { service.touch() } ?: return@probe
                        respondOk()
                    }
                }
                get("/probe/watch") {
                    probe {
                        val result = restInvoke { service.watch() } ?: return@probe
                        respondSse(result)
                    }
                }
                post("/probe/pair") {
                    probe {
                        val first = receiveJsonBody<Probe>("first")
                        val second = receiveJsonBody<Probe>("second")
                        val third = receiveJsonBodyOrNull<Int>("third")
                        respondJson(listOf(first, second.copy(count = third ?: -1)))
                    }
                }
                val streamHandler: suspend RoutingContext.() -> Unit = {
                    probe {
                        val probeId = pathParam("probeId", RestConvert.uuid)
                        val offset = queryParam("offset", RestConvert.long)
                        if (respondFile("stream", listOf(probeId, offset))) return@probe
                        val result = restInvoke {
                            val defaults = if (offset == null) captureDefaults { ProbeDefaults.stream(probeId) } else null
                            service.stream(probeId, offset ?: defaults!!.arg(1))
                        } ?: return@probe
                        respondBytesFlow(result)
                    }
                }
                head("/probe/stream/{probeId}", streamHandler)
                get("/probe/stream/{probeId}", streamHandler)
                post("/probe/fields") {
                    probe {
                        val name = receiveJsonField<String>("name")
                        val count = receiveJsonField<Int>("count")
                        val extra = receiveJsonFieldOrNull<Probe>("extra")
                        respondJson(Probe("$name/$count", extra?.count ?: -1))
                    }
                }
                get("/probe/png") {
                    probe { respondBytes(pngBytes) }
                }
                get("/probe/octets") {
                    probe { respondBytes(byteArrayOf(1, 2, 3)) }
                }
            }
        }
    }

    private suspend fun HttpResponse.text() = bodyAsText()

    @Test
    fun `path parameter is converted and json is returned`() = testApplication {
        setUpProbeApplication()
        val response = client.get("/probe/find/${FakeProbeService.KNOWN}")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        assertEquals("""{"name":"known","count":7}""", response.text())
    }

    @Test
    fun `invalid path parameter is a 400 with the conversion message`() = testApplication {
        setUpProbeApplication()
        val response = client.get("/probe/find/not-a-uuid")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("Invalid UUID value: not-a-uuid", response.text())
    }

    @Test
    fun `null result is a 404`() = testApplication {
        setUpProbeApplication()
        assertEquals(HttpStatusCode.NotFound, client.get("/probe/find/${UUID.randomUUID()}").status)
    }

    @Test
    fun `missing required parameter is a 400`() = testApplication {
        setUpProbeApplication()
        val response = client.get("/probe/page")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("Missing required parameter 'query'", response.text())
    }

    @Test
    fun `omitted optional parameters take the compiler defaults`() = testApplication {
        setUpProbeApplication()
        assertEquals("\"q/3/50\"", client.get("/probe/page?query=q").text())
        assertEquals("\"q/3/9\"", client.get("/probe/page?query=q&pageSize=9").text())
        assertEquals("\"q/3/50\"", client.get("/probe/page?query=q&page=%20").text())
    }

    @Test
    fun `invalid query parameter is a 400`() = testApplication {
        setUpProbeApplication()
        val response = client.get("/probe/page?query=q&page=abc")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("Invalid Int value: abc", response.text())
    }

    @Test
    fun `lists are flattened and blanks dropped`() = testApplication {
        setUpProbeApplication()
        val response = client.get("/probe/list?ids=1,2,%203&ids=4,,&modes=fast,SLOW,fast")
        assertEquals("""{"ids":["1","2","3","4"],"modes":["FAST","SLOW"]}""", response.text())
        assertEquals("""{"ids":["none"],"modes":["none"]}""", client.get("/probe/list").text())
    }

    @Test
    fun `unknown enum is a 400 and blank is absent`() = testApplication {
        setUpProbeApplication()
        val response = client.get("/probe/mode?mode=nope")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("Invalid Enum value: nope", response.text())
        assertEquals("\"SLOW\"", client.get("/probe/mode?mode=slow").text())
        assertEquals("null", client.get("/probe/mode?mode=").text())
    }

    @Test
    fun `json query parameter decodes or fails with 400`() = testApplication {
        setUpProbeApplication()
        assertEquals("""{"name":"x","count":2}""", client.get("/probe/json?probe=%7B%22name%22%3A%22x%22%2C%22count%22%3A2%7D").text())
        assertEquals("null", client.get("/probe/json").text())
        val bad = client.get("/probe/json?probe=%7Bnope")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue(bad.text().startsWith("Invalid value for parameter probe: "))
    }

    @Test
    fun `unauthorized is a 403 and other failures are a 500`() = testApplication {
        setUpProbeApplication()
        val forbidden = client.get("/probe/secret")
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertEquals("no capability", forbidden.text())
        val failed = client.get("/probe/explode")
        assertEquals(HttpStatusCode.InternalServerError, failed.status)
        assertEquals("kaboom", failed.text())
    }

    @Test
    fun `unit result is a 200`() = testApplication {
        setUpProbeApplication()
        assertEquals(HttpStatusCode.OK, client.post("/probe/touch").status)
        assertEquals(1, fake.touched)
    }

    @Test
    fun `sse frames every non-null item`() = testApplication {
        setUpProbeApplication()
        val response = client.get("/probe/watch")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-cache", response.headers[HttpHeaders.CacheControl])
        assertEquals(ContentType.Text.EventStream, response.contentType()?.withoutParameters())
        assertEquals("data: {\"name\":\"a\",\"count\":1}\r\n\r\ndata: {\"name\":\"b\",\"count\":2}\r\n\r\n", response.text())
    }

    @Test
    fun `body is read once and serves every body parameter`() = testApplication {
        setUpProbeApplication()
        val response = client.post("/probe/pair") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"same","count":5}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""[{"name":"same","count":5},{"name":"same","count":-1}]""", response.text())
    }

    @Test
    fun `invalid body is a 400`() = testApplication {
        setUpProbeApplication()
        val response = client.post("/probe/pair") {
            contentType(ContentType.Application.Json)
            setBody("not json")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.text().startsWith("Invalid value for parameter first: "))
    }

    @Test
    fun `file provider serves head and get`() = testApplication {
        setUpProbeApplication()
        val head = client.head("/probe/stream/${UUID.randomUUID()}")
        assertEquals(HttpStatusCode.OK, head.status)
        assertEquals("bytes", head.headers[HttpHeaders.AcceptRanges])
        assertEquals("300", head.headers[HttpHeaders.ContentLength])
        assertTrue(head.readRawBytes().isEmpty())

        val get = client.get("/probe/stream/${UUID.randomUUID()}?offset=0")
        assertEquals(HttpStatusCode.OK, get.status)
        assertEquals("bytes", get.headers[HttpHeaders.AcceptRanges])
        assertEquals("inline; filename=probe.bin", get.headers[HttpHeaders.ContentDisposition])
        assertArrayEquals(fileBytes, get.readRawBytes())
    }

    @Test
    fun `missing file falls through to the byte flow`() = testApplication {
        fake = FakeProbeService(null)
        setUpProbeApplication()
        val response = client.get("/probe/stream/${UUID.randomUUID()}")
        assertEquals(HttpStatusCode.OK, response.status)
        assertArrayEquals("fallback".toByteArray(), response.readRawBytes())
    }

    @Test
    fun `body fields are decoded from one object`() = testApplication {
        setUpProbeApplication()
        val response = client.post("/probe/fields") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"a","count":2,"extra":{"name":"e","count":9}}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"name":"a/2","count":9}""", response.text())
    }

    @Test
    fun `missing body field is a 400`() = testApplication {
        setUpProbeApplication()
        val response = client.post("/probe/fields") {
            contentType(ContentType.Application.Json)
            setBody("""{"count":2}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("Missing body field 'name'", response.text())
    }

    @Test
    fun `absent optional body field is null`() = testApplication {
        setUpProbeApplication()
        val response = client.post("/probe/fields") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"a","count":2}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"name":"a/2","count":-1}""", response.text())
        val explicitNull = client.post("/probe/fields") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"a","count":2,"extra":null}""")
        }
        assertEquals("""{"name":"a/2","count":-1}""", explicitNull.text())
    }

    @Test
    fun `body that is not an object is a 400`() = testApplication {
        setUpProbeApplication()
        val response = client.post("/probe/fields") {
            contentType(ContentType.Application.Json)
            setBody("""[{"name":"a","count":2}]""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.text().startsWith("Request body must be a JSON object"))
    }

    @Test
    fun `sniffed bytes decide the content type`() = testApplication {
        setUpProbeApplication()
        val response = client.get("/probe/png")
        assertEquals(ContentType.Image.PNG, response.contentType())
        assertEquals("inline", response.headers[HttpHeaders.ContentDisposition])
        assertArrayEquals(pngBytes, response.readRawBytes())
    }

    @Test
    fun `unknown bytes fall back to octet stream without a disposition`() = testApplication {
        setUpProbeApplication()
        val response = client.get("/probe/octets")
        assertEquals(ContentType.Application.OctetStream, response.contentType())
        assertNull(response.headers[HttpHeaders.ContentDisposition])
        assertArrayEquals(byteArrayOf(1, 2, 3), response.readRawBytes())
    }

    @Test
    fun `captureDefaults returns the compiler-filled defaults`() = runTest {
        val captured = captureDefaults { ProbeDefaults.page("q") }
        assertEquals("q", captured.arg<String>(0))
        assertEquals(3, captured.arg<Int>(1))
        assertEquals(50, captured.arg<Int>(2))
        val stream = captureDefaults { ProbeDefaults.stream(FakeProbeService.KNOWN) }
        assertEquals(0L, stream.arg<Long>(1))
    }
}
