package dev.dertyp.utils

import io.ktor.util.logging.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
inline fun <reified T : Any> T.withLogging(): T {
    val interfaceClass = T::class.java
    val logger = KtorSimpleLogger(interfaceClass.simpleName)
    val target = this

    return Proxy.newProxyInstance(interfaceClass.classLoader, arrayOf(interfaceClass)) { proxy, method, args ->
        if (method.declaringClass == Object::class.java) {
            return@newProxyInstance when (method.name) {
                "toString" -> $$"$${interfaceClass.simpleName}$Proxy"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        }

        val cleanArgs = args?.filter { it !is Continuation<*> } ?: emptyList()
        logger.info("${method.name}(${cleanArgs.joinToString(", ")})")

        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    } as T
}