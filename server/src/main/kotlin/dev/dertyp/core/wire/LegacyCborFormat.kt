@file:OptIn(ExperimentalSerializationApi::class)

package dev.dertyp.core.wire

import kotlinx.rpc.krpc.serialization.KrpcSerialFormat
import kotlinx.rpc.krpc.serialization.KrpcSerialFormatBuilder
import kotlinx.rpc.krpc.serialization.KrpcSerialFormatConfiguration
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.cbor.CborBuilder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus

class LegacyCborFormat(val cbor: Cbor, val wire: LegacyWire) : BinaryFormat {
    override val serializersModule: SerializersModule get() = cbor.serializersModule

    override fun <T> encodeToByteArray(serializer: SerializationStrategy<T>, value: T): ByteArray =
        cbor.encodeToByteArray(wire.strategy(serializer), value)

    override fun <T> decodeFromByteArray(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T =
        cbor.decodeFromByteArray(wire.deserializer(deserializer), bytes)
}

private object LegacyCborKrpcFormat : KrpcSerialFormat<LegacyCborFormat, CborBuilder> {
    override fun withBuilder(from: LegacyCborFormat?, builderConsumer: CborBuilder.() -> Unit): LegacyCborFormat {
        val base = requireNotNull(from)
        return LegacyCborFormat(Cbor(base.cbor, builderConsumer), base.wire)
    }

    override fun CborBuilder.applySerializersModule(serializersModule: SerializersModule) {
        this.serializersModule = this.serializersModule + serializersModule
    }
}

fun KrpcSerialFormatConfiguration.legacyCbor(cbor: Cbor, wire: LegacyWire) {
    register(KrpcSerialFormatBuilder.Binary(LegacyCborKrpcFormat, LegacyCborFormat(cbor, wire)) {})
}
