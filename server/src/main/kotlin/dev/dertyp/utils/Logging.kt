package dev.dertyp.utils

import io.ktor.util.logging.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation

enum class LogMode {
    DEFAULT, MASK, EXCLUDE
}

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class LogParam(val property: String = "", val mode: LogMode = LogMode.DEFAULT)

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

        val implMethod = try {
            target.javaClass.getMethod(method.name, *method.parameterTypes)
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

        logger.info("${method.name}(${logArgs.joinToString(", ")})")

        try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    } as T
}
