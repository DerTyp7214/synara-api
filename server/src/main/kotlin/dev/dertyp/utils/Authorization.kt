package dev.dertyp.utils

import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.RequiresAdmin
import dev.dertyp.data.RequiresCapability
import dev.dertyp.data.User
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class AuthorizationInvocationHandler(
    private val target: Any,
    private val interfaceClass: Class<*>,
    private val user: User?,
) : InvocationHandler, ProxyTargetHolder {
    override val proxyTarget: Any get() = target

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        if (method.declaringClass == Object::class.java) {
            return when (method.name) {
                "toString" -> $$"$$${interfaceClass.simpleName}$Proxy"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        }

        val realTarget = unwrapProxyTarget(target)
        val implMethod = try {
            realTarget.javaClass.getMethod(method.name, *method.parameterTypes)
        } catch (_: NoSuchMethodException) {
            null
        }

        val capabilityAnnotation = method.getAnnotation(RequiresCapability::class.java)
            ?: implMethod?.getAnnotation(RequiresCapability::class.java)

        if (capabilityAnnotation != null) {
            if (user == null || !user.hasCapability(capabilityAnnotation.capability)) {
                throw UnauthorizedException("User does not have required capability: ${capabilityAnnotation.capability}")
            }
        }

        val adminAnnotation = method.getAnnotation(RequiresAdmin::class.java)
            ?: implMethod?.getAnnotation(RequiresAdmin::class.java)

        if (adminAnnotation != null) {
            if (user == null || !user.isAdmin) {
                throw UnauthorizedException("User is not an admin")
            }
        }

        return try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
inline fun <reified T : Any> T.withAuthorization(user: User?): T {
    val interfaceClass = T::class.java
    return Proxy.newProxyInstance(
        interfaceClass.classLoader,
        arrayOf(interfaceClass),
        AuthorizationInvocationHandler(this, interfaceClass, user),
    ) as T
}
