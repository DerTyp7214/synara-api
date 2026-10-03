package dev.dertyp.core.wire

import dev.dertyp.rpc.annotations.LegacyWireName
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.SealedSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.modules.PolymorphicModuleBuilder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlinx.serialization.modules.polymorphic
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.cast

class LegacySubclass<T : Any, S : T>(private val klass: KClass<S>, private val serializer: KSerializer<S>) {
    fun register(builder: PolymorphicModuleBuilder<T>, wire: LegacyWire) {
        builder.subclass(klass, wire.Renamed(serializer))
    }
}

class LegacySealed<T : Any>(
    private val base: KClass<T>,
    serializer: KSerializer<T>,
    private val subclasses: List<LegacySubclass<T, *>>,
) {
    val serialName: String = serializer.descriptor.serialName

    val polymorphic = PolymorphicSerializer(base)

    fun register(builder: SerializersModuleBuilder, wire: LegacyWire) {
        builder.polymorphic(base) { subclasses.forEach { it.register(this, wire) } }
    }

    fun encode(encoder: Encoder, value: Any?) {
        encoder.encodeSerializableValue(polymorphic, base.cast(value))
    }
}

private class WireEntry(source: SerialDescriptor, val wire: SerialDescriptor?) {
    private val names = Array(source.elementsCount) { source.getElementName(it) }

    fun describes(desc: SerialDescriptor): Boolean = names.indices.all { names[it] == desc.getElementName(it) }
}

class LegacyWire(sealed: List<LegacySealed<*>>) {
    private val descriptors = ConcurrentHashMap<SerialDescriptor, WireEntry>()

    private val sealedByName: Map<String, LegacySealed<*>> = sealed.associateBy { it.serialName }

    val module: SerializersModule = SerializersModule { sealed.forEach { it.register(this, this@LegacyWire) } }

    fun wireDescriptor(desc: SerialDescriptor): SerialDescriptor {
        if (desc.kind != StructureKind.CLASS) return desc
        val entry = descriptors.computeIfAbsent(desc) { WireEntry(it, copy(it, it.serialName)) }
        val wire = if (entry.describes(desc)) entry.wire else copy(desc, desc.serialName)
        return wire ?: desc
    }

    private fun copy(desc: SerialDescriptor, serialName: String): SerialDescriptor? {
        val names = List(desc.elementsCount) { desc.getElementAnnotations(it).legacyName() ?: desc.getElementName(it) }
        if (serialName == desc.serialName && names.withIndex().all { (i, name) -> name == desc.getElementName(i) }) {
            return null
        }
        return buildClassSerialDescriptor(serialName) {
            annotations = desc.annotations
            names.forEachIndexed { i, name ->
                element(name, desc.getElementDescriptor(i), desc.getElementAnnotations(i), desc.isElementOptional(i))
            }
        }
    }

    private fun List<Annotation>.legacyName(): String? = filterIsInstance<LegacyWireName>().firstOrNull()?.name

    fun <T> strategy(s: SerializationStrategy<T>): SerializationStrategy<T> {
        if (s is LegacyStrategy<*> || s is Renamed<*> || s is SealedStrategy<*>) return s
        val kind = s.descriptor.kind
        if (kind is PrimitiveKind || kind == SerialKind.ENUM) return s
        if (kind is PolymorphicKind && !s.descriptor.isNullable) {
            return sealedByName[s.descriptor.serialName]?.let { SealedStrategy<T>(it) } ?: s
        }
        return LegacyStrategy(s)
    }

    fun <T> deserializer(d: DeserializationStrategy<T>): DeserializationStrategy<T> {
        if (d is LegacyDeserializer<*> || d is Renamed<*>) return d
        val kind = d.descriptor.kind
        if (kind is PrimitiveKind || kind == SerialKind.ENUM) return d
        if (kind is PolymorphicKind && !d.descriptor.isNullable) {
            val sealed = sealedByName[d.descriptor.serialName] ?: return d

            @Suppress("UNCHECKED_CAST")
            val polymorphic = sealed.polymorphic as DeserializationStrategy<T>
            return polymorphic
        }
        return LegacyDeserializer(d)
    }

    private fun encoder(real: Encoder): Encoder =
        if (real is JsonEncoder) LegacyJsonEncoder(real) else LegacyEncoder(real)

    private fun decoder(real: Decoder): Decoder =
        if (real is JsonDecoder) LegacyJsonDecoder(real) else LegacyDecoder(real)

