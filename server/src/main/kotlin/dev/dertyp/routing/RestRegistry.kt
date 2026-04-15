package dev.dertyp.routing

import dev.dertyp.IIndexer
import dev.dertyp.RpcIndexer
import dev.dertyp.StreamInfo
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.getUser
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.rpc.annotations.RestDelete
import dev.dertyp.rpc.annotations.RestFileResponse
import dev.dertyp.rpc.annotations.RestGet
import dev.dertyp.rpc.annotations.RestPost
import dev.dertyp.rpc.annotations.RestPublic
import dev.dertyp.rpc.annotations.RestPut
import dev.dertyp.rpc.annotations.RpcDoc
import dev.dertyp.rpc.annotations.RpcParamDoc
import dev.dertyp.services.AlbumRpcService
import dev.dertyp.services.ArtistRpcService
import dev.dertyp.services.CustomAudioRpcService
import dev.dertyp.services.DbManagementService
import dev.dertyp.services.FavSyncRpcService
import dev.dertyp.services.IAlbumService
import dev.dertyp.services.IArtistService
import dev.dertyp.services.IAuthService
import dev.dertyp.services.IBackupService
import dev.dertyp.services.ICustomAudioService
import dev.dertyp.services.IDbManagementService
import dev.dertyp.services.IFavSyncService
import dev.dertyp.services.IImageService
import dev.dertyp.services.ILyricsSearch
import dev.dertyp.services.ILyricsService
import dev.dertyp.services.IMirrorService
import dev.dertyp.services.IPlaybackService
import dev.dertyp.services.IPlaylistService
import dev.dertyp.services.IReleaseService
import dev.dertyp.services.IRemoteMirrorService
import dev.dertyp.services.IScheduledTaskLogService
import dev.dertyp.services.IServerStatsService
import dev.dertyp.services.ISessionService
import dev.dertyp.services.ISongService
import dev.dertyp.services.IUserPlaylistBackupService
import dev.dertyp.services.IUserPlaylistService
import dev.dertyp.services.IUserService
import dev.dertyp.services.ImageService
import dev.dertyp.services.LyricsSearch
import dev.dertyp.services.LyricsService
import dev.dertyp.services.MirrorRpcService
import dev.dertyp.services.PlaylistService
import dev.dertyp.services.RemoteMirrorRpcService
import dev.dertyp.services.RpcAuthService
import dev.dertyp.services.RpcBackupService
import dev.dertyp.services.RpcPlaybackService
import dev.dertyp.services.RpcReleaseService
import dev.dertyp.services.RpcScheduledTaskLogService
import dev.dertyp.services.RpcSessionService
import dev.dertyp.services.RpcUserPlaylistBackupService
import dev.dertyp.services.RpcUserService
import dev.dertyp.services.ServerStatsService
import dev.dertyp.services.SongRpcService
import dev.dertyp.services.UserPlaylistService
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.IMusicBrainzService
import dev.dertyp.services.metadata.MetadataDispatcherService
import dev.dertyp.services.tdn.DownloadRpcService
import dev.dertyp.services.tdn.IDownloadService
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.head
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.CacheControl
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.route
import io.ktor.server.sse.SSEServerContent
import io.ktor.sse.ServerSentEvent
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.serializer
import org.koin.core.Koin
import java.time.Instant
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.starProjectedType

interface RestFileProvider {
    suspend fun getFile(methodName: String, args: List<Any?>): StreamInfo?
}

private class NoOutputWithContentLength(
    override val contentType: ContentType,
    override val status: HttpStatusCode? = null,
    override val contentLength: Long? = null
) : OutgoingContent.NoContent()

