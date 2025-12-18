package dev.dertyp.services.models.tidal

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder

interface AttributeType

@Serializable
enum class Type(val value: String) {
    @SerialName("tracks") TRACKS("tracks"),
    @SerialName("albums") ALBUMS("albums"),
    @SerialName("artists") ARTISTS("artists"),
}

@Serializable
sealed class BaseAttributes: AttributeType{
}

@JvmInline
@Serializable(with = JsonAttributeSerializer::class)
value class JsonAttribute(val element: JsonElement) : AttributeType

object JsonAttributeSerializer : KSerializer<JsonAttribute> {
    override val descriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): JsonAttribute {
        val json = (decoder as JsonDecoder).decodeJsonElement()
        return JsonAttribute(json)
    }

    override fun serialize(encoder: Encoder, value: JsonAttribute) {
        (encoder as JsonEncoder).encodeJsonElement(value.element)
    }
}