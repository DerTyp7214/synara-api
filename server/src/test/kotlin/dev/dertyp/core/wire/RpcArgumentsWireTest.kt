@file:OptIn(ExperimentalSerializationApi::class, InternalRpcApi::class, ExperimentalRpcApi::class)

package dev.dertyp.core.wire

import dev.dertyp.core.LegacyServerCbor
import dev.dertyp.core.ServerWire
import dev.dertyp.serializers.AppCbor
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.ILyricsService
import dev.dertyp.services.IQueueService
import dev.dertyp.services.IUserPlaylistService
import io.github.classgraph.ClassGraph
import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.descriptor.RpcCallable
import kotlinx.rpc.descriptor.RpcServiceDescriptor
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlinx.rpc.internal.utils.InternalRpcApi
import kotlinx.rpc.krpc.internal.CallableParametersSerializer
import kotlinx.rpc.krpc.internal.buildContextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.getContextualDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.plus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.full.companionObjectInstance

private class OldClientArguments(private val callable: RpcCallable<*>, cbor: Cbor) : KSerializer<Array<Any?>> {
    private val plain = CallableParametersSerializer(callable, cbor.serializersModule)

    private val values =
        callable.parameters.map { ServerWire.strategy(cbor.serializersModule.buildContextual(it.type)) }

    override val descriptor: SerialDescriptor get() = plain.descriptor

    override fun serialize(encoder: Encoder, value: Array<Any?>) = encoder.encodeStructure(descriptor) {
        values.forEachIndexed { index, strategy ->
            encodeNullableSerializableElement(descriptor, index, strategy, value[index])
        }
    }

    override fun deserialize(decoder: Decoder): Array<Any?> = plain.deserialize(decoder)
}

private class ScannedCallable(val name: String, val callable: RpcCallable<*>)

class RpcArgumentsWireTest {
    private val sampleModule = AppJson.serializersModule

    private val legacyServer = LegacyCborFormat(LegacyServerCbor, ServerWire)

    private val oldClient =
        Cbor(OldClientCbor) { serializersModule = OldClientCbor.serializersModule + ServerWire.module }

    private val maxDepth = 4

    private fun rpcServices(): List<KClass<*>> =
        ClassGraph()
            .enableClassInfo()
            .enableAnnotationInfo()
            .acceptPackages("dev.dertyp")
            .scan().use { scan ->
                scan.getClassesWithAnnotation(Rpc::class.java.name)
                    .filter { it.isInterface }
                    .map { it.loadClass().kotlin }
            }

    private fun descriptorOf(service: KClass<*>): RpcServiceDescriptor<*> {
        val stub = service.java.classLoader.loadClass("${service.qualifiedName}\$\$rpcServiceStub").kotlin
        return stub.companionObjectInstance as RpcServiceDescriptor<*>
    }

    private fun callables(): List<ScannedCallable> {
        val scanned = mutableListOf<ScannedCallable>()
        for (service in rpcServices()) {
            for (callable in descriptorOf(service).callables.values) {
                scanned += ScannedCallable("${service.simpleName}.${callable.name}", callable)
            }
        }
        return scanned.sortedBy { it.name }
    }

    private fun SerialDescriptor.resolved(): SerialDescriptor =
        if (kind == SerialKind.CONTEXTUAL) sampleModule.getContextualDescriptor(this) ?: this else this

    private fun string(desc: SerialDescriptor): String = when (desc.serialName.removeSuffix("?")) {
        "UUID" -> "00000000-0000-0000-0000-000000000077"
        "LocalDate" -> "2020-01-02"
        "LocalDateTime" -> "2020-01-02T03:04:05"
        "OffsetDateTime", "Instant" -> "2020-01-02T03:04:05Z"
        "Duration" -> "PT1M"
        else -> "sample"
    }

