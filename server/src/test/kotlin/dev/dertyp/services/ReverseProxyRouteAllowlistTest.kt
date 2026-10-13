package dev.dertyp.services

import dev.dertyp.config.ServerConfig
import dev.dertyp.routing.rest.GeneratedRestRoutes
import dev.dertyp.routing.rest.RestGoldenSupport
import dev.dertyp.routing.rest.RestResponseKind
import dev.dertyp.routing.rest.RestRouteInfo
import io.github.smiley4.ktoropenapi.OpenApi
import io.ktor.client.request.request
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.application.plugin
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReverseProxyRouteAllowlistTest {
    private val parameter = Regex("""\{[^}]*}""")
    private val methods = listOf(
        HttpMethod.Get,
        HttpMethod.Post,
        HttpMethod.Put,
        HttpMethod.Delete,
        HttpMethod.Patch,
        HttpMethod.Head,
    )

    private fun sample(template: String): String = template.replace(parameter, "sample-1")

    private fun shape(template: String): String = template.replace(parameter, "{}")

    private fun firstRoute(predicate: (RestRouteInfo) -> Boolean): RestRouteInfo =
        GeneratedRestRoutes.manifest.first(predicate)

    private fun authApplication(block: suspend ApplicationTestBuilder.(Set<Pair<String, String>>) -> Unit) {
        val jwtService = JwtService(
            ServerConfig(
                MapApplicationConfig(
                    "jwt.audience" to "test-audience",
                    "jwt.issuer" to "test-issuer",
                    "jwt.realm" to "test-realm",
                    "jwt.secret" to "test-secret",
                )
            ),
            mockk<UserService>(),
            mockk<RefreshTokenService>(),
            mockk<SessionService>(),
        )
        var leaves: Set<Pair<String, String>>? = null
        testApplication {
            environment { config = MapApplicationConfig() }
            application {
                install(OpenApi)
                jwtService.authenticate(this)
                routing { jwtService.authenticate(this) }
                leaves = RestGoldenSupport.collectLeaves(plugin(RoutingRoot))
            }
            startApplication()
            block(leaves!!)
        }
    }

    @Test
    fun `every manifest entry is allowed under its own method`() {
        assertTrue(GeneratedRestRoutes.manifest.isNotEmpty())
        val refused = GeneratedRestRoutes.manifest
            .filterNot { ReverseProxyRouteAllowlist.isAllowed(HttpMethod.parse(it.method), sample(it.canonicalPath)) }
            .map { "${it.method} ${it.canonicalPath}" }
        assertEquals(emptyList(), refused)
    }

    @Test
    fun `manifest templates only use literal and plain parameter segments`() {
        val plainParameter = Regex("""\{[A-Za-z0-9_]+}""")
        val other = (GeneratedRestRoutes.manifest.map { it.canonicalPath } + JwtService.HTTP_ROUTES.map { it.second })
            .filter { path ->
                path.split('/').any { ('{' in it || '}' in it || '*' in it) && !plainParameter.matches(it) }
            }
        assertEquals(emptyList(), other)
    }

    @Test
    fun `a manifest path is refused under a method it does not have`() {
        val methodsByShape = GeneratedRestRoutes.manifest
            .groupBy({ shape(it.canonicalPath) }, { it.method })
        var checked = 0
        val allowed = GeneratedRestRoutes.manifest.flatMap { route ->
            val own = methodsByShape.getValue(shape(route.canonicalPath))
            methods.filterNot { it.value in own }.mapNotNull { method ->
                checked++
                "${method.value} ${route.canonicalPath}".takeIf {
                    ReverseProxyRouteAllowlist.isAllowed(method, sample(route.canonicalPath))
                }
            }
        }
        assertTrue(checked > GeneratedRestRoutes.manifest.size)
        assertEquals(emptyList(), allowed)
    }

    @Test
    fun `OPTIONS is allowed on listed paths only`() {
        val refused = (GeneratedRestRoutes.manifest.map { it.canonicalPath } + JwtService.HTTP_ROUTES.map { it.second })
            .filterNot { ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Options, sample(it)) }
        assertEquals(emptyList(), refused)

        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Options, "/swagger"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Options, "/radio/stream"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Options, "/album/notARoute"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Options, "/sessions/abc/extra"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Options, "/"))
    }

    @Test
    fun `HEAD is allowed on FILE routes and refused on JSON routes`() {
        val fileRoutes = GeneratedRestRoutes.manifest.filter { it.response == RestResponseKind.FILE }
        assertTrue(fileRoutes.any { it.method == "HEAD" })
        fileRoutes.forEach {
            assertTrue(
                ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Head, sample(it.canonicalPath)),
                "HEAD ${it.canonicalPath}"
            )
        }

        val headShapes = GeneratedRestRoutes.manifest.filter { it.method == "HEAD" }.map { shape(it.canonicalPath) }
        val jsonRoutes = GeneratedRestRoutes.manifest.filter {
            it.method == "GET" && it.response == RestResponseKind.JSON && shape(it.canonicalPath) !in headShapes
        }
        assertTrue(jsonRoutes.isNotEmpty())
        jsonRoutes.forEach {
            assertFalse(
                ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Head, sample(it.canonicalPath)),
                "HEAD ${it.canonicalPath}"
            )
        }
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Head, "/userInfo"))
    }

    @Test
    fun `the hand-written auth routes are allowed under their methods`() {
        assertTrue(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Post, "/authenticate"))
        assertTrue(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Post, "/refresh-token"))
        assertTrue(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Post, "/register"))
        assertTrue(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "/userInfo"))
        assertTrue(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "/sessions"))
        assertTrue(
            ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Delete, "/sessions/3f0e8f5e-5d0b-4c0a-9d53-0d4c6a1f2b7e")
        )

        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "/authenticate"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "/refresh-token"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "/register"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Post, "/userInfo"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Delete, "/sessions"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "/sessions/abc"))
    }

    @Test
    fun `an encoded parameter segment matches exactly one parameter`() {
        val route = firstRoute { it.method == "GET" && it.canonicalPath == "/album/byId/{id}" }
        val base = route.canonicalPath.substringBefore("/{")

        assertTrue(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "$base/a%20b"))
        assertTrue(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "$base/a%2Fb"))
        assertTrue(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "$base/%C3%A4%3F%23"))
        assertTrue(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "/album/%62yId/abc"))
        assertTrue(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "//album//byId//abc"))

        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "$base/a/b"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "$base/abc/extra"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, base))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "/album"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "/Album/byId/abc"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "/album%2FbyId/abc"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "album/byId/abc"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "$base/%zz"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "$base/abc?x=1"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "$base/abc#x"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "$base/.."))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "$base/%2E%2E"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "$base/."))
    }

    @Test
    fun `a trailing slash is refused`() {
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "/album/byId/abc/"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "/album/byId/"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "/album/byIds/"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Post, "/authenticate/"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Options, "/authenticate/"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "/"))
        assertFalse(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, ""))
    }

    @Test
    fun `the excluded surfaces are refused`() {
        val excluded = listOf(
            HttpMethod.Get to "/swagger",
            HttpMethod.Get to "/swagger/index.html",
            HttpMethod.Get to "/api.json",
            HttpMethod.Get to "/static/app.js",
            HttpMethod.Get to "/static/css/app.css",
            HttpMethod.Post to "/mcp",
            HttpMethod.Get to "/mcp",
            HttpMethod.Get to "/rest/ping.view",
            HttpMethod.Get to "/rest/ping",
            HttpMethod.Post to "/rest/getAlbum.view",
            HttpMethod.Get to "/radio/stream",
            HttpMethod.Get to "/radio/3f0e8f5e-5d0b-4c0a-9d53-0d4c6a1f2b7e/stream",
            HttpMethod.Get to "/radio/channel/3f0e8f5e-5d0b-4c0a-9d53-0d4c6a1f2b7e/stream",
            HttpMethod.Get to "/admin/mirror",
            HttpMethod.Post to "/admin/mirror/start",
            HttpMethod.Get to "/admin/mirror/progress",
            HttpMethod.Get to "/admin/mirror/remote-image/abc",
            HttpMethod.Get to "/handshake",
            HttpMethod.Get to "/rpc",
            HttpMethod.Get to "/rpc/auth",
            HttpMethod.Get to "/rpc/services",
            HttpMethod.Get to "/proxy/abc",
            HttpMethod.Post to "/proxy/abc/def",
            HttpMethod.Get to "/health",
            HttpMethod.Get to "/ready",
        )
        val allowed = excluded.flatMap { (method, path) -> listOf(method to path, HttpMethod.Options to path) }
            .filter { (method, path) -> ReverseProxyRouteAllowlist.isAllowed(method, path) }
            .map { (method, path) -> "${method.value} $path" }
        assertEquals(emptyList(), allowed)

        assertTrue(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "/handshake/handshake"))
    }

    @Test
    fun `every route JwtService registers is listed and allowed`() = authApplication { leaves ->
        assertEquals(JwtService.HTTP_ROUTES.map { (method, path) -> method.value to path }.toSet(), leaves)
        leaves.forEach { (method, path) ->
            assertTrue(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.parse(method), sample(path)), "$method $path")
        }
    }

    @Test
    fun `the allowlist agrees with the routing of the server`() = authApplication {
        val unrouted = setOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed)
        val probes = listOf(
            HttpMethod.Get to "/userInfo",
            HttpMethod.Get to "/userInfo/",
            HttpMethod.Post to "/userInfo",
            HttpMethod.Head to "/userInfo",
            HttpMethod.Get to "/sessions",
            HttpMethod.Get to "/sessions/",
            HttpMethod.Delete to "/sessions",
            HttpMethod.Delete to "/sessions/",
            HttpMethod.Delete to "/sessions/abc",
            HttpMethod.Delete to "/sessions/abc/",
            HttpMethod.Delete to "/sessions/a%2Fb",
            HttpMethod.Delete to "/sessions/a%20b",
            HttpMethod.Delete to "/sessions/a/b",
            HttpMethod.Delete to "/Sessions/abc",
            HttpMethod.Delete to "/%73essions/abc",
            HttpMethod.Post to "/authenticate",
            HttpMethod.Post to "/authenticate/",
            HttpMethod.Get to "/authenticate",
            HttpMethod.Post to "/refresh-token",
            HttpMethod.Post to "/register",
            HttpMethod.Post to "/register/extra",
        )
        probes.forEach { (probeMethod, path) ->
            val status = client.request(path) { method = probeMethod }.status
            assertEquals(
                status !in unrouted,
                ReverseProxyRouteAllowlist.isAllowed(probeMethod, path),
                "${probeMethod.value} $path answered $status"
            )
        }
    }
}