fun Route.registerRestService(
    serviceInterface: KClass<*>,
    authenticated: Boolean = false,
    serviceFactory: suspend RoutingContext.() -> Any
) {
    val serviceName = serviceInterface.simpleName?.removePrefix("I")?.removeSuffix("Service")?.replaceFirstChar { it.lowercase() } ?: ""
    
    route("/$serviceName") {
        if (serviceInterface.declaredMemberFunctions.any { it.findAnnotation<RestFileResponse>() != null }) {
            install(PartialContent) {
                maxRangeCount = 10
            }
        }

        serviceInterface.declaredMemberFunctions
            .filter { it.isSuspend || it.returnType.isFlow() }
            .forEach { func ->
                val (method, name) = func.getRestMethodAndName()
                val methodName = name.replaceFirstChar { it.lowercase() }
                val typeParam = func.parameters.find { it.kind == KParameter.Kind.VALUE && it.name == "type" && isPrimitive(it.type) }
                val idParam = func.parameters.find { it.kind == KParameter.Kind.VALUE && it.name != "type" && (it.name == "id" || it.name?.endsWith("Id") == true) && isPrimitive(it.type) }

                var finalPath = methodName
                if (typeParam != null) finalPath = "{type}/$finalPath"
                if (idParam != null) finalPath = "$finalPath/{${idParam.name}}"

                val rpcDoc = func.findAnnotation<RpcDoc>()
                val isFileResponse = func.findAnnotation<RestFileResponse>() != null

                val handler: suspend RoutingContext.() -> Unit = handler@{
                    if (authenticated && func.findAnnotation<RestPublic>() == null) {
                        val user = call.getUser()
                        if (user == null) {
                            call.respond(HttpStatusCode.Unauthorized)
                            return@handler
                        }
                    }

                    val service = serviceFactory()
                    val argMap = mutableMapOf<KParameter, Any?>()
                    val instanceParam = func.parameters.first { it.kind == KParameter.Kind.INSTANCE }
                    argMap[instanceParam] = service

                    func.parameters.filter { it.kind == KParameter.Kind.VALUE }.forEach { param ->
                        val value = try {
                            if (param.name == "type" && call.parameters["type"] != null) {
                                convertValue(call.parameters["type"], param.type)
                            } else if (idParam != null && param.name == idParam.name && call.parameters[idParam.name!!] != null) {
                                convertValue(call.parameters[idParam.name!!], param.type)
                            } else if (method == "GET" || isPrimitive(param.type)) {
                                val classifier = param.type.classifier
                                if (classifier == List::class || classifier == Collection::class || classifier == Iterable::class || classifier == Set::class) {
                                    val values = call.request.queryParameters.getAll(param.name!!)
                                    val itemType = param.type.arguments.firstOrNull()?.type ?: String::class.starProjectedType
                                    values?.map { convertValue(it, itemType) } ?: if (param.isOptional) null else emptyList<Any>()
                                } else {
                                    val rawValue = call.request.queryParameters[param.name!!]
                                    convertValue(rawValue, param.type)
                                }
                            } else {
                                @Suppress("UNCHECKED_CAST")
                                call.receive(param.type.classifier as KClass<Any>)
                            }
                        } catch (e: Exception) {
                            if (param.isOptional && call.request.queryParameters[param.name!!] == null) null
                            else throw IllegalArgumentException("Invalid value for parameter ${param.name}: ${e.message}")
                        }

                        if (value != null || param.type.isMarkedNullable) {
                            argMap[param] = value
                        }
                    }

                    if (isFileResponse && service is RestFileProvider) {
                        val methodParams = func.parameters.filter { it.kind == KParameter.Kind.VALUE }
                        val fileArgs = methodParams.map { argMap[it] ?: if (it.type.isMarkedNullable) null else throw IllegalArgumentException("Missing required file provider parameter: ${it.name}") }
                        val streamInfo = service.getFile(func.name, fileArgs)
                        if (streamInfo != null) {
                            call.response.header(HttpHeaders.AcceptRanges, "bytes")
                            if (call.request.local.method == HttpMethod.Head) {
                                call.respond(
                                    NoOutputWithContentLength(
                                        contentType = streamInfo.contentType,
                                        status = HttpStatusCode.OK,
                                        contentLength = streamInfo.contentLength
                                    )
                                )
                            } else {
                                call.response.header(
                                    HttpHeaders.ContentDisposition,
                                    ContentDisposition.Inline.withParameter(
                                        ContentDisposition.Parameters.FileName,
                                        streamInfo.fileName
                                    ).toString()
                                )
                                call.respondFile(streamInfo.file)
                            }
                            return@handler
                        }
                    }
                    
                    val result = try {
                        if (func.isSuspend) {
                            func.callSuspendBy(argMap)
                        } else {
                            func.callBy(argMap)
                        }
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid arguments")
                        return@handler
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
                        return@handler
                    }
                    
                    when (result) {
                        null -> call.respond(HttpStatusCode.NotFound)
                        is ByteArray -> {
                            val contentType = if (func.name.contains("Image", ignoreCase = true)) {
                                ContentType.Image.JPEG
                            } else {
                                ContentType.Application.OctetStream
                            }
                            call.respondBytes(result, contentType)
                        }
                        is Flow<*> -> {
                            val typeArg = func.returnType.arguments.firstOrNull()?.type
                            if (typeArg?.classifier == ByteArray::class) {
                                call.respondBytesWriter {
                                    @Suppress("UNCHECKED_CAST")
                                    (result as Flow<ByteArray>).collect {
                                        writeFully(it)
                                    }
                                }
                            } else {
                                call.response.cacheControl(CacheControl.NoCache(null))
                                call.respond(SSEServerContent(call, handle = {
                                    result.collect { item ->
                                        if (item != null) {
                                            send(ServerSentEvent(data = ApplicationScope.json.encodeToString(serializer(typeArg!!), item)))
                                        }
                                    }
                                }))
                            }
                        }
                        else -> {
                            if (func.returnType.classifier == Unit::class) {
                                call.respond(HttpStatusCode.OK)
                            } else {
                                try {
                                    val json = ApplicationScope.json.encodeToString(serializer(func.returnType), result)
                                    call.respondText(json, ContentType.Application.Json)
                                } catch (_: Throwable) {
                                    call.respond(result)
                                }
                            }
                        }
                    }
                }

                val openApiDoc: RouteConfig.() -> Unit = {
                    tags(serviceName)
                    summary = rpcDoc?.description
                    if (authenticated && func.findAnnotation<RestPublic>() == null) securitySchemeNames("UserAuth")
                    
                    request {
                        func.parameters.filter { it.kind == KParameter.Kind.VALUE }.forEach { param ->
                            val pDoc = param.findAnnotation<RpcParamDoc>()
                            val safeType = getSafeOpenApiType(param.type)
                            if (param.name == "type" && typeParam != null) {
                                pathParameter(param.name!!, safeType) { description = pDoc?.description }
                            } else if (idParam != null && param.name == idParam.name) {
                                pathParameter(param.name!!, safeType) { description = pDoc?.description }
                            } else if (method == "GET" || isPrimitive(param.type)) {
                                queryParameter(param.name!!, safeType) {
                                    description = pDoc?.description
                                }
                            } else {
                                body(safeType) { description = pDoc?.description }
                            }
                        }
                    }
                    
                    response {
                        HttpStatusCode.OK to {
                            description = "Successful"
                            val returnType = func.returnType
                            if (isFileResponse) {
                                body<ByteArray> {
                                    mediaTypes(ContentType.Application.OctetStream)
                                }
                            } else if (returnType.classifier != Unit::class) {
                                try {
                                    if (returnType.isFlow()) {
                                        val itemType = returnType.arguments.firstOrNull()?.type ?: Any::class.starProjectedType
                                        val safeItemType = getSafeOpenApiType(itemType)
                                        
                                        if (itemType.classifier == ByteArray::class) {
                                            body(safeItemType) {
                                                mediaTypes(ContentType.Application.OctetStream)
                                            }
                                        } else {
                                            body(safeItemType) {
                                                mediaTypes(ContentType.Text.EventStream)
                                            }
                                        }
                                    } else {
                                        body(getSafeOpenApiType(returnType))
                                    }
                                } catch (_: Throwable) {
                                    body<String>()
                                }
                            }
                        }
                        
                        rpcDoc?.errors?.forEach { errorMsg ->
                            HttpStatusCode.InternalServerError to {
                                description = errorMsg
                            }
                        }
                    }
                }

                when (method) {
                    "GET" -> {
                        if (isFileResponse) {
                            head(finalPath, openApiDoc, handler)
                        }
                        get(finalPath, openApiDoc, handler)
                    }
                    "POST" -> post(finalPath, openApiDoc, handler)
                    "PUT" -> put(finalPath, openApiDoc, handler)
                    "DELETE" -> delete(finalPath, openApiDoc, handler)
                }
            }
    }
}