    private fun sample(raw: SerialDescriptor, depth: Int): JsonElement {
        val desc = raw.resolved()
        if (desc.serialName == JsonElement.serializer().descriptor.serialName) return JsonPrimitive("sample")
        return when (val kind = desc.kind) {
            PrimitiveKind.STRING -> JsonPrimitive(string(desc))
            PrimitiveKind.CHAR -> JsonPrimitive("c")
            PrimitiveKind.BOOLEAN -> JsonPrimitive(true)
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> JsonPrimitive(7)
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> JsonPrimitive(1.5)
            SerialKind.ENUM -> JsonPrimitive(desc.getElementName(desc.elementsCount - 1))
            StructureKind.LIST ->
                JsonArray(
                    if (depth < maxDepth) listOf(
                        sample(
                            desc.getElementDescriptor(0),
                            depth + 1
                        )
                    ) else emptyList()
                )

            StructureKind.MAP -> {
                val key = sample(desc.getElementDescriptor(0), depth + 1)
                if (depth < maxDepth && key is JsonPrimitive) {
                    JsonObject(mapOf(key.content to sample(desc.getElementDescriptor(1), depth + 1)))
                } else {
                    JsonObject(emptyMap())
                }
            }

            StructureKind.CLASS, StructureKind.OBJECT -> classSample(desc, depth)
            PolymorphicKind.SEALED -> {
                val subclass = desc.getElementDescriptor(1).getElementDescriptor(0)
                JsonObject(mapOf("type" to JsonPrimitive(subclass.serialName)) + classSample(subclass, depth))
            }

            else -> error("no sample for ${desc.serialName} of kind $kind")
        }
    }

    private fun classSample(desc: SerialDescriptor, depth: Int): JsonObject = JsonObject(
        (0 until desc.elementsCount)
            .filter { depth < maxDepth || !desc.isElementOptional(it) }
            .associate { desc.getElementName(it) to sample(desc.getElementDescriptor(it), depth + 1) }
    )

    private fun sampleArguments(callable: RpcCallable<*>): Array<Any?> {
        val serializer = CallableParametersSerializer(callable, sampleModule)
        return AppJson.decodeFromJsonElement(serializer, classSample(serializer.descriptor, 0))
    }

    private fun describe(arguments: Array<Any?>): String = arguments.contentDeepToString()

    private fun roundTrip(scanned: ScannedCallable): String? {
        val name = scanned.name
        val callable = scanned.callable
        val arguments = try {
            sampleArguments(callable)
        } catch (e: Exception) {
            return "$name: no sample arguments ($e)"
        }
        return try {
            val oldBytes = oldClient.encodeToByteArray(OldClientArguments(callable, oldClient), arguments)
            val legacy = legacyServer.decodeFromByteArray(
                CallableParametersSerializer(callable, LegacyServerCbor.serializersModule),
                oldBytes,
            )
            val plainBytes =
                AppCbor.encodeToByteArray(CallableParametersSerializer(callable, AppCbor.serializersModule), arguments)
            val plain = AppCbor.decodeFromByteArray(
                CallableParametersSerializer(callable, AppCbor.serializersModule),
                plainBytes,
            )
            when {
                !arguments.contentDeepEquals(legacy) ->
                    "$name: legacy decoded ${describe(legacy)} instead of ${describe(arguments)}"

                !arguments.contentDeepEquals(plain) ->
                    "$name: plain decoded ${describe(plain)} instead of ${describe(arguments)}"

                else -> null
            }
        } catch (e: Exception) {
            "$name: $e"
        }
    }

    @Test
    fun `every rpc callable gets its sample arguments back through the legacy and the plain format`() {
        val all = callables()
        val names = all.map { it.name }.toSet()
        assertTrue("IUserPlaylistService.byId" in names, "scan found $names")
        assertTrue("ILyricsService.getSyncedLyrics" in names, "scan found $names")
        assertTrue("IQueueService.commitUpload" in names, "scan found $names")
        assertTrue(
            rpcServices().containsAll(
                listOf(
                    IUserPlaylistService::class,
                    ILyricsService::class,
                    IQueueService::class
                )
            )
        )

        val failures = (all + all.reversed()).mapNotNull { roundTrip(it) }.distinct()

        assertEquals(emptyList<String>(), failures, "${failures.size} of ${all.size} callables lost arguments")
    }

    @Test
    fun `parameter lists of the same shape keep their own names on the legacy wire`() {
        val shapes = callables().map {
            it.name to CallableParametersSerializer(it.callable, LegacyServerCbor.serializersModule).descriptor
        }
        val renamed = (shapes + shapes.reversed()).mapNotNull { (name, descriptor) ->
            val wire = ServerWire.wireDescriptor(descriptor)
            val expected = List(descriptor.elementsCount) { descriptor.getElementName(it) }
            val actual = List(wire.elementsCount) { wire.getElementName(it) }
            if (expected == actual) null else "$name: $expected went out as $actual"
        }.distinct()

        assertEquals(emptyList<String>(), renamed)
    }
}
