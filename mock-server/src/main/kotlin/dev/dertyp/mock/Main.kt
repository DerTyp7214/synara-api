package dev.dertyp.mock

import dev.dertyp.rpc.annotations.RestDelete
import dev.dertyp.rpc.annotations.RestGet
import dev.dertyp.rpc.annotations.RestPost
import dev.dertyp.rpc.annotations.RestPut
import dev.dertyp.rpc.getAllServiceClasses
import dev.dertyp.rpc.initializeServiceRegistry
import dev.dertyp.serializers.AppCbor
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.IUiService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlin.reflect.KParameter
import kotlin.reflect.full.callSuspend
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.KrpcRoute
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8081
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

val explicitMocks: Map<KClass<*>, () -> Any> = mapOf(
    IUiService::class to { MockUiService() },
)

fun mockFor(serviceClass: KClass<*>): Any = explicitMocks[serviceClass]?.invoke() ?: MockGenerator.createMock(serviceClass)

val MockAuthPlugin = createRouteScopedPlugin("MockAuthPlugin") {
    onCall { call ->
        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            call.respond(HttpStatusCode.Unauthorized)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
fun Application.module() {
    install(ContentNegotiation) {
        json(AppJson)
    }

    install(StatusPages) {
        unhandled { call ->
            call.respond(HttpStatusCode.NotFound)
        }
    }

    install(Krpc) {
        serialization {
            cbor(AppCbor)
        }
    }
    
    initializeServiceRegistry()
    val allServices = getAllServiceClasses()
    val publicServices = listOf("IAuthService", "IServerStatsService")

    routing {
        rpc("/rpc") {
            val krpcRoute = this
            allServices.filter { it.simpleName in publicServices }.forEach { serviceClass ->
                try {
                    val method = KrpcRoute::class.memberFunctions.find { 
                        it.name == "registerService" && it.parameters.size == 3 
                    }
                    method?.call(krpcRoute, serviceClass, { mockFor(serviceClass) })
                } catch (_: Exception) {}
            }
        }

        rpc("/rpc/auth") {
            val krpcRoute = this
            allServices.filter { it.simpleName == "IAuthService" }.forEach { serviceClass ->
                try {
                    val method = KrpcRoute::class.memberFunctions.find { 
                        it.name == "registerService" && it.parameters.size == 3 
                    }
                    method?.call(krpcRoute, serviceClass, { mockFor(serviceClass) })
                } catch (_: Exception) {}
            }
        }

        route("/rpc/services") {
            install(MockAuthPlugin)
            rpc {
                val krpcRoute = this
                allServices.filter { it.simpleName !in publicServices }.forEach { serviceClass ->
                    try {
                        val method = KrpcRoute::class.memberFunctions.find { 
                            it.name == "registerService" && it.parameters.size == 3 
                        }
                        method?.call(krpcRoute, serviceClass, { mockFor(serviceClass) })
                    } catch (_: Exception) {}
                }
            }
        }

        allServices.forEach { serviceClass ->
            registerMockRestService(serviceClass, AppJson, serviceClass.simpleName in publicServices)
        }

        route("{...}") {
            handle {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}

fun Route.registerMockRestService(serviceInterface: KClass<*>, json: Json, isPublic: Boolean) {
    val serviceName = serviceInterface.simpleName?.removePrefix("I")?.removeSuffix("Service")?.replaceFirstChar { it.lowercase() } ?: ""

    route("/$serviceName") {
        if (!isPublic) {
            install(MockAuthPlugin)
        }

        serviceInterface.declaredMemberFunctions.forEach { func ->
            val (method, name) = func.getRestMethodAndName()
            val methodName = name.replaceFirstChar { it.lowercase() }

            val handler: suspend RoutingContext.() -> Unit = {
                val explicit = explicitMocks[serviceInterface]?.invoke()
                val dummy = if (explicit != null) {
                    val args = func.parameters.filter { it.kind == KParameter.Kind.VALUE }.map { param ->
                        val fromQuery = call.request.queryParameters[param.name ?: ""]
                        when {
                            fromQuery != null && param.type.classifier == String::class -> fromQuery
                            param.type.isMarkedNullable -> null
                            else -> MockGenerator.createDummy(param.type, param.name, 4)
                        }
                    }
                    val result = func.callSuspend(explicit, *args.toTypedArray())
                    if (result is Flow<*>) result.first() else result
                } else {
                    MockGenerator.createDummy(func.returnType, func.name)
                }
                if (dummy == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    try {
                        val responseType = if (func.returnType.classifier == Flow::class) func.returnType.arguments.first().type!! else func.returnType
                        val responseJson = json.encodeToString(serializer(responseType), dummy)
                        call.respondText(responseJson, ContentType.Application.Json)
                    } catch (_: Exception) {
                        call.respond(dummy)
                    }
                }
            }

            when (method) {
                "GET" -> get(methodName, handler)
                "POST" -> post(methodName, handler)
                "PUT" -> put(methodName, handler)
                "DELETE" -> delete(methodName, handler)
            }
        }
    }
}

private fun KFunction<*>.getRestMethodAndName(): Pair<String, String> {
    val getAttr = findAnnotation<RestGet>()
    val postAttr = findAnnotation<RestPost>()
    val putAttr = findAnnotation<RestPut>()
    val deleteAttr = findAnnotation<RestDelete>()

    return when {
        getAttr != null -> "GET" to this.name
        postAttr != null -> "POST" to this.name
        putAttr != null -> "PUT" to this.name
        deleteAttr != null -> "DELETE" to this.name
        name.startsWith("by") -> "GET" to name
        name.startsWith("all") -> "GET" to name
        name.startsWith("liked") -> "GET" to name
        name.startsWith("stream") -> "GET" to name
        name.startsWith("download") -> "GET" to name
        name.startsWith("get") -> "GET" to name.removePrefix("get")
        name.startsWith("list") -> "GET" to name.removePrefix("list")
        name.startsWith("find") -> "GET" to name.removePrefix("find")
        name.startsWith("fetch") -> "GET" to name.removePrefix("fetch")
        name.startsWith("search") -> "GET" to name.removePrefix("search")
        name.startsWith("ranked") -> "GET" to name.removePrefix("ranked")
        name.startsWith("exists") -> "GET" to name
        name.contains("Exists") -> "GET" to name
        name.startsWith("add") -> "POST" to name
        name.startsWith("post") -> "POST" to name.removePrefix("post")
        name.startsWith("create") -> "POST" to name.removePrefix("create")
        name.startsWith("put") -> "PUT" to name.removePrefix("put")
        name.startsWith("set") -> "PUT" to name.removePrefix("set")
        name.startsWith("update") -> "PUT" to name.removePrefix("update")
        name.startsWith("delete") -> "DELETE" to name.removePrefix("delete")
        name.startsWith("remove") -> "DELETE" to name.removePrefix("remove")
        else -> "POST" to name
    }
}
