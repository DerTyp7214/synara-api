package dev.dertyp.utils

import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.rpc.annotations.Cached
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.security.MessageDigest
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
inline fun <reified T : Any> T.withCaching(): T = withCaching(T::class.java)

@Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
fun <T : Any> T.withCaching(interfaceClass: Class<T>): T {
    val logger = KtorSimpleLogger("Caching(${interfaceClass.simpleName})")
    val target = this

    return Proxy.newProxyInstance(interfaceClass.classLoader, arrayOf(interfaceClass)) { proxy, method, args ->
        if (method.declaringClass == Object::class.java) {
            return@newProxyInstance when (method.name) {
                "toString" -> $$"$$${interfaceClass.simpleName}$Proxy"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        }

        val lastArg = args?.lastOrNull()
        if (lastArg !is Continuation<*>) {
            return@newProxyInstance invokeTarget(method, target, args ?: emptyArray())
        }

        val implMethod = try {
            target.javaClass.getMethod(method.name, *method.parameterTypes)
        } catch (_: NoSuchMethodException) {
            null
        }

        val cachedAnnotation = method.getAnnotation(Cached::class.java)
            ?: implMethod?.getAnnotation(Cached::class.java)
            ?: return@newProxyInstance invokeTarget(method, target, args)

        val cacheProvider = try {
            object : KoinComponent {}.get<RedisCacheProvider>()
        } catch (_: Exception) {
            null
        } ?: return@newProxyInstance invokeTarget(method, target, args)

        val duration = parseDuration(cachedAnnotation.duration)
        val key = "rpc_cache:${interfaceClass.name}:${method.name}:${hashArgs(args)}"

        val continuation = lastArg as Continuation<Any?>
        val call: suspend () -> Any? = {
            val cachedValue = withContext(Dispatchers.IO) { cacheProvider.getCache(key) }
            if (cachedValue != null) {
                logger.debug("Cache hit for $key")
                cachedValue
            } else {
                val res = invokeSuspending(method, target, args)
                if (res != null) {
                    withContext(Dispatchers.IO) { cacheProvider.setCache(key, res, duration) }
                }
                res
            }
        }
        call.startCoroutineUninterceptedOrReturn(continuation)
    } as T
}

private fun invokeTarget(method: Method, target: Any, args: Array<Any?>): Any? =
    try {
        method.invoke(target, *args)
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }

private suspend fun invokeSuspending(method: Method, target: Any, args: Array<Any?>): Any? =
    suspendCoroutineUninterceptedOrReturn { continuation ->
        val newArgs = args.copyOf()
        newArgs[newArgs.size - 1] = continuation
        invokeTarget(method, target, newArgs)
    }

fun parseDuration(durationStr: String): Duration {
    return try {
        Duration.parse(durationStr)
    } catch (_: Exception) {
        val match = Regex("(\\d+)([smhd])").matchEntire(durationStr.lowercase())
        if (match != null) {
            val value = match.groupValues[1].toLong()
            when (match.groupValues[2]) {
                "s" -> Duration.parse("${value}s")
                "m" -> Duration.parse("${value}m")
                "h" -> Duration.parse("${value}h")
                "d" -> Duration.parse("${value}d")
                else -> 5.minutes
            }
        } else {
            5.minutes
        }
    }
}

fun hashArgs(args: Array<Any?>?): String {
    if (args == null) return "none"
    val s = args.filter { it !is Continuation<*> }.joinToString(",") { it.toString() }
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(s.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