    inner class Renamed<T>(private val inner: KSerializer<T>) : KSerializer<T> {
        override val descriptor: SerialDescriptor =
            inner.descriptor.annotations.legacyName()?.let { copy(inner.descriptor, it) }
                ?: wireDescriptor(inner.descriptor)

        override fun serialize(encoder: Encoder, value: T) = inner.serialize(encoder(encoder), value)

        override fun deserialize(decoder: Decoder): T = inner.deserialize(decoder(decoder))
    }

    private class SealedStrategy<T>(private val sealed: LegacySealed<*>) : SerializationStrategy<T> {
        override val descriptor: SerialDescriptor get() = sealed.polymorphic.descriptor

        override fun serialize(encoder: Encoder, value: T) = sealed.encode(encoder, value)
    }

    private inner class LegacyStrategy<T>(private val inner: SerializationStrategy<T>) : SerializationStrategy<T> {
        override val descriptor: SerialDescriptor get() = inner.descriptor

        override fun serialize(encoder: Encoder, value: T) = inner.serialize(encoder(encoder), value)
    }

    private inner class LegacyDeserializer<T>(private val inner: DeserializationStrategy<T>) :
        DeserializationStrategy<T> {
        override val descriptor: SerialDescriptor get() = inner.descriptor

        override fun deserialize(decoder: Decoder): T = inner.deserialize(decoder(decoder))
    }

    private fun beginStructure(real: Encoder, descriptor: SerialDescriptor): CompositeEncoder {
        val wire = wireDescriptor(descriptor)
        return LegacyCompositeEncoder(real.beginStructure(wire), wire)
    }

    private fun beginStructure(real: Decoder, descriptor: SerialDescriptor): CompositeDecoder {
        val wire = wireDescriptor(descriptor)
        return LegacyCompositeDecoder(real.beginStructure(wire), wire)
    }

    private inner class LegacyEncoder(private val real: Encoder) : Encoder {
        override val serializersModule: SerializersModule get() = real.serializersModule

        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder = beginStructure(real, descriptor)

        override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder =
            LegacyCompositeEncoder(real.beginCollection(descriptor, collectionSize), descriptor)

        override fun encodeInline(descriptor: SerialDescriptor): Encoder = encoder(real.encodeInline(descriptor))

        override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) =
            real.encodeSerializableValue(strategy(serializer), value)

        override fun <T : Any> encodeNullableSerializableValue(serializer: SerializationStrategy<T>, value: T?) =
            real.encodeNullableSerializableValue(strategy(serializer), value)

        override fun encodeBoolean(value: Boolean) = real.encodeBoolean(value)

        override fun encodeByte(value: Byte) = real.encodeByte(value)

        override fun encodeShort(value: Short) = real.encodeShort(value)

        override fun encodeChar(value: Char) = real.encodeChar(value)

        override fun encodeInt(value: Int) = real.encodeInt(value)

        override fun encodeLong(value: Long) = real.encodeLong(value)

        override fun encodeFloat(value: Float) = real.encodeFloat(value)

        override fun encodeDouble(value: Double) = real.encodeDouble(value)

