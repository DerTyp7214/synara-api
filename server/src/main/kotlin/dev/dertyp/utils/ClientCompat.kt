package dev.dertyp.utils

import dev.dertyp.core.ClientInfo
import dev.dertyp.data.Album
import dev.dertyp.data.CollectionSearchResults
import dev.dertyp.data.CollectionSongMatch
import dev.dertyp.data.ListenedAlbum
import dev.dertyp.data.ListenedSong
import dev.dertyp.data.NowPlaying
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.PlaybackState
import dev.dertyp.data.QueueItem
import dev.dertyp.data.RadioChannelSearchResults
import dev.dertyp.data.RadioChannelSongMatch
import dev.dertyp.data.RecentListens
import dev.dertyp.data.Song
import dev.dertyp.data.User
import dev.dertyp.data.UserSong
import dev.dertyp.services.IUiService
import dev.dertyp.services.models.RecentRelease
import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiContext
import dev.dertyp.ui.UiInvokePayload
import dev.dertyp.ui.UiInvokeResult
import dev.dertyp.ui.UiLiveUpdate
import dev.dertyp.ui.UiRender
import dev.dertyp.ui.UiSlotRender
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

open class ResponseShaper(
    val client: ClientInfo,
    rules: List<CompatRule> = CompatRules.all,
    val user: User? = null,
    val call: ApplicationCall? = null,
) {
    private val activeRules = rules
        .filter { it.isActive(client) }
        .sortedByDescending { it.feature.minApiVersion }

    open val isNoop: Boolean get() = activeRules.isEmpty()

    fun scope(contributionId: String? = null, context: UiContext = UiContext()): CompatScope =
        CompatScope(client, user, contributionId, context, call)

    @Suppress("UNCHECKED_CAST")
    suspend fun shape(value: Any?, scope: CompatScope = scope()): Any? = when (value) {
        is Album -> shapeAlbum(value)
        is ListenedAlbum -> value.copy(album = shapeAlbum(value.album))
        is CollectionSearchResults -> value.copy(
            songs = shapePaginated(value.songs as PaginatedResponse<Any?>, scope) as PaginatedResponse<CollectionSongMatch>,
            albums = shapePaginated(value.albums as PaginatedResponse<Any?>, scope) as PaginatedResponse<Album>,
        )

        is RadioChannelSearchResults -> value.copy(
            songs = shapePaginated(value.songs as PaginatedResponse<Any?>, scope) as PaginatedResponse<RadioChannelSongMatch>,
            albums = shapePaginated(value.albums as PaginatedResponse<Any?>, scope) as PaginatedResponse<Album>,
        )

        is UserSong -> shapeUserSong(value)
        is Song -> shapeSong(value)
        is UiComponent -> shapeUiComponent(value, scope)
        is UiRender -> scope.copy(contributionId = value.contributionId).let { renderScope ->
            value.copy(
                root = shapeUiComponent(value.root, renderScope),
                toolbar = value.toolbar.map { shapeUiComponent(it, renderScope) },
                schemaVersion = minOf(value.schemaVersion, client.uiSchemaVersion),
            )
        }

        is UiSlotRender -> value.copy(items = value.items.map { shape(it, scope) as UiRender })
        is UiLiveUpdate.Replace -> value.copy(child = shapeUiComponent(value.child, scope))
        is RecentRelease -> shapeRecentReleases(listOf(value)).first()
        is PaginatedResponse<*> -> shapePaginated(value as PaginatedResponse<Any?>, scope)
        is List<*> -> shapeList(value, scope)
        is Map<*, *> -> value.mapValues { shape(it.value, scope) }
        is Flow<*> -> value.map { shape(it, scope) }
        is NowPlaying -> value.copy(song = shapeUserSong(value.song))
        is RecentListens -> value.copy(
            nowPlaying = value.nowPlaying?.let { shape(it, scope) as NowPlaying },
            recent = value.recent.map { shape(it, scope) as ListenedSong },
        )

        is ListenedSong -> value.copy(song = shapeUserSong(value.song))
        is CollectionSongMatch -> value.copy(song = shapeUserSong(value.song))
        is RadioChannelSongMatch -> value.copy(song = shapeUserSong(value.song))
        is PlaybackState -> value.copy(queue = value.queue.map { shape(it, scope) as PlaybackState.QueueEntry })
        is PlaybackState.QueueEntry.WithSong -> value.copy(song = shapeUserSong(value.song))
        is QueueItem -> value.copy(song = value.song?.let(::shapeUserSong))
        else -> value
    }

    suspend fun interceptUiInvoke(
        scope: CompatScope,
        contributionId: String,
        actionId: String,
        payload: UiInvokePayload,
    ): UiInvokeResult? = activeRules.firstNotNullOfOrNull { it.interceptUiInvoke(scope, contributionId, actionId, payload) }

    @Suppress("UNCHECKED_CAST")
    private suspend fun shapeList(list: List<*>, scope: CompatScope): List<Any?> = when {
        list.isNotEmpty() && list.all { it is RecentRelease } -> shapeRecentReleases(list as List<RecentRelease>)
        list.isNotEmpty() && list.all { it is Album } -> shapeAlbums(list as List<Album>).map(::shapeAlbum)
        else -> list.map { shape(it, scope) }
    }

    private suspend fun shapePaginated(response: PaginatedResponse<Any?>, scope: CompatScope): PaginatedResponse<Any?> {
        val data = shapeList(response.data, scope)
        return response.copy(data = data, total = response.total + (data.size - response.data.size))
    }

    protected open fun shapeRecentReleases(releases: List<RecentRelease>): List<RecentRelease> =
        activeRules.fold(releases) { shaped, rule -> rule.shapeRecentReleases(shaped) }

    protected open fun shapeAlbums(albums: List<Album>): List<Album> =
        activeRules.fold(albums) { shaped, rule -> rule.shapeAlbums(shaped) }

    protected open fun shapeAlbum(album: Album): Album {
        val shaped = activeRules.fold(album) { current, rule -> rule.shapeAlbum(current) }
        return shaped.copy(versions = shaped.versions.map(::shapeAlbum))
    }

    protected open fun shapeSong(song: Song): Song = activeRules.fold(song) { shaped, rule -> rule.shapeSong(shaped) }

    protected open fun shapeUserSong(song: UserSong): UserSong =
        activeRules.fold(song) { shaped, rule -> rule.shapeUserSong(shaped) }

    protected open suspend fun shapeUiComponent(component: UiComponent, scope: CompatScope): UiComponent =
        activeRules.fold(component) { shaped, rule -> rule.shapeUiComponent(shaped, scope) }
}

