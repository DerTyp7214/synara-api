package dev.dertyp.plugins

import kotlin.reflect.KClass

fun interface HookRegistration {
    fun cancel()
}

interface HookBus {
    fun <E : HookEvent> on(type: KClass<E>, handler: suspend (E) -> Unit): HookRegistration
    suspend fun emit(event: HookEvent)
}

inline fun <reified E : HookEvent> HookBus.on(noinline handler: suspend (E) -> Unit): HookRegistration =
    on(E::class, handler)
