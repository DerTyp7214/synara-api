package dev.dertyp.mock

import dev.dertyp.data.PaginatedResponse
import kotlinx.coroutines.flow.flow
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor

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
            createDummy(returnType, method.name)
        } as T
    }

    fun createDummy(type: KType, name: String? = null): Any? {
        if (type.isMarkedNullable && (0..100).random() < 10) {
            val lowercaseName = name?.lowercase() ?: ""
            if (!lowercaseName.contains("active") &&
                !lowercaseName.contains("valid") &&
                !lowercaseName.contains("token") &&
                !lowercaseName.contains("id") &&
                !lowercaseName.contains("success")) {
                return null
            }
        }

        val classifier = type.classifier as? KClass<*> ?: return null

        return when {
            classifier == String::class -> "Mock String ${UUID.randomUUID().toString().take(5)}"
            classifier == Int::class -> {
                if (name?.lowercase()?.contains("total") == true || name?.lowercase()?.contains("count") == true) {
                    (1..100).random()
                } else {
                    (0..100).random()
                }
            }
            classifier == Long::class -> (0..1000L).random()
            classifier == Boolean::class -> {
                val lowercaseName = name?.lowercase() ?: ""
                if (lowercaseName.contains("active") ||
                    lowercaseName.contains("valid") ||
                    lowercaseName.contains("success") ||
                    lowercaseName.contains("secure") ||
                    lowercaseName.contains("supported") ||
                    lowercaseName.contains("enabled") ||
                    lowercaseName.contains("completed") ||
                    lowercaseName.contains("finished") ||
                    lowercaseName.contains("healthy") ||
                    lowercaseName.contains("connected") ||
                    lowercaseName.contains("authorized") ||
                    lowercaseName.contains("authenticated")) {
                    true
                } else {
                    (0..1).random() == 1
                }
            }
            classifier == Double::class -> (0..10000).random().toDouble() / 100.0
            classifier == Float::class -> (0..10000).random().toFloat() / 100.0f
            classifier == Date::class || classifier.simpleName == "Date" || classifier.simpleName == "PlatformDate" -> {
                if (name?.lowercase()?.contains("expire") == true) {
                    Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 30)
                } else {
                    Date()
                }
            }
            classifier == Instant::class || classifier.simpleName == "Instant" || classifier.simpleName == "PlatformInstant" -> {
                if (name?.lowercase()?.contains("expire") == true) {
                    Instant.now().plusSeconds(60 * 60 * 24 * 30)
                } else {
                    Instant.now()
                }
            }
            classifier == LocalDate::class || classifier.simpleName == "LocalDate" || classifier.simpleName == "PlatformLocalDate" -> {
                if (name?.lowercase()?.contains("expire") == true) {
                    LocalDate.now().plusMonths(1)
                } else {
                    LocalDate.now()
                }
            }
            classifier == UUID::class || classifier.simpleName == "UUID" || classifier.simpleName == "PlatformUUID" -> UUID.randomUUID()
            classifier.isSubclassOf(Enum::class) -> classifier.java.enumConstants?.random()
            classifier == List::class || classifier == Collection::class || classifier == Iterable::class || classifier == Set::class -> {
                val itemType = type.arguments.firstOrNull()?.type ?: return emptyList<Any>()
                List(3) { createDummy(itemType, name) }.let {
                    if (classifier == Set::class) it.toSet() else it
                }
            }
            classifier.simpleName == "Flow" -> {
                val itemType = type.arguments.firstOrNull()?.type ?: return null
                flow {
                    repeat(3) {
                        emit(createDummy(itemType, name))
                    }
                }
            }
            classifier.simpleName == "PaginatedResponse" -> {
                val itemType = type.arguments.firstOrNull()?.type ?: return null
                val items = List(5) { createDummy(itemType, name) }
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
                val constructor = classifier.primaryConstructor ?: return null
                val args = constructor.parameters.associateWith { param ->
                    createDummy(param.type, param.name)
                }
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
}
