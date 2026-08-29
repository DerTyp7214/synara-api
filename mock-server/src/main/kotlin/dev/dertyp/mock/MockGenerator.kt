package dev.dertyp.mock

import dev.dertyp.data.PaginatedResponse
import kotlinx.coroutines.flow.flow
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import java.util.UUID
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.starProjectedType

object MockGenerator {
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> createMock(serviceInterface: KClass<T>): T {
        return Proxy.newProxyInstance(
            serviceInterface.java.classLoader,
            arrayOf(serviceInterface.java)
        ) { _, method, _ ->
            if (method.declaringClass == Any::class.java) {
                return@newProxyInstance when (method.name) {
                    "toString" -> "Mock${serviceInterface.simpleName}"
                    "hashCode" -> System.identityHashCode(serviceInterface)
                    "equals" -> false
                    else -> null
                }
            }

            val kFunc = serviceInterface.members.find { it.name == method.name }
                ?: throw IllegalStateException("Method ${method.name} not found in ${serviceInterface.simpleName}")

            val returnType = kFunc.returnType
            createDummy(returnType, method.name, 0)
        } as T
    }

    fun createDummy(type: KType, name: String? = null, depth: Int = 0): Any? {
        if (type.isMarkedNullable && (depth > 3 || (0..100).random() < 10)) {
            if (!isEssentialField(name)) {
                return null
            }
        }

        val classifier = type.classifier as? KClass<*> ?: return null

        return when {
            classifier == String::class -> "Mock String ${UUID.randomUUID().toString().take(5)}"
            classifier == Int::class -> {
                if (nameContains(name, "total", "count")) {
                    (1..100).random()
                } else {
                    (0..100).random()
                }
            }
            classifier == Long::class -> (0..Int.MAX_VALUE.toLong()).random()
            classifier == Boolean::class -> {
                if (isEssentialField(name)) {
                    true
                } else {
                    (0..1).random() == 1
                }
            }
            classifier == Double::class -> (0..10000).random().toDouble() / 100.0
            classifier == Float::class -> (0..10000).random().toFloat() / 100.0f
            classifier == ByteArray::class -> {
                val size = if (nameContains(name, "data", "bytes")) 64 else 32
                Random.nextBytes(size)
            }
            classifier == Date::class || classifier.simpleName == "Date" || classifier.simpleName == "PlatformDate" -> {
                if (nameContains(name, "expire")) {
                    Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 30)
                } else {
                    Date()
                }
            }
            classifier == Instant::class || classifier.simpleName == "Instant" || classifier.simpleName == "PlatformInstant" -> {
                if (nameContains(name, "expire")) {
                    Instant.now().plusSeconds(60 * 60 * 24 * 30)
                } else {
                    Instant.now()
                }
            }
            classifier == LocalDate::class || classifier.simpleName == "LocalDate" || classifier.simpleName == "PlatformLocalDate" -> {
                if (nameContains(name, "expire")) {
                    LocalDate.now().plusMonths(1)
                } else {
                    LocalDate.now()
                }
            }
            classifier == UUID::class || classifier.simpleName == "UUID" || classifier.simpleName == "PlatformUUID" -> UUID.randomUUID()
            classifier.isSubclassOf(Enum::class) -> classifier.java.enumConstants?.random()
            classifier.isSealed -> {
                if (depth > 10) return null
                val subclasses = classifier.sealedSubclasses
                val leafOnly = depth > 3
                val candidates = if (leafOnly) subclasses.filter { sub ->
                    sub.primaryConstructor?.parameters?.none { param ->
                        val itemType = param.type.arguments.firstOrNull()?.type?.classifier as? KClass<*>
                        (param.type.classifier as? KClass<*>)?.isSealed == true || itemType?.isSealed == true
                    } ?: true
                }.ifEmpty { subclasses } else subclasses
                val subclass = candidates.randomOrNull() ?: return null
                createDummy(subclass.starProjectedType, name, depth + 1)
            }
            classifier.isSubclassOf(Map::class) -> {
                val keyType = type.arguments.getOrNull(0)?.type ?: return emptyMap<Any, Any>()
                val valueType = type.arguments.getOrNull(1)?.type ?: return emptyMap<Any, Any>()
                if (depth > 5) return emptyMap<Any, Any>()
                val map = (1..3).associate {
                    createDummy(keyType, "${name}Key", depth + 1) to createDummy(valueType, "${name}Value", depth + 1)
                }.filterKeys { it != null }
                @Suppress("UNCHECKED_CAST")
                val result = map as Map<Any, Any>
                if (classifier.isSubclassOf(MutableMap::class)) result.toMutableMap() else result
            }
            classifier.isSubclassOf(Iterable::class) || classifier.isSubclassOf(Collection::class) -> {
                val itemType = type.arguments.firstOrNull()?.type ?: return emptyList<Any>()
                if (depth > 5) return if (classifier.isSubclassOf(Set::class)) emptySet() else emptyList<Any>()
                val items = List(3) { createDummy(itemType, name, depth + 1) }.let { generated ->
                    if (itemType.isMarkedNullable) generated else generated.filterNotNull()
                }
                when {
                    classifier.isSubclassOf(MutableSet::class) -> items.toMutableSet()
                    classifier.isSubclassOf(Set::class) -> items.toSet()
                    classifier.isSubclassOf(MutableList::class) -> items.toMutableList()
                    else -> items
                }
            }
            classifier.simpleName == "Flow" -> {
                val itemType = type.arguments.firstOrNull()?.type ?: return null
                flow {
                    repeat(3) {
                        emit(createDummy(itemType, name, depth + 1))
                    }
                }
            }
            classifier.simpleName == "PaginatedResponse" -> {
                val itemType = type.arguments.firstOrNull()?.type ?: return null
                val items = List(5) { createDummy(itemType, name, depth + 1) }
                @Suppress("UNCHECKED_CAST")
                PaginatedResponse(
                    data = items as List<Any>,
                    total = items.size,
                    page = 0,
                    pageSize = 50,
                    hasNextPage = false
                )
            }
            classifier.isData -> {
                if (depth > 10) return try {
                    classifier.createInstance()
                } catch (_: Exception) {
                    null
                }
                val constructor = classifier.primaryConstructor ?: return null
                val args = constructor.parameters.mapNotNull { param ->
                    val value = createDummy(param.type, param.name, depth + 1)
                    if (value == null && !param.type.isMarkedNullable && param.isOptional) {
                        null
                    } else {
                        param to value
                    }
                }.toMap()
                constructor.callBy(args)
            }
            else -> {
                try {
                    classifier.createInstance()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private fun nameContains(name: String?, vararg keywords: String): Boolean {
        val lowercaseName = name?.lowercase() ?: return false
        return keywords.any { lowercaseName.contains(it) }
    }

    private fun isEssentialField(name: String?): Boolean {
        return nameContains(
            name,
            "active", "valid", "token", "id", "data", "success",
            "secure", "supported", "enabled", "completed", "finished",
            "healthy", "connected", "authorized", "authenticated", "admin"
        )
    }
}
