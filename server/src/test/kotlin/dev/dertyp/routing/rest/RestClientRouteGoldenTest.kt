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
import io.ktor.http.Url
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
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.valueParameters
import kotlin.reflect.full.withNullability

class RestClientRouteGoldenTest {
    private class GoldenParam(val source: Char, val name: String, val optional: Boolean)

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

        fun requiredQueryNames(): Set<String> =
            params.filter { it.source == 'q' && !it.optional }.mapTo(mutableSetOf()) { it.name }

        fun builderParams(): List<GoldenParam> = params.filter { it.source != 'b' }
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
                    GoldenParam(it.first(), it.substringAfter(':').removeSuffix("?"), it.endsWith("?"))
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

    private fun mappedPath(route: GoldenRoute, segments: List<String>, arguments: Map<String, Any?>): String {
        val pathNames = route.names('p')
        val expectedSegments = route.path.split('/').filter { it.isNotEmpty() }
        return segments.withIndex().joinToString("/", prefix = "/") { (index, segment) ->
            val name = expectedSegments.getOrNull(index)?.removeSurrounding("{", "}")
            if (name in pathNames && segment == pathText(arguments.getValue(name!!)!!)) "{$name}" else segment
        }
    }

    private fun builderProblems(route: GoldenRoute, builder: KFunction<*>): List<String> {
        val found = mutableListOf<String>()
        val expected = route.builderParams()
        val actual = builder.valueParameters
        if (actual.map { it.name } != expected.map { it.name }) {
            found += "the URL builder takes ${actual.map { it.name }}, expected ${expected.map { it.name }}"
            return found
        }
        actual.zip(expected).forEach { (parameter, golden) ->
            if (golden.optional && !parameter.type.isMarkedNullable) {
                found += "URL builder parameter ${golden.name} has a default and is not nullable"
            }
            if (parameter.isOptional != golden.optional) {
                found += "URL builder parameter ${golden.name} has default=${parameter.isOptional}, expected ${golden.optional}"
            }
        }
        return found
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

        val urlFunctionName = "${route.functionName}Url"
        val urlFunctions = type.memberFunctions.filter { it.name == urlFunctionName }
        val urlFunction = urlFunctions.singleOrNull()
        val urlProblems = mutableListOf<String>()
        if (urlFunction == null) {
            urlProblems += "the client has ${urlFunctions.size} functions named $urlFunctionName, expected one"
        } else {
            urlProblems += builderProblems(route, urlFunction)
        }
        var builtUrl: String? = null

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
            val outcome = try {
                val result = if (function.isSuspend) function.callSuspend(client, *values) else function.call(client, *values)
                if (result is Flow<*>) result.collect()
                null
            } catch (e: InvocationTargetException) {
                e.targetException
            } catch (e: Exception) {
                e
            }
            if (urlFunction != null && urlProblems.isEmpty()) {
                val requestsBefore = requests.size
                val tokenCallsBefore = tokenCalls.size
                try {
                    val builderArguments = urlFunction.valueParameters.map { arguments[it.name] }.toTypedArray()
                    builtUrl = urlFunction.callSuspend(client, *builderArguments) as String
                } catch (e: InvocationTargetException) {
                    urlProblems += "the URL builder threw ${e.targetException}"
                } catch (e: Exception) {
                    urlProblems += "the URL builder failed with $e"
                }
                if (requests.size != requestsBefore) urlProblems += "the URL builder sent a request"
                if (tokenCalls.size != tokenCallsBefore) urlProblems += "the URL builder asked for a token"
            }
            outcome
        }

        val found = mutableListOf<String>()
        found += urlProblems
        if (failure !is RestException || failure.status != HttpStatusCode.InternalServerError.value) {
            found += "expected RestException(500) from the error answer, got $failure"
        }
        if (requests.size != 1) return found + "expected exactly one request, got ${requests.size}"
        val request = requests.single()

        if (request.method.value != route.method) found += "method is ${request.method.value}"

        val mappedPath = mappedPath(route, request.url.segments, arguments)
        if (mappedPath != route.path) found += "path is $mappedPath (sent as ${request.url.encodedPath})"
        if (builtUrl != null && builtUrl != request.url.toString()) {
            found += "the URL builder returned $builtUrl, the call sent ${request.url}"
        }

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

    private suspend fun requiredOnlyProblems(route: GoldenRoute): List<String> {
        val type = clientClass(route.interfaceName) ?: return listOf("no generated client for ${route.interfaceName}")
        val urlFunction = type.memberFunctions.singleOrNull { it.name == "${route.functionName}Url" }
            ?: return listOf("the client has no single function named ${route.functionName}Url")
        val required = urlFunction.valueParameters.filterNot { it.isOptional }
        val arguments = required.associate { it.name!! to argument(it) }
        arguments.filterValues { it == null }.keys.takeIf { it.isNotEmpty() }?.let {
            return listOf("no non-null, non-empty argument could be generated for $it")
        }

        val built = HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) }).use { httpClient ->
            val config = RestClientConfig(httpClient = httpClient, baseUrl = { BASE_URL }, token = { TOKEN })
            val client = type.primaryConstructor!!.call(config)
            try {
                val callArguments = mapOf<KParameter, Any?>(urlFunction.parameters.first() to client) +
                    required.associateWith { arguments.getValue(it.name!!) }
                urlFunction.callSuspendBy(callArguments) as String
            } catch (e: InvocationTargetException) {
                return listOf("the URL builder threw ${e.targetException}")
            } catch (e: Exception) {
                return listOf("the URL builder failed with $e")
            }
        }

        val found = mutableListOf<String>()
        val url = Url(built)
        val queryNames = url.parameters.names()
        if (queryNames != route.requiredQueryNames()) found += "query parameters are $queryNames in $built"
        val mappedPath = mappedPath(route, url.segments, arguments)
        if (mappedPath != route.path) found += "path is $mappedPath (built as ${url.encodedPath})"
        return found
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

    @Test
    fun `every URL builder with only its required arguments names the required query parameters`() = runTest {
        val routes = goldenRoutes().filterNot { it.method == HEAD }
        assertTrue(routes.isNotEmpty(), "the golden lists no routes")
        val failures = routes.mapNotNull { route ->
            requiredOnlyProblems(route).takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "${route.line}\n") { "    $it" }
        }
        if (failures.isNotEmpty()) {
            fail<Unit>("${failures.size} of ${routes.size} URL builders differ from routes.golden:\n" + failures.joinToString("\n"))
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
