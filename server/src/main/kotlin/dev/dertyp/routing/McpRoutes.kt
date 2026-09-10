package dev.dertyp.routing

import dev.dertyp.core.apiKeyUser
import dev.dertyp.mcp.ListenHistoryMcpServerFactory
import dev.dertyp.plugins.ApiKeyScope
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import org.koin.ktor.ext.get

private const val API_KEY_NOTE =
    "Authenticate with an API key via the `apiKey` query parameter, the `X-API-Key` header, or `Authorization: Bearer <key>`. The key needs the `mcp` scope."

private object McpJsonResponseHook : Hook<Unit> {
    override fun install(pipeline: ApplicationCallPipeline, handler: Unit) {
        pipeline.sendPipeline.intercept(ApplicationSendPipeline.Before) { subject ->
            val json = when {
                subject is JSONRPCMessage -> McpJson.encodeToString(subject)
                subject is List<*> && subject.isNotEmpty() && subject.all { it is JSONRPCMessage } ->
                    McpJson.encodeToString(subject.filterIsInstance<JSONRPCMessage>())

                else -> return@intercept
            }
            proceedWith(TextContent(json, ContentType.Application.Json.withCharset(Charsets.UTF_8)))
        }
    }
}

private val McpJsonResponses = createRouteScopedPlugin("McpJsonResponses") {
    on(McpJsonResponseHook, Unit)
}

fun Route.mcpRouting() {
    route("/mcp") {
        install(McpJsonResponses)

        post({
            tags("MCP")
            summary = "Model Context Protocol endpoint (read-only listen history)"
            description = "Stateless Streamable HTTP transport for the Model Context Protocol. Every request carries a " +
                "complete JSON-RPC 2.0 message (or a batch of them) and is answered with a JSON response; no session id is " +
                "issued and no server-initiated stream is opened, so `initialize`, `tools/list` and `tools/call` can each be " +
                "sent as an independent POST. Send `Content-Type: application/json` and " +
                "`Accept: application/json, text/event-stream`. The exposed tools are read-only and scoped to the " +
                "authenticated user: search_library, get_listens, get_listening_summary, get_top, get_listen_timeline, " +
                "get_listening_stats and get_now_playing. $API_KEY_NOTE"
            securitySchemeNames("ApiKeyAuth")
            request {
                headerParameter<String>("Accept") {
                    description = "Must include both `application/json` and `text/event-stream`."
                    required = true
                }
                body<String> {
                    description = "A JSON-RPC 2.0 request, notification, or an array of them."
                    mediaTypes(ContentType.Application.Json)
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "The JSON-RPC response, or an array of responses for a batch."
                    body<String> { mediaTypes(ContentType.Application.Json) }
                }
                HttpStatusCode.Accepted to { description = "The body contained only notifications or responses, so there is nothing to return." }
                HttpStatusCode.Unauthorized to { description = "Missing or invalid API key, or the key lacks the `mcp` scope." }
                HttpStatusCode.NotAcceptable to { description = "The Accept header does not allow both application/json and text/event-stream." }
                HttpStatusCode.UnsupportedMediaType to { description = "The Content-Type is not application/json." }
            }
        }) {
            val factory = call.get<ListenHistoryMcpServerFactory>()
            val user = call.apiKeyUser(ApiKeyScope.Mcp)
            if (user == null) {
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"synara\"")
                return@post call.respondText(
                    McpJson.encodeToString(
                        JSONRPCError(id = null, error = RPCError(RPCError.ErrorCode.CONNECTION_CLOSED, "Unauthorized")),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
            }

            val transport = StreamableHttpServerTransport(
                StreamableHttpServerTransport.Configuration(enableJsonResponse = true),
            ).also { it.setSessionIdGenerator(null) }

            val server = factory.create(user)
            val session = server.createSession(transport)

            try {
                transport.handleRequest(null, call)
            } finally {
                session.close()
            }
        }

        get {
            call.response.header(HttpHeaders.Allow, "POST")
            call.respond(HttpStatusCode.MethodNotAllowed)
        }

        delete {
            call.response.header(HttpHeaders.Allow, "POST")
            call.respond(HttpStatusCode.MethodNotAllowed)
        }
    }
}