private fun getSafeOpenApiType(type: KType): KType {
    val classifier = type.classifier as? KClass<*> ?: return type
    if (classifier == ByteArray::class) return type
    if (classifier == UUID::class) return String::class.starProjectedType

    if (type.isFlow()) {
        val itemType = type.arguments.firstOrNull()?.type ?: Any::class.starProjectedType
        val itemClassifier = itemType.classifier as? KClass<*>
        return itemClassifier?.starProjectedType ?: itemType
    }

    val isPaginated = classifier.simpleName == "PaginatedResponse"
    if (classifier.isSubclassOf(Iterable::class) || classifier.isSubclassOf(Map::class) || isPaginated) {
        val hasGenericParameter = type.arguments.any { arg ->
            arg.type?.classifier is KTypeParameter
        }
        if (!hasGenericParameter) {
            return type
        }
    }

    return if (type.arguments.isNotEmpty()) {
        classifier.starProjectedType
    } else {
        type
    }
}

private fun KType.isFlow(): Boolean {
    val classifier = this.classifier as? KClass<*> ?: return false
    return classifier.simpleName == "Flow" || classifier.qualifiedName?.contains("Flow") == true
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

fun Route.registerPublicRestServices(koin: Koin) {
    registerRestService(IServerStatsService::class) { koin.get<ServerStatsService>() }
    registerRestService(IAuthService::class) { RpcAuthService(call, koin.get(), koin.get(), koin.get()) }
    registerRestService(IImageService::class, authenticated = true) { koin.get<ImageService>() }
}

fun Route.registerAuthenticatedRestServices(koin: Koin) {
    registerRestService(IIndexer::class, authenticated = true) { RpcIndexer(koin.get()) }
    registerRestService(IUserService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcUserService(user, koin.get(), koin.get())
    }
    registerRestService(ISongService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        SongRpcService(songService = koin.get(), user = user)
    }
    registerRestService(IAlbumService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        AlbumRpcService(user, koin.get())
    }
    registerRestService(ILyricsSearch::class, authenticated = true) { koin.get<LyricsSearch>() }
    registerRestService(ILyricsService::class, authenticated = true) { koin.get<LyricsService>() }
    registerRestService(IArtistService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        ArtistRpcService(user, koin.get())
    }
    registerRestService(IFavSyncService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        FavSyncRpcService(user, koin.get())
    }
    registerRestService(IDownloadService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        DownloadRpcService(user, call, koin.get(), koin.get())
    }
    registerRestService(IPlaylistService::class, authenticated = true) { koin.get<PlaylistService>() }
    registerRestService(IUserPlaylistService::class, authenticated = true) { koin.get<UserPlaylistService>() }
    registerRestService(ISessionService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcSessionService(user, koin.get())
    }
    registerRestService(IPlaybackService::class, authenticated = true) { RpcPlaybackService(koin.get()) }
    registerRestService(ICustomAudioService::class, authenticated = true) { CustomAudioRpcService(koin.get()) }
    registerRestService(IDbManagementService::class, authenticated = true) { koin.get<DbManagementService>() }
    registerRestService(IBackupService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcBackupService(user, koin.get())
    }
    registerRestService(IUserPlaylistBackupService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcUserPlaylistBackupService(user, koin.get())
    }
    registerRestService(IMirrorService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        MirrorRpcService(user, koin.get())
    }
    registerRestService(IRemoteMirrorService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RemoteMirrorRpcService(user, koin.get())
    }
    registerRestService(IScheduledTaskLogService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcScheduledTaskLogService(user, koin.get())
    }
    registerRestService(IReleaseService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcReleaseService(user, koin.get())
    }
    registerRestService(IMusicBrainzService::class, authenticated = true) { koin.get<CachedMusicBrainzService>() }
    registerRestService(IMetadataService::class, authenticated = true) { koin.get<MetadataDispatcherService>() }
}

private fun isPrimitive(type: KType): Boolean {
    val classifier = type.classifier ?: return false
    if (classifier !is KClass<*>) return false
    return classifier == String::class ||
            classifier == Int::class ||
            classifier == Long::class ||
            classifier == Boolean::class ||
            classifier == Double::class ||
            classifier == Float::class ||
            classifier == UUID::class ||
            classifier.simpleName == "UUID" ||
            classifier.simpleName == "PlatformUUID" ||
            classifier.simpleName == "Instant" ||
            classifier.simpleName == "PlatformInstant" ||
            classifier.isSubclassOf(Enum::class)
}

private fun convertValue(value: String?, type: KType): Any? {
    if (value.isNullOrBlank()) return null
    val classifier = type.classifier as? KClass<*> ?: return value
    
    return when {
        classifier == String::class -> value
        classifier == Int::class -> value.toIntOrNull() ?: throw IllegalArgumentException("Invalid Int value: $value")
        classifier == Long::class -> value.toLongOrNull() ?: throw IllegalArgumentException("Invalid Long value: $value")
        classifier == Boolean::class -> value.toBooleanStrictOrNull() ?: throw IllegalArgumentException("Invalid Boolean value: $value")
        classifier == Double::class -> value.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid Double value: $value")
        classifier == Float::class -> value.toFloatOrNull() ?: throw IllegalArgumentException("Invalid Float value: $value")
        classifier == UUID::class || classifier.simpleName == "UUID" || classifier.simpleName == "PlatformUUID" -> value.toUUIDOrNull() ?: throw IllegalArgumentException("Invalid UUID value: $value")
        classifier.simpleName == "Instant" || classifier.simpleName == "PlatformInstant" -> {
            try { Instant.parse(value) } catch (_: Exception) { throw IllegalArgumentException("Invalid Instant value: $value") }
        }
        classifier.isSubclassOf(Enum::class) -> {
            @Suppress("UNCHECKED_CAST")
            val enumClass = classifier.java as Class<out Enum<*>>
            enumClass.enumConstants?.find { it.name.equals(value, ignoreCase = true) } ?: throw IllegalArgumentException("Invalid Enum value: $value")
        }
        else -> value
    }
}
