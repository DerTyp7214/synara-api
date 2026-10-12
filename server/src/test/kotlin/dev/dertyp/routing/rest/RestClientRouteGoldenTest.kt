package dev.dertyp.routing.rest

import dev.dertyp.mock.MockGenerator
import dev.dertyp.rpc.rest.client.RestClientConfig
import dev.dertyp.rpc.rest.client.RestException
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.metadata.IMetadataService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.valueParameters
import kotlin.reflect.full.withNullability

class RestClientRouteGoldenTest {
    private class GoldenParam(val source: Char, val name: String)

    private class GoldenRoute(
        val line: String,
        val method: String,
        val path: String,
        val interfaceName: String,
        val functionName: String,
        val params: List<GoldenParam>,
        val public: Boolean,
    ) {
        fun names(source: Char): Set<String> = params.filter { it.source == source }.mapTo(mutableSetOf()) { it.name }
    }

    private val linePattern = Regex("""^(\S+) (\S+) (\w+)\.(\w+) \[(.*)] (\w+)( PUBLIC)?$""")

    private fun goldenRoutes(): List<GoldenRoute> {
        val file = Paths.get("src/test/resources/rest/routes.golden")
        assertTrue(Files.exists(file), "Golden tests expect the server module as working directory, missing $file")
        return Files.readAllLines(file).filter { it.isNotBlank() }.map { line ->
            val match = linePattern.matchEntire(line) ?: fail("unreadable golden line: $line")
            val (method, path, interfaceName, functionName, params) = match.destructured
            GoldenRoute(
                line = line,
                method = method,
                path = path,
                interfaceName = interfaceName,
                functionName = functionName,
                params = params.split(' ').filter { it.isNotEmpty() }.map {
                    GoldenParam(it.first(), it.substringAfter(':').removeSuffix("?"))
                },
                public = match.groupValues[7].isNotEmpty(),
            )
        }
    }

    private fun clientClass(interfaceName: String): KClass<*>? = try {
        Class.forName("${RestClientConfig::class.java.packageName}.Rest${interfaceName.removePrefix("I")}").kotlin
    } catch (_: ClassNotFoundException) {
        null
    }

    private fun isFilled(value: Any): Boolean = when (value) {
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }

    private fun dummy(type: KType, name: String?): Any? {
        val arguments = type.arguments.map { it.type }
        return when {
            type.classifier == Pair::class -> Pair(dummy(arguments[0]!!, name), dummy(arguments[1]!!, name))
            type.classifier == List::class && arguments.single()?.classifier == Pair::class ->
                List(2) { dummy(arguments.single()!!, name) }

            else -> MockGenerator.createDummy(type.withNullability(false), name)
        }
    }

    private fun argument(parameter: KParameter): Any? {
        repeat(ARGUMENT_ATTEMPTS) {
            val value = try {
                dummy(parameter.type, parameter.name)
            } catch (_: Exception) {
                null
            }
            if (value != null && isFilled(value)) return value
        }
        return null
    }

    private fun pathText(value: Any): String = when (value) {
        is IMetadataService.MetadataType -> value.value
        is Enum<*> -> value.name
        else -> value.toString()
    }

