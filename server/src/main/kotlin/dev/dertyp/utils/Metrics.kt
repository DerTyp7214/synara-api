package dev.dertyp.utils

import dev.dertyp.services.RpcMetricsCollector
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

@Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
fun <T : Any> T.withMetrics(interfaceClass: Class<T>, username: String, collector: RpcMetricsCollector): T {
    val target = this
    val service = interfaceClass.simpleName

    return Proxy.newProxyInstance(interfaceClass.classLoader, arrayOf(interfaceClass)) { proxy, method, args ->
        if (method.declaringClass == Object::class.java) {
            return@newProxyInstance when (method.name) {
                "toString" -> $$"$${interfaceClass.simpleName}$Proxy"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        }

        collector.record(service, method.name, username)

        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    } as T
}
