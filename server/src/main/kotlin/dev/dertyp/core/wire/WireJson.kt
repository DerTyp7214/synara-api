package dev.dertyp.core.wire

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.modules.SerializersModule

class WireJson(val format: Json, private val wire: LegacyWire? = null) {
    val serializersModule: SerializersModule get() = format.serializersModule

    fun <T> encodeToString(serializer: SerializationStrategy<T>, value: T): String =
        format.encodeToString(wire?.strategy(serializer) ?: serializer, value)

    fun <T> decodeFromString(deserializer: DeserializationStrategy<T>, text: String): T =
        format.decodeFromString(wire?.deserializer(deserializer) ?: deserializer, text)

    fun <T> decodeFromJsonElement(deserializer: DeserializationStrategy<T>, element: JsonElement): T =
        format.decodeFromJsonElement(wire?.deserializer(deserializer) ?: deserializer, element)
}
