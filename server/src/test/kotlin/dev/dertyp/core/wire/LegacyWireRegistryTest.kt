@file:OptIn(ExperimentalSerializationApi::class)

package dev.dertyp.core.wire

import dev.dertyp.core.ServerWire
import dev.dertyp.data.PlaybackState
import dev.dertyp.rpc.annotations.LegacyWireName
import dev.dertyp.serializers.AppJson
import io.github.classgraph.ClassGraph
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.getContextualDescriptor
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.full.starProjectedType

class LegacyWireRegistryTest {
    private val module = AppJson.serializersModule

    private fun sealedModelClasses(): List<KClass<*>> =
        ClassGraph()
            .enableClassInfo()
            .enableAnnotationInfo()
            .acceptPackages("dev.dertyp")
            .scan().use { scan ->
                scan.getClassesWithAnnotation(Serializable::class.java.name)
                    .map { it.loadClass().kotlin }
                    .filter { it.isSealed && it.typeParameters.isEmpty() }
            }

    private fun SerialDescriptor.resolved(): SerialDescriptor =
        if (kind == SerialKind.CONTEXTUAL) module.getContextualDescriptor(this) ?: this else this

    private fun reachesLegacyName(descriptor: SerialDescriptor, visited: MutableSet<String>): Boolean {
        val resolved = descriptor.resolved()
        if (!visited.add(resolved.serialName)) return false
        if (resolved.annotations.any { it is LegacyWireName }) return true
        if ((0 until resolved.elementsCount).any { index ->
                resolved.getElementAnnotations(index).any { it is LegacyWireName }
            }) {
            return true
        }
        return resolved.elementDescriptors.any { reachesLegacyName(it, visited) }
    }

    private fun subclassDescriptors(sealed: SerialDescriptor): List<SerialDescriptor> =
        sealed.getElementDescriptor(1).elementDescriptors.toList()

    private fun wireName(descriptor: SerialDescriptor): String =
        descriptor.annotations.filterIsInstance<LegacyWireName>().firstOrNull()?.name ?: descriptor.serialName

    private fun <T : Any> registeredSubclass(base: KClass<T>, name: String): DeserializationStrategy<T>? =
        ServerWire.module.getPolymorphic(base, name)

    @Test
    fun `every sealed model that reaches a renamed field is registered with all its subclasses`() {
        val sealed = sealedModelClasses()
        assertTrue(
            sealed.any { it == PlaybackState.QueueEntry::class },
            "scan found ${sealed.map { it.qualifiedName }}"
        )

        val reaching = sealed.filter { klass ->
            reachesLegacyName(module.serializer(klass.starProjectedType).descriptor, mutableSetOf())
        }
        assertTrue(reaching.contains(PlaybackState.QueueEntry::class))

        val unregistered = reaching.filter { klass ->
            val serializer: KSerializer<Any?> = module.serializer(klass.starProjectedType)
            ServerWire.strategy(serializer) === serializer
        }
        assertEquals(
            emptyList<String?>(),
            unregistered.map { it.qualifiedName },
            "sealed models missing from ServerWire"
        )

        reaching.forEach { klass ->
            val descriptor = module.serializer(klass.starProjectedType).descriptor
            subclassDescriptors(descriptor).forEach { subclass ->
                assertNotNull(
                    registeredSubclass(klass, wireName(subclass)),
                    "${klass.qualifiedName} misses the subclass ${subclass.serialName} in ServerWire",
                )
            }
        }
    }
}