    private suspend fun problems(route: GoldenRoute): List<String> {
        val type = clientClass(route.interfaceName) ?: return listOf("no generated client for ${route.interfaceName}")
        val function = type.memberFunctions.singleOrNull { it.name == route.functionName }
            ?: return listOf("the client has no single function named ${route.functionName}")
        val arguments = function.valueParameters.associate { it.name!! to argument(it) }
        arguments.filterValues { it == null }.keys.takeIf { it.isNotEmpty() }?.let {
            return listOf("no non-null, non-empty argument could be generated for $it")
        }
        val goldenNames = route.params.mapTo(mutableSetOf()) { it.name }
        if (arguments.keys != goldenNames) return listOf("the client function takes ${arguments.keys}")

        val requests = mutableListOf<HttpRequestData>()
        val tokenCalls = mutableListOf<Boolean>()
        val failure = HttpClient(
            MockEngine { request ->
                requests += request
                respondError(HttpStatusCode.InternalServerError)
            },
        ).use { httpClient ->
            val config = RestClientConfig(
                httpClient = httpClient,
                baseUrl = { BASE_URL },
                token = { forceRefresh ->
                    tokenCalls += forceRefresh
                    TOKEN
                },
            )
            val client = type.primaryConstructor!!.call(config)
            val values = arguments.values.toTypedArray()
            try {
                val result = if (function.isSuspend) function.callSuspend(client, *values) else function.call(client, *values)
                if (result is Flow<*>) result.collect()
                null
            } catch (e: InvocationTargetException) {
                e.targetException
            } catch (e: Exception) {
                e
            }
        }

        val found = mutableListOf<String>()
        if (failure !is RestException || failure.status != HttpStatusCode.InternalServerError.value) {
            found += "expected RestException(500) from the error answer, got $failure"
        }
        if (requests.size != 1) return found + "expected exactly one request, got ${requests.size}"
        val request = requests.single()

        if (request.method.value != route.method) found += "method is ${request.method.value}"

        val pathNames = route.names('p')
        val expectedSegments = route.path.split('/').filter { it.isNotEmpty() }
        val actualSegments = request.url.segments
        val mappedPath = actualSegments.withIndex().joinToString("/", prefix = "/") { (index, segment) ->
            val name = expectedSegments.getOrNull(index)?.removeSurrounding("{", "}")
            if (name in pathNames && segment == pathText(arguments.getValue(name!!)!!)) "{$name}" else segment
        }
        if (mappedPath != route.path) found += "path is $mappedPath (sent as ${request.url.encodedPath})"

        val queryNames = request.url.parameters.names()
        if (queryNames != route.names('q')) found += "query parameters are $queryNames"

        val bodyNames = route.names('b')
        val body = (request.body as? TextContent)?.text
        when {
            bodyNames.isEmpty() -> if (body != null) found += "unexpected body $body"
            body == null -> found += "no body was sent for $bodyNames"
            bodyNames.size > 1 -> {
                val keys = (AppJson.parseToJsonElement(body) as? JsonObject)?.keys
                if (keys != bodyNames) found += "body keys are $keys in $body"
            }
        }

        val authorization = request.headers[HttpHeaders.Authorization]
        if (route.public) {
            if (authorization != null) found += "a public route sent Authorization: $authorization"
            if (tokenCalls.isNotEmpty()) found += "a public route asked for a token"
        } else {
            if (authorization != "Bearer $TOKEN") found += "Authorization is $authorization"
            if (tokenCalls != listOf(false)) found += "token provider calls were $tokenCalls"
        }
        return found
    }

    @Test
    fun `every golden interface has a generated client`() {
        val missing = goldenRoutes().map { it.interfaceName }.distinct().filter { clientClass(it) == null }
        assertEquals(emptyList<String>(), missing, "interfaces without a generated REST client")
    }

    @Test
    fun `head lines have no client call and repeat a get line`() {
        val routes = goldenRoutes()
        val heads = routes.filter { it.method == HEAD }
        assertTrue(heads.isNotEmpty(), "the golden lists no HEAD line")
        val orphans = heads.filter { head ->
            routes.none {
                it.method == GET && it.path == head.path &&
                    it.interfaceName == head.interfaceName && it.functionName == head.functionName
            }
        }
        assertEquals(emptyList<String>(), orphans.map { it.line }, "HEAD lines without a GET line of the same function")
    }

    @Test
    fun `every client call sends the request of its golden line`() = runTest {
        val routes = goldenRoutes().filterNot { it.method == HEAD }
        assertTrue(routes.isNotEmpty(), "the golden lists no routes")
        val failures = routes.mapNotNull { route ->
            problems(route).takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "${route.line}\n") { "    $it" }
        }
        if (failures.isNotEmpty()) {
            fail<Unit>("${failures.size} of ${routes.size} client calls differ from routes.golden:\n" + failures.joinToString("\n"))
        }
    }

    private companion object {
        const val BASE_URL = "http://synara.test"
        const val TOKEN = "golden-token"
        const val HEAD = "HEAD"
        const val GET = "GET"
        const val ARGUMENT_ATTEMPTS = 20
    }
}
