package dev.dertyp.utils

import dev.dertyp.core.isProxied
import dev.dertyp.core.principalUsername
import io.ktor.server.application.*
import io.ktor.util.logging.*
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation

enum class LogMode {
    DEFAULT, MASK, EXCLUDE
}

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class LogParam(val property: String = "", val mode: LogMode = LogMode.DEFAULT)

interface ProxyTargetHolder {
    val proxyTarget: Any
}

fun unwrapProxyTarget(obj: Any): Any {
    var current = obj
    while (Proxy.isProxyClass(current.javaClass)) {
        val handler = Proxy.getInvocationHandler(current)
        current = (handler as? ProxyTargetHolder)?.proxyTarget ?: break
    }
    return current
}

class LoggingInvocationHandler(
    private val target: Any,
    private val interfaceClass: Class<*>,
    private val prefix: String,
) : InvocationHandler, ProxyTargetHolder {
    override val proxyTarget: Any get() = target

    private val logger = KtorSimpleLogger(interfaceClass.simpleName)

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        if (method.declaringClass == Object::class.java) {
            return when (method.name) {
                "toString" -> $$"$${interfaceClass.simpleName}$Proxy"
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

        val logArgs = args?.indices?.mapNotNull { index ->
            val arg = args[index]
            if (arg is Continuation<*>) return@mapNotNull null

            val interfaceParam = method.parameters[index]
            val implParam = implMethod?.parameters?.get(index)

            val annotation = interfaceParam.getAnnotation(LogParam::class.java)
                ?: implParam?.getAnnotation(LogParam::class.java)

            val mode = annotation?.mode ?: LogMode.DEFAULT
            val property = annotation?.property ?: ""

            if (mode == LogMode.EXCLUDE) return@mapNotNull null
            if (mode == LogMode.MASK) return@mapNotNull "***"

            if (property.isNotEmpty() && arg != null) {
                val propName = property.removePrefix(".")
                try {
                    try {
                        return@mapNotNull "$propName=${arg.javaClass.getMethod(propName).invoke(arg)}"
                    } catch (_: NoSuchMethodException) {
                    }

                    try {
                        val getterName = "get" + propName.replaceFirstChar { it.uppercase() }
                        return@mapNotNull "$propName=${arg.javaClass.getMethod(getterName).invoke(arg)}"
                    } catch (_: NoSuchMethodException) {
                    }

                    try {
                        val getterName = "is" + propName.replaceFirstChar { it.uppercase() }
                        return@mapNotNull "$propName=${arg.javaClass.getMethod(getterName).invoke(arg)}"
                    } catch (_: NoSuchMethodException) {
                    }

                    try {
                        return@mapNotNull "$propName=${arg.javaClass.getField(propName).get(arg)}"
                    } catch (_: NoSuchFieldException) {
                    }

                    arg
                } catch (_: Exception) {
                    arg
                }
            } else {
                arg
            }
        } ?: emptyList()

        logger.info("$prefix${method.name}(${logArgs.joinToString(", ")})")

        return try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
inline fun <reified T : Any> T.withLogging(call: ApplicationCall? = null): T {
    val interfaceClass = T::class.java
    val prefix = buildString {
        if (call?.isProxied == true) append("[Proxy] ")
        call?.principalUsername?.let { append("[$it] ") }
        //if (call?.request?.header(SynaraPackHeader) != "true") append("[No-Pack] ")
    }

    return Proxy.newProxyInstance(
        interfaceClass.classLoader,
        arrayOf(interfaceClass),
        LoggingInvocationHandler(this, interfaceClass, prefix),
    ) as T
}
