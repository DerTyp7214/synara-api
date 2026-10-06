@file:OptIn(ExperimentalSerializationApi::class)

package dev.dertyp.core.wire

import dev.dertyp.core.ServerWire
import dev.dertyp.core.wire.fixtures.*
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
            .rejectPackages(WirePlaybackState::class.java.packageName)
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

    private fun <T : Any> registeredSubclass(
        wire: LegacyWire,
        base: KClass<T>,
        name: String
    ): DeserializationStrategy<T>? = wire.module.getPolymorphic(base, name)

    private fun reaching(sealed: List<KClass<*>>): List<KClass<*>> = sealed.filter { klass ->
        reachesLegacyName(module.serializer(klass.starProjectedType).descriptor, mutableSetOf())
    }

    private fun unregistered(wire: LegacyWire, reaching: List<KClass<*>>): List<String?> = reaching.filter { klass ->
        val serializer: KSerializer<Any?> = module.serializer(klass.starProjectedType)
        wire.strategy(serializer) === serializer
    }.map { it.qualifiedName }

    private fun missingSubclasses(wire: LegacyWire, reaching: List<KClass<*>>): List<String> =
        reaching.flatMap { klass ->
            val descriptor = module.serializer(klass.starProjectedType).descriptor
            subclassDescriptors(descriptor)
                .filter { registeredSubclass(wire, klass, wireName(it)) == null }
                .map { "${klass.qualifiedName} misses the subclass ${it.serialName}" }
        }

    @Test
    fun `every sealed model that reaches a renamed field is registered with all its subclasses`() {
        val sealed = sealedModelClasses()
        assertTrue(
            sealed.any { it == PlaybackState.QueueEntry::class },
            "scan found ${sealed.map { it.qualifiedName }}"
        )
        assertTrue(sealed.none { it in FixtureSealedClasses }, "scan found ${sealed.map { it.qualifiedName }}")

        val reaching = reaching(sealed)

        assertEquals(emptyList<String?>(), unregistered(ServerWire, reaching), "sealed models missing from ServerWire")
        assertEquals(emptyList<String>(), missingSubclasses(ServerWire, reaching), "subclasses missing from ServerWire")
    }

    @Test
    fun `the guard finds sealed classes and subclasses that are missing from a registry`() {
        val reaching = reaching(FixtureSealedClasses)
        assertEquals(FixtureSealedClasses, reaching)

        assertEquals(emptyList<String?>(), unregistered(FixtureWire, reaching))
        assertEquals(emptyList<String>(), missingSubclasses(FixtureWire, reaching))

        val empty = LegacyWire(emptyList())
        assertEquals(FixtureSealedClasses.map { it.qualifiedName }, unregistered(empty, reaching))

        val incomplete = LegacyWire(
            listOf(
                LegacySealed(
                    WirePlaybackState.QueueEntry::class,
                    WirePlaybackState.QueueEntry.serializer(),
                    listOf(
                        LegacySubclass(
                            WirePlaybackState.QueueEntry.FromSource::class,
                            WirePlaybackState.QueueEntry.FromSource.serializer()
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf(WireQueueWriteResult::class.qualifiedName, WireQueueUploadStart::class.qualifiedName),
            unregistered(incomplete, reaching)
        )
        assertEquals(
            listOf("${WirePlaybackState.QueueEntry::class.qualifiedName} misses the subclass WithSong"),
            missingSubclasses(incomplete, listOf(WirePlaybackState.QueueEntry::class))
        )
    }
}
