package dev.dertyp.routing

import dev.dertyp.data.User
import dev.dertyp.mcp.ListenHistoryMcpServerFactory
import dev.dertyp.mcp.ListenHistoryQueryService
import dev.dertyp.mcp.McpListensPage
import dev.dertyp.plugins.ApiKeyScope
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.ApiKeyService
import dev.dertyp.services.ListeningStatsService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.util.UUID
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ContentNegotiationApplicationPlugin

class McpRoutesTest {

    private val apiKeyService = mockk<ApiKeyService>()
    private val query = mockk<ListenHistoryQueryService>()
    private val stats = mockk<ListeningStatsService>()
    private val factory = ListenHistoryMcpServerFactory(query, stats)
    private val user = User(id = UUID.randomUUID(), username = "tester", passwordHash = "x")

    private fun stubs() {
        coEvery { apiKeyService.resolveUser("good", ApiKeyScope.Mcp) } returns user
        coEvery { apiKeyService.resolveUser(neq("good"), any()) } returns null
        coEvery { query.listens(any(), any(), any(), any(), any()) } returns McpListensPage(emptyList(), null, false)
    }

    @AfterEach
    fun tearDown() {
        runCatching { stopKoin() }
    }

    private fun ApplicationTestBuilder.setUpMcpApplication() {
        stubs()
        environment { config = MapApplicationConfig() }
        application {
            install(Koin) {
                modules(
                    module {
                        single { apiKeyService }
                        single { factory }
                    },
                )
            }
            install(ContentNegotiationApplicationPlugin) { json(AppJson) }
            routing { mcpRouting() }
        }
    }

    private fun initializeBody(id: Int = 1) =
        """{"jsonrpc":"2.0","id":$id,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
            """"capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""

    private suspend fun ApplicationTestBuilder.mcpPost(body: String, key: String? = "good") =
        client.post("/mcp") {
            key?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            header("MCP-Protocol-Version", "2025-06-18")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun toolCallBody(id: Int, name: String, arguments: String) =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}"""

    @Test
    fun `post without authorization header is unauthorized and challenges`() = testApplication {
        setUpMcpApplication()

        val response = mcpPost(initializeBody(), key = null)

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.headers.contains(HttpHeaders.WWWAuthenticate))
    }

    @Test
    fun `post with unknown api key is unauthorized`() = testApplication {
        setUpMcpApplication()

        val response = mcpPost(initializeBody(), key = "wrong")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `initialize returns server info without a session id`() = testApplication {
        setUpMcpApplication()

        val response = mcpPost(initializeBody())

        assertEquals(HttpStatusCode.OK, response.status)
        val result = McpJson.parseToJsonElement(response.bodyAsText()).jsonObject["result"]!!.jsonObject
        assertEquals("synara", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertNull(response.headers["Mcp-Session-Id"])
    }

    @Test
    fun `notification only body is accepted without a response`() = testApplication {
        setUpMcpApplication()

        val response = mcpPost("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")

        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `tools list exposes the read only listen history tools encoded by McpJson`() = testApplication {
        setUpMcpApplication()

        val response = mcpPost("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        val tools = McpJson.parseToJsonElement(text).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(
            setOf(
                "search_library",
                "get_listens",
                "get_listening_summary",
                "get_top",
                "get_listen_timeline",
                "get_listening_stats",
                "get_now_playing",
            ),
            tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet(),
        )
        tools.forEach { tool ->
            val annotations = tool.jsonObject["annotations"]!!.jsonObject
            assertEquals(true, annotations["readOnlyHint"]!!.jsonPrimitive.content.toBoolean())
        }
        assertFalse(text.contains(":null"))
        assertFalse(text.contains("\"null\""))
    }

    @Test
    fun `tools call get_listens returns the stubbed page for the authenticated user`() = testApplication {
        setUpMcpApplication()

        val response = mcpPost(toolCallBody(3, "get_listens", """{"limit":5}"""))

        assertEquals(HttpStatusCode.OK, response.status)
        val result = McpJson.parseToJsonElement(response.bodyAsText()).jsonObject["result"]!!.jsonObject
        val payload = result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        val page = McpJson.parseToJsonElement(payload).jsonObject
        assertEquals(false, page["hasMore"]!!.jsonPrimitive.content.toBoolean())
        coVerify { query.listens(eq(user.id), any(), any(), any(), any()) }
    }

    @Test
    fun `tools call get_top without kind reports a tool error instead of failing the request`() = testApplication {
        setUpMcpApplication()

        val response = mcpPost(toolCallBody(4, "get_top", "{}"))

        assertEquals(HttpStatusCode.OK, response.status)
        val result = McpJson.parseToJsonElement(response.bodyAsText()).jsonObject["result"]!!.jsonObject
        assertEquals(true, result["isError"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `get on the mcp endpoint is not allowed`() = testApplication {
        setUpMcpApplication()

        val response = client.get("/mcp")

        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
        assertEquals("POST", response.headers[HttpHeaders.Allow])
    }
}
