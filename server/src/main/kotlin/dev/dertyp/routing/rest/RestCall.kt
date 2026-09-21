package dev.dertyp.routing.rest

import dev.dertyp.core.UnauthorizedException
import dev.dertyp.core.clientInfo
import dev.dertyp.core.getUser
import dev.dertyp.core.sniffMediaType
import dev.dertyp.serializers.AppJson
import dev.dertyp.utils.ResponseShaper
import dev.dertyp.utils.unwrapProxyTarget
import dev.dertyp.utils.withClientCompat
import io.ktor.http.CacheControl
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.sse.SSEServerContent
import io.ktor.sse.ServerSentEvent
import io.ktor.util.AttributeKey
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.UndeclaredThrowableException

private val RestBodyTextKey = AttributeKey<String>("RestBodyText")
private val RestBodyObjectKey = AttributeKey<JsonObject>("RestBodyObject")
private const val BodyMustBeObject = "Request body must be a JSON object with the fields of the request"

class RestCall<S : Any>(
    val call: ApplicationCall,
    val service: S,
    private val fileProvider: RestFileProvider?,
) {
    fun <T : Any> pathParam(name: String, convert: RestConverter<T>): T {
        val raw = call.parameters[name] ?: throw RestBindingException("Missing path parameter $name")
        return convert.convert(raw)
    }

    fun <T : Any> queryParam(name: String, convert: RestConverter<T>): T? {
        val raw = call.request.queryParameters[name]
        if (raw.isNullOrBlank()) return null
        return convert.convert(raw)
    }

    fun <T : Any> queryList(name: String, convert: RestConverter<T>): List<T>? {
        val values = call.request.queryParameters.getAll(name) ?: return null
        return values
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map(convert::convert)
    }

    fun <T : Any> querySet(name: String, convert: RestConverter<T>): Set<T>? = queryList(name, convert)?.toSet()

    suspend inline fun <reified T> queryJson(name: String): T? {
        val raw = call.request.queryParameters[name]
        if (raw.isNullOrBlank()) return null
        return try {
            AppJson.decodeFromString(AppJson.serializersModule.serializer<T>(), raw)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RestBindingException("Invalid value for parameter $name: ${e.message}", e)
        }
    }

    suspend fun restBodyText(): String {
        call.attributes.getOrNull(RestBodyTextKey)?.let { return it }
        val text = call.receiveText()
        call.attributes.put(RestBodyTextKey, text)
        return text
    }

    suspend inline fun <reified T> receiveJsonBody(name: String): T {
        val text = restBodyText()
        return try {
            AppJson.decodeFromString(AppJson.serializersModule.serializer<T>(), text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RestBindingException("Invalid value for parameter $name: ${e.message}", e)
        }
    }

    suspend inline fun <reified T> receiveJsonBodyOrNull(name: String): T? =
        try {
            receiveJsonBody<T>(name)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    @PublishedApi
    internal suspend fun restBodyObject(): JsonObject {
        call.attributes.getOrNull(RestBodyObjectKey)?.let { return it }
        val text = restBodyText()
        val parsed = if (text.isBlank()) {
            JsonObject(emptyMap())
        } else {
            val element = try {
                AppJson.parseToJsonElement(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw RestBindingException(BodyMustBeObject, e)
            }
            element as? JsonObject ?: throw RestBindingException(BodyMustBeObject)
        }
        call.attributes.put(RestBodyObjectKey, parsed)
        return parsed
    }

    suspend inline fun <reified T> receiveJsonField(name: String): T {
        val serializer = AppJson.serializersModule.serializer<T>()
        val element = restBodyObject()[name] ?: JsonNull
        if (element is JsonNull && !serializer.descriptor.isNullable) {
            throw RestBindingException("Missing body field '$name'")
        }
        return try {
            AppJson.decodeFromJsonElement(serializer, element)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RestBindingException("Invalid value for parameter $name: ${e.message}", e)
        }
    }

    suspend inline fun <reified T> receiveJsonFieldOrNull(name: String): T? {
        val element = restBodyObject()[name] ?: return null
        if (element is JsonNull) return null
        return try {
            AppJson.decodeFromJsonElement(AppJson.serializersModule.serializer<T>(), element)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    fun <T : Any> required(name: String, value: T?): T =
        value ?: throw IllegalArgumentException("Missing required parameter '$name'")

    suspend inline fun <R : Any> restInvoke(block: () -> R?): R? {
        val result = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return respondFailure(e)
        }
        if (result == null) {
            call.respond(HttpStatusCode.NotFound)
            return null
        }
        return result
    }

    @PublishedApi
    internal suspend fun respondFailure(e: Exception): Nothing? {
        val cause = when (e) {
            is UndeclaredThrowableException -> e.undeclaredThrowable ?: e
            is InvocationTargetException -> e.targetException ?: e
            else -> e
        }
        if (cause is CancellationException) throw cause
        if (cause !is Exception) throw cause
        if (call.response.isCommitted) throw cause
        when (cause) {
            is IllegalArgumentException -> call.respond(HttpStatusCode.BadRequest, cause.message ?: "Invalid arguments")
            is UnauthorizedException -> call.respond(HttpStatusCode.Forbidden, cause.message ?: "Unauthorized")
            else -> call.respond(HttpStatusCode.InternalServerError, cause.message ?: "Internal Server Error")
        }
        return null
    }

    suspend fun respondOk() {
        call.respond(HttpStatusCode.OK)
    }

    suspend inline fun <reified T> respondJson(value: T) {
        call.respondText(
            AppJson.encodeToString(AppJson.serializersModule.serializer<T>(), value),
            ContentType.Application.Json,
        )
    }

    suspend fun respondBytes(bytes: ByteArray) {
        val sniffed = sniffMediaType(bytes)?.takeIf {
            it.contentType.equals("image", ignoreCase = true) ||
                it.contentType.equals("video", ignoreCase = true)
        }
        if (sniffed == null) {
            call.respondBytes(bytes, ContentType.Application.OctetStream)
            return
        }
        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Inline.toString())
        call.respondBytes(bytes, sniffed)
    }

    suspend fun respondBytesFlow(flow: Flow<ByteArray>) {
        call.respondBytesWriter {
            flow.collect { writeFully(it) }
        }
    }

    suspend inline fun <reified T : Any> respondSse(flow: Flow<T?>) {
        val itemSerializer = AppJson.serializersModule.serializer<T>()
        call.response.cacheControl(CacheControl.NoCache(null))
        call.respond(SSEServerContent(call, handle = {
            flow.collect { item ->
                if (item != null) {
                    send(ServerSentEvent(data = AppJson.encodeToString(itemSerializer, item)))
                }
            }
        }))
    }

    suspend fun respondFile(functionName: String, args: List<Any?>): Boolean {
        val streamInfo = fileProvider?.getFile(functionName, args) ?: return false
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        if (call.request.local.method == HttpMethod.Head) {
            call.respond(
                NoOutputWithContentLength(
                    contentType = streamInfo.contentType,
                    status = HttpStatusCode.OK,
                    contentLength = streamInfo.contentLength,
                ),
            )
        } else {
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Inline.withParameter(
                    ContentDisposition.Parameters.FileName,
                    streamInfo.fileName,
                ).toString(),
            )
            call.respondFile(streamInfo.file)
        }
        return true
    }
}

suspend fun <S : Any> RoutingContext.restRoute(
    requireUser: Boolean,
    serviceInterface: Class<S>,
    factory: suspend RoutingContext.() -> S,
    body: suspend RestCall<S>.() -> Unit,
) {
    if (requireUser && call.getUser() == null) {
        call.respond(HttpStatusCode.Unauthorized)
        return
    }
    val raw = factory()
    val fileProvider = unwrapProxyTarget(raw) as? RestFileProvider
    val service = raw.withClientCompat(serviceInterface, ResponseShaper(call.clientInfo))
    try {
        RestCall(call, service, fileProvider).body()
    } catch (e: RestBindingException) {
        if (call.response.isCommitted) throw e
        call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid arguments")
    }
}
