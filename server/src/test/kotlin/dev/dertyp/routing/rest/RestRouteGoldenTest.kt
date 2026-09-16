package dev.dertyp.routing.rest

import dev.dertyp.routing.*
import dev.dertyp.serializers.AppJson
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.AuthScheme
import io.github.smiley4.ktoropenapi.config.AuthType
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktoropenapi.route as documentedRoute
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.config.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koin.dsl.koinApplication

class RestRouteGoldenTest {
    private class Captured(val openApi: JsonObject, val tree: Set<Pair<String, String>>)

    private fun capture(): Captured {
        var openApi: JsonObject? = null
        var tree: Set<Pair<String, String>>? = null
        testApplication {
            environment { config = MapApplicationConfig() }
            application {
                install(OpenApi) {
                    schemas {
                        generator = SchemaGenerator.kotlinx(AppJson) {
                            overwrite(SchemaGenerator.TypeOverwrites.JavaUuid())
                            overwrite(SchemaGenerator.TypeOverwrites.KotlinUuid())
                        }
                    }
                    security {
                        securityScheme("UserAuth") {
                            type = AuthType.HTTP
                            scheme = AuthScheme.BEARER
                            bearerFormat = "JWT"
                        }
                    }
                }
                install(SSE)
                install(ContentNegotiation) { json(AppJson) }
                install(Authentication) {
                    provider("synara-auth") { authenticate { } }
                }
                routing {
                    route("api.json") { openApi() }
                    val koin = koinApplication { }.koin
                    registerPublicRestServices(koin)
                    authenticate("synara-auth") {
                        documentedRoute({ securitySchemeNames("UserAuth") }) {
                            registerAuthenticatedRestServices(koin)
                        }
                    }
                }
                tree = RestGoldenSupport.collectLeaves(plugin(RoutingRoot))
            }
            val response = client.get("/api.json")
            assertEquals(HttpStatusCode.OK, response.status)
            openApi = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
        return Captured(openApi!!, tree!!)
    }

    private fun registeredPrefixes(tree: Set<Pair<String, String>>): Set<String> =
        tree.mapTo(mutableSetOf()) { it.second.removePrefix("/").substringBefore('/') }

    private fun registeredRoutes(prefixes: Set<String>): List<RestRouteInfo> =
        GeneratedRestRoutes.manifest
            .filter { it.servicePrefix in prefixes }
            .sortedWith(compareBy({ it.servicePrefix }, { it.method }, { it.canonicalPath }, { it.functionName }))

    private fun renderRoutes(routes: List<RestRouteInfo>): String = routes.joinToString("\n", postfix = "\n") { it.render() }

    private fun openApiOperations(openApi: JsonObject): List<Triple<String, String, JsonObject>> =
        openApi["paths"]!!.jsonObject.entries
            .filterNot { it.key.startsWith("/api.json") }
            .flatMap { (path, methods) ->
                methods.jsonObject.entries.map { (method, operation) -> Triple(method.uppercase(), path, operation.jsonObject) }
            }
            .sortedWith(compareBy({ it.second }, { it.first }))

    private fun renderOpenApi(openApi: JsonObject): String = openApiOperations(openApi).joinToString("\n", postfix = "\n") { (method, path, operation) ->
        val operationId = operation["operationId"]?.jsonPrimitive?.content ?: "-"
        val summary = operation["summary"]?.jsonPrimitive?.content ?: "-"
        val tags = operation["tags"]?.jsonArray?.joinToString(",") { it.jsonPrimitive.content } ?: "-"
        val security = operation["security"]?.jsonArray
            ?.flatMap { it.jsonObject.keys }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(",")
            ?: "-"
        val params = operation["parameters"]?.jsonArray
            ?.joinToString(",") { "${it.jsonObject["name"]!!.jsonPrimitive.content}:${it.jsonObject["in"]!!.jsonPrimitive.content}" }
            ?.ifEmpty { "-" }
            ?: "-"
        val body = if (operation.containsKey("requestBody")) "yes" else "no"
        "$method $path $operationId | $summary | $tags | $security | $params | body:$body"
    }

    @Test
    fun `routes golden`() {
        val prefixes = registeredPrefixes(capture().tree)
        assertTrue(prefixes.isNotEmpty(), "no REST prefixes were registered")
        RestGoldenSupport.checkOrUpdate("routes.golden", renderRoutes(registeredRoutes(prefixes)))
    }

    @Test
    fun `openapi golden`() {
        RestGoldenSupport.checkOrUpdate("routes.openapi.golden", renderOpenApi(capture().openApi))
    }

    @Test
    fun `routing tree matches openapi and the manifest`() {
        val captured = capture()
        val fromOpenApi = openApiOperations(captured.openApi).map { it.first to it.second }.toSet()
        val treeWithoutTrailingSlash = captured.tree.map { (method, path) -> method to path.trimEnd('/') }.toSet()
        assertEquals(emptySet<Pair<String, String>>(), fromOpenApi - treeWithoutTrailingSlash, "documented but not routed")
        assertEquals(emptySet<Pair<String, String>>(), treeWithoutTrailingSlash - fromOpenApi, "routed but not documented")
        assertEquals(fromOpenApi.size, captured.tree.size)

        val fromManifest = registeredRoutes(registeredPrefixes(captured.tree))
            .map { it.method to it.canonicalPath.replace("//", "/") }
            .toSet()
        assertEquals(emptySet<Pair<String, String>>(), fromManifest - captured.tree, "in the manifest but not routed")
        assertEquals(emptySet<Pair<String, String>>(), captured.tree - fromManifest, "routed but not in the manifest")
    }
}