        override fun encodeString(value: String) = real.encodeString(value)

        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) = real.encodeEnum(enumDescriptor, index)

        override fun encodeNotNullMark() = real.encodeNotNullMark()

        override fun encodeNull() = real.encodeNull()
    }

    @OptIn(SealedSerializationApi::class)
    private inner class LegacyJsonEncoder(private val real: JsonEncoder) : JsonEncoder by real {
        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder = beginStructure(real, descriptor)

        override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder =
            LegacyCompositeEncoder(real.beginCollection(descriptor, collectionSize), descriptor)

        override fun encodeInline(descriptor: SerialDescriptor): Encoder = encoder(real.encodeInline(descriptor))

        override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) =
            real.encodeSerializableValue(strategy(serializer), value)

        override fun <T : Any> encodeNullableSerializableValue(serializer: SerializationStrategy<T>, value: T?) =
            real.encodeNullableSerializableValue(strategy(serializer), value)
    }

    private inner class LegacyCompositeEncoder(
        private val real: CompositeEncoder,
        private val wire: SerialDescriptor,
    ) : CompositeEncoder {
        override val serializersModule: SerializersModule get() = real.serializersModule

        override fun endStructure(descriptor: SerialDescriptor) = real.endStructure(wire)

        override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int): Boolean =
            real.shouldEncodeElementDefault(wire, index)

        override fun encodeBooleanElement(descriptor: SerialDescriptor, index: Int, value: Boolean) =
            real.encodeBooleanElement(wire, index, value)

        override fun encodeByteElement(descriptor: SerialDescriptor, index: Int, value: Byte) =
            real.encodeByteElement(wire, index, value)

        override fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short) =
            real.encodeShortElement(wire, index, value)

        override fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char) =
            real.encodeCharElement(wire, index, value)

        override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int) =
            real.encodeIntElement(wire, index, value)

        override fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long) =
            real.encodeLongElement(wire, index, value)

        override fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float) =
            real.encodeFloatElement(wire, index, value)

        override fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double) =
            real.encodeDoubleElement(wire, index, value)

        override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String) =
            real.encodeStringElement(wire, index, value)

        override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder =
            encoder(real.encodeInlineElement(wire, index))

        override fun <T> encodeSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            serializer: SerializationStrategy<T>,
            value: T,
        ) = real.encodeSerializableElement(wire, index, strategy(serializer), value)

        override fun <T : Any> encodeNullableSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            serializer: SerializationStrategy<T>,
            value: T?,
        ) = real.encodeNullableSerializableElement(wire, index, strategy(serializer), value)
    }

    private inner class LegacyDecoder(private val real: Decoder) : Decoder {
        override val serializersModule: SerializersModule get() = real.serializersModule

        override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = beginStructure(real, descriptor)

        override fun decodeInline(descriptor: SerialDescriptor): Decoder = decoder(real.decodeInline(descriptor))

        override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T =
            real.decodeSerializableValue(deserializer(deserializer))

        override fun <T : Any> decodeNullableSerializableValue(deserializer: DeserializationStrategy<T?>): T? =
            real.decodeNullableSerializableValue(deserializer(deserializer))

        override fun decodeNotNullMark(): Boolean = real.decodeNotNullMark()

        override fun decodeNull(): Nothing? = real.decodeNull()

        override fun decodeBoolean(): Boolean = real.decodeBoolean()

        override fun decodeByte(): Byte = real.decodeByte()

        override fun decodeShort(): Short = real.decodeShort()

        override fun decodeChar(): Char = real.decodeChar()

        override fun decodeInt(): Int = real.decodeInt()

        override fun decodeLong(): Long = real.decodeLong()

        override fun decodeFloat(): Float = real.decodeFloat()

        override fun decodeDouble(): Double = real.decodeDouble()

        override fun decodeString(): String = real.decodeString()

        override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = real.decodeEnum(enumDescriptor)
    }

    @OptIn(SealedSerializationApi::class)
    private inner class LegacyJsonDecoder(private val real: JsonDecoder) : JsonDecoder by real {
        override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = beginStructure(real, descriptor)

        override fun decodeInline(descriptor: SerialDescriptor): Decoder = decoder(real.decodeInline(descriptor))

        override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T =
            real.decodeSerializableValue(deserializer(deserializer))

        override fun <T : Any> decodeNullableSerializableValue(deserializer: DeserializationStrategy<T?>): T? =
            real.decodeNullableSerializableValue(deserializer(deserializer))
    }

    private inner class LegacyCompositeDecoder(
        private val real: CompositeDecoder,
        private val wire: SerialDescriptor,
    ) : CompositeDecoder {
        override val serializersModule: SerializersModule get() = real.serializersModule

        override fun endStructure(descriptor: SerialDescriptor) = real.endStructure(wire)

        override fun decodeSequentially(): Boolean = real.decodeSequentially()

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int = real.decodeElementIndex(wire)

        override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = real.decodeCollectionSize(wire)

        override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int): Boolean =
            real.decodeBooleanElement(wire, index)

        override fun decodeByteElement(descriptor: SerialDescriptor, index: Int): Byte =
            real.decodeByteElement(wire, index)

        override fun decodeShortElement(descriptor: SerialDescriptor, index: Int): Short =
            real.decodeShortElement(wire, index)

        override fun decodeCharElement(descriptor: SerialDescriptor, index: Int): Char =
            real.decodeCharElement(wire, index)

        override fun decodeIntElement(descriptor: SerialDescriptor, index: Int): Int =
            real.decodeIntElement(wire, index)

        override fun decodeLongElement(descriptor: SerialDescriptor, index: Int): Long =
            real.decodeLongElement(wire, index)

        override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int): Float =
            real.decodeFloatElement(wire, index)

        override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int): Double =
            real.decodeDoubleElement(wire, index)

        override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String =
            real.decodeStringElement(wire, index)

        override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder =
            decoder(real.decodeInlineElement(wire, index))

        override fun <T> decodeSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            deserializer: DeserializationStrategy<T>,
            previousValue: T?,
        ): T = real.decodeSerializableElement(wire, index, deserializer(deserializer), previousValue)

        override fun <T : Any> decodeNullableSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            deserializer: DeserializationStrategy<T?>,
            previousValue: T?,
        ): T? = real.decodeNullableSerializableElement(wire, index, deserializer(deserializer), previousValue)
    }
}