private val uiContributionCalls = setOf(
    IUiService::render.name,
    IUiService::subscribe.name,
    IUiService::subscribeWithContext.name,
    IUiService::subscribeLive.name,
    IUiService::subscribeLiveWithContext.name,
    IUiService::search.name,
    IUiService::invoke.name,
)

private fun ResponseShaper.scopeOf(method: Method, params: Array<Any?>): CompatScope {
    if (method.declaringClass != IUiService::class.java) return scope()
    val context = params.firstNotNullOfOrNull { it as? UiContext }
        ?: params.firstNotNullOfOrNull { it as? UiInvokePayload }?.context
        ?: UiContext(entityId = params.firstNotNullOfOrNull { it as? UUID })
    val contributionId = if (method.name in uiContributionCalls) params.firstOrNull() as? String else null
    return scope(contributionId, context)
}

private suspend fun ResponseShaper.intercept(method: Method, params: Array<Any?>, scope: CompatScope): UiInvokeResult? {
    if (method.declaringClass != IUiService::class.java || method.name != IUiService::invoke.name) return null
    val contributionId = params[0] as String
    val actionId = params[1] as String
    val payload = params[2] as UiInvokePayload
    return interceptUiInvoke(scope, contributionId, actionId, payload)
}

private fun invokeTarget(target: Any, method: Method, args: Array<Any?>): Any? = try {
    method.invoke(target, *args)
} catch (e: InvocationTargetException) {
    throw e.targetException
}

private suspend fun invokeSuspending(target: Any, method: Method, params: Array<Any?>): Any? =
    suspendCoroutineUninterceptedOrReturn { continuation -> invokeTarget(target, method, arrayOf(*params, continuation)) }

@Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
fun <T : Any> T.withClientCompat(interfaceClass: Class<T>, shaper: ResponseShaper): T {
    if (shaper.isNoop) return this
    val target = this
    val interfaces = (listOf(interfaceClass) + target.javaClass.interfaces).distinct().toTypedArray()

    return Proxy.newProxyInstance(interfaceClass.classLoader, interfaces) { proxy, method, args ->
        if (method.declaringClass == Object::class.java) {
            return@newProxyInstance when (method.name) {
                "toString" -> $$"$$${interfaceClass.simpleName}$ClientCompat"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        }

        val callArgs = args ?: emptyArray()
        val continuation = callArgs.lastOrNull() as? Continuation<Any?>
        if (continuation != null) {
            val params = callArgs.copyOfRange(0, callArgs.size - 1)
            val scope = shaper.scopeOf(method, params)
            val shaped: suspend () -> Any? = {
                shaper.intercept(method, params, scope) ?: shaper.shape(invokeSuspending(target, method, params), scope)
            }
            shaped.startCoroutineUninterceptedOrReturn(continuation)
        } else {
            val scope = shaper.scopeOf(method, callArgs)
            when (val res = invokeTarget(target, method, callArgs)) {
                is Flow<*> -> res.map { shaper.shape(it, scope) }
                else -> res
            }
        }
    } as T
}
