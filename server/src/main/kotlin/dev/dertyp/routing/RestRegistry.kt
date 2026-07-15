package dev.dertyp.routing

import dev.dertyp.IIndexer
import dev.dertyp.RpcIndexer
import dev.dertyp.StreamInfo
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.UnauthorizedException
import dev.dertyp.core.getUser
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.rpc.annotations.*
import dev.dertyp.services.*
import dev.dertyp.services.import.IImportService
import dev.dertyp.services.import.ImportRpcService
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.IMusicBrainzService
import dev.dertyp.services.metadata.MetadataDispatcherService
import dev.dertyp.services.schedule.RpcScheduledTaskConfigurationService
import dev.dertyp.services.sync.RpcListenBrainzService
import dev.dertyp.utils.withAuthorization
import io.github.smiley4.ktoropenapi.*
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.serializer
import org.koin.core.Koin
import java.time.Instant
import java.util.*
import kotlin.reflect.*
import kotlin.reflect.full.*

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
                                    val resultValues = values?.flatMap { it.split(",") }?.map { convertValue(it.trim(), itemType) }
                                    if (classifier == Set::class) resultValues?.toSet() ?: if (param.isOptional) null else emptySet<Any>()
                                    else resultValues ?: if (param.isOptional) null else emptyList<Any>()
                                } else {
                                    val rawValue = call.request.queryParameters[param.name!!]
                                    convertValue(rawValue, param.type)
                                }
                            } else {
                                @Suppress("UNCHECKED_CAST")
                                val serializer = ApplicationScope.json.serializersModule.serializer(param.type)
                                val body = call.receiveText()
                                ApplicationScope.json.decodeFromString(serializer, body)
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
                    } catch (e: UnauthorizedException) {
                        call.respond(HttpStatusCode.Forbidden, e.message ?: "Unauthorized")
                        return@handler
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
                        return@handler
                    }
                    
                    when (result) {
                        null -> call.respond(HttpStatusCode.NotFound)
                        is ByteArray -> {
                            val contentType = when {
                                func.name.contains("Animated", ignoreCase = true) -> ContentType.Video.MP4
                                func.name.contains("Image", ignoreCase = true) -> ContentType.Image.JPEG
                                else -> ContentType.Application.OctetStream
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
                                            val safeType = getSafeOpenApiType(typeArg!!)
                                            val safeItem = transformToRestResponse(item)
                                            send(ServerSentEvent(data = ApplicationScope.json.encodeToString(serializer(safeType), safeItem)))
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
                                    val safeReturnType = getSafeOpenApiType(func.returnType)
                                    val safeResult = transformToRestResponse(result)
                                    val json = ApplicationScope.json.encodeToString(serializer(safeReturnType), safeResult)
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

private fun transformToRestResponse(value: Any?): Any? {
    if (value == null) return null
    if (value is IMetadataService.MetadataType) return value.value
    if (value is Iterable<*>) return value.map { transformToRestResponse(it) }
    if (value is Map<*, *>) return value.entries.associate { transformToRestResponse(it.key) to transformToRestResponse(it.value) }
    if (value is PaginatedResponse<*>) {
        return PaginatedResponse(
            data = value.data.map { transformToRestResponse(it) },
            page = value.page,
            total = value.total,
            pageSize = value.pageSize,
            hasNextPage = value.hasNextPage
        )
    }
    return value
}

private fun getSafeOpenApiType(type: KType): KType {
    val classifier = type.classifier as? KClass<*> ?: return type
    if (classifier == ByteArray::class) return type
    if (classifier == UUID::class || classifier.simpleName == "MetadataType") return String::class.starProjectedType

    if (type.isFlow()) {
        val itemType = type.arguments.firstOrNull()?.type ?: Any::class.starProjectedType
        return getSafeOpenApiType(itemType)
    }

    val isPaginated = classifier.simpleName == "PaginatedResponse"
    if (classifier.isSubclassOf(Iterable::class) || isPaginated) {
        val itemType = type.arguments.firstOrNull()?.type
        if (itemType != null) {
            val safeItemType = getSafeOpenApiType(itemType)
            if (safeItemType != itemType) {
                return if (isPaginated) {
                    classifier.createType(listOf(KTypeProjection.invariant(safeItemType)))
                } else {
                    List::class.createType(listOf(KTypeProjection.invariant(safeItemType)))
                }
            }
        }

        val hasGenericParameter = type.arguments.any { arg ->
            arg.type?.classifier is KTypeParameter
        }
        if (!hasGenericParameter) {
            return type
        }
    }

    if (classifier.isSubclassOf(Map::class)) {
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
        name.startsWith("import") -> "GET" to name
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
    registerRestService(IHandshakeService::class) { HandshakeService(call) }
    registerRestService(IImageService::class, authenticated = true) {
        ImageRpcService(call.getUser(), koin.get<ImageService>())
    }
    registerRestService(IAnimatedImageService::class, authenticated = true) {
        AnimatedImageRpcService(koin.get<AnimatedImageService>())
    }
}

fun Route.registerAuthenticatedRestServices(koin: Koin) {
    registerRestService(IIndexer::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcIndexer(koin.get(), user).withAuthorization<IIndexer>(user)
    }
    registerRestService(IUserService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcUserService(user, koin.get(), koin.get()).withAuthorization<IUserService>(user)
    }
    registerRestService(ISongService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        SongRpcService(songService = koin.get(), user = user).withAuthorization<ISongService>(user)
    }
    registerRestService(IAlbumService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        AlbumRpcService(user, koin.get()).withAuthorization<IAlbumService>(user)
    }
    registerRestService(ILyricsSearch::class, authenticated = true) {
        val user = call.getUser()
        koin.get<LyricsSearch>().withAuthorization<ILyricsSearch>(user)
    }
    registerRestService(ILyricsService::class, authenticated = true) {
        val user = call.getUser()
        koin.get<LyricsService>().withAuthorization<ILyricsService>(user)
    }
    registerRestService(IArtistService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        ArtistRpcService(user, koin.get()).withAuthorization<IArtistService>(user)
    }
    registerRestService(IAudioAnalysisService::class, authenticated = true) {
        val user = call.getUser()
        koin.get<AudioAnalysisService>().withAuthorization<IAudioAnalysisService>(user)
    }
    registerRestService(IDiscoveryService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        DiscoveryRpcService(user, koin.get()).withAuthorization<IDiscoveryService>(user)
    }
    registerRestService(IFavSyncService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        FavSyncRpcService(user, koin.get()).withAuthorization<IFavSyncService>(user)
    }
    registerRestService(IImportService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        ImportRpcService(user, call, koin.get(), koin.get()).withAuthorization<IImportService>(user)
    }
    registerRestService(IPlaylistService::class, authenticated = true) {
        val user = call.getUser()
        koin.get<PlaylistService>().withAuthorization<IPlaylistService>(user)
    }
    registerRestService(IUserPlaylistService::class, authenticated = true) {
        val user = call.getUser()
        koin.get<UserPlaylistService>().withAuthorization<IUserPlaylistService>(user)
    }
    registerRestService(ICollectionService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcCollectionService(user, koin.get()).withAuthorization<ICollectionService>(user)
    }
    registerRestService(ISessionService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcSessionService(user, koin.get()).withAuthorization<ISessionService>(user)
    }
    registerRestService(IPlaybackService::class, authenticated = true) {
        val user = call.getUser()
        RpcPlaybackService(koin.get()).withAuthorization<IPlaybackService>(user)
    }
    registerRestService(ICustomAudioService::class, authenticated = true) {
        val user = call.getUser()
        CustomAudioRpcService(koin.get()).withAuthorization<ICustomAudioService>(user)
    }
    registerRestService(IDbManagementService::class, authenticated = true) {
        val user = call.getUser()
        koin.get<DbManagementService>().withAuthorization<IDbManagementService>(user)
    }
    registerRestService(IBackupService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcBackupService(user, koin.get()).withAuthorization<IBackupService>(user)
    }
    registerRestService(IUserPlaylistBackupService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcUserPlaylistBackupService(user, koin.get()).withAuthorization<IUserPlaylistBackupService>(user)
    }
    registerRestService(IMirrorService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        MirrorRpcService(koin.get()).withAuthorization<IMirrorService>(user)
    }
    registerRestService(IRemoteMirrorService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RemoteMirrorRpcService(user, koin.get()).withAuthorization<IRemoteMirrorService>(user)
    }
    registerRestService(IScheduledTaskLogService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcScheduledTaskLogService(user, koin.get()).withAuthorization<IScheduledTaskLogService>(user)
    }
    registerRestService(IScheduledTaskConfigurationService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcScheduledTaskConfigurationService(koin.get(), koin.get()).withAuthorization<IScheduledTaskConfigurationService>(user)
    }
    registerRestService(IReleaseService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcReleaseService(user, koin.get()).withAuthorization<IReleaseService>(user)
    }
    registerRestService(IMusicBrainzService::class, authenticated = true) {
        val user = call.getUser()
        koin.get<CachedMusicBrainzService>().withAuthorization<IMusicBrainzService>(user)
    }
    registerRestService(IMetadataService::class, authenticated = true) {
        val user = call.getUser()
        koin.get<MetadataDispatcherService>().withAuthorization<IMetadataService>(user)
    }
    registerRestService(IListenBrainzService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcListenBrainzService(user, koin.get()).withAuthorization<IListenBrainzService>(user)
    }
    registerRestService(IScrobbleService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcScrobbleService(user, koin.get()).withAuthorization<IScrobbleService>(user)
    }
    registerRestService(IRecommendationService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcRecommendationService(user, koin.get()).withAuthorization<IRecommendationService>(user)
    }
    registerRestService(IRadioService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RadioRpcService(user, koin.get()).withAuthorization<IRadioService>(user)
    }
    registerRestService(IApiKeyService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcApiKeyService(user, koin.get()).withAuthorization<IApiKeyService>(user)
    }
    registerRestService(IRadioChannelService::class, authenticated = true) {
        val user = call.getUser() ?: throw IllegalArgumentException("No user found")
        RpcRadioChannelService(user, koin.get(), koin.get()).withAuthorization<IRadioChannelService>(user)
    }
}

private fun isPrimitive(type: KType): Boolean {
    val classifier = type.classifier ?: return false
    if (classifier !is KClass<*>) return false

    if (classifier.isSubclassOf(Iterable::class)) {
        val itemType = type.arguments.firstOrNull()?.type ?: return false
        return isPrimitive(itemType)
    }

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
            classifier.simpleName == "MetadataType" ||
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
        classifier.simpleName == "MetadataType" -> IMetadataService.MetadataType(value)
        classifier.isSubclassOf(Enum::class) -> {
            @Suppress("UNCHECKED_CAST")
            val enumClass = classifier.java as Class<out Enum<*>>
            enumClass.enumConstants?.find { it.name.equals(value, ignoreCase = true) } ?: throw IllegalArgumentException("Invalid Enum value: $value")
        }
        else -> value
    }
}
