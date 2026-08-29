package dev.dertyp.utils

import dev.dertyp.core.ClientInfo
import dev.dertyp.data.CollectionSongMatch
import dev.dertyp.data.ListenedSong
import dev.dertyp.data.NowPlaying
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.PlaybackState
import dev.dertyp.data.RadioChannelSongMatch
import dev.dertyp.data.Song
import dev.dertyp.data.UserSong
import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiLiveUpdate
import dev.dertyp.ui.UiRender
import dev.dertyp.ui.UiSlotRender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

open class ResponseShaper(val client: ClientInfo, rules: List<CompatRule> = CompatRules.all) {
    private val activeRules = rules
        .filter { it.isActive(client) }
        .sortedByDescending { it.feature.minApiVersion }

    open val isNoop: Boolean get() = activeRules.isEmpty()

    @Suppress("UNCHECKED_CAST")
    fun shape(value: Any?): Any? = when (value) {
        is UserSong -> shapeUserSong(value)
        is Song -> shapeSong(value)
        is UiComponent -> shapeUiComponent(value)
        is UiRender -> value.copy(
            root = shapeUiComponent(value.root),
            toolbar = value.toolbar.map(::shapeUiComponent),
            schemaVersion = minOf(value.schemaVersion, client.uiSchemaVersion),
        )
        is UiSlotRender -> value.copy(items = value.items.map { shape(it) as UiRender })
        is UiLiveUpdate.Replace -> value.copy(child = shapeUiComponent(value.child))
        is PaginatedResponse<*> -> (value as PaginatedResponse<Any?>).copy(data = value.data.map(::shape))
        is List<*> -> value.map(::shape)
        is Map<*, *> -> value.mapValues { shape(it.value) }
        is Flow<*> -> value.map(::shape)
        is NowPlaying -> value.copy(song = shapeUserSong(value.song))
        is ListenedSong -> value.copy(song = shapeUserSong(value.song))
        is CollectionSongMatch -> value.copy(song = shapeUserSong(value.song))
        is RadioChannelSongMatch -> value.copy(song = shapeUserSong(value.song))
        is PlaybackState.QueueEntry.Explicit -> value.copy(song = shapeUserSong(value.song))
        else -> value
    }

    protected open fun shapeSong(song: Song): Song = activeRules.fold(song) { shaped, rule -> rule.shapeSong(shaped) }

    protected open fun shapeUserSong(song: UserSong): UserSong = activeRules.fold(song) { shaped, rule -> rule.shapeUserSong(shaped) }

    protected open fun shapeUiComponent(component: UiComponent): UiComponent =
        activeRules.fold(component) { shaped, rule -> rule.shapeUiComponent(shaped, client) }
}

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

        val lastArg = args?.lastOrNull()
        val invokeArgs = if (lastArg is Continuation<*>) {
            val continuation = lastArg as Continuation<Any?>
            val shaped = object : Continuation<Any?> {
                override val context = continuation.context
                override fun resumeWith(result: Result<Any?>) = continuation.resumeWith(result.map(shaper::shape))
            }
            args.copyOf().also { it[it.size - 1] = shaped }
        } else {
            args ?: emptyArray()
        }

        try {
            val res = method.invoke(target, *invokeArgs)
            if (res === COROUTINE_SUSPENDED) res else shaper.shape(res)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    } as T
}
