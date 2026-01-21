package dev.dertyp.core

import dev.dertyp.serializers.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

object ApplicationScope {
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json = Json {
        encodeDefaults = true
        isLenient = true
        allowSpecialFloatingPointValues = true
        allowStructuredMapKeys = true
        prettyPrint = false
        useArrayPolymorphism = false
        ignoreUnknownKeys = true

        serializersModule = SerializersModule {
            contextual(UUIDSerializer)
            contextual(DateSerializer)
            contextual(LocalDateSerializer)
            contextual(LocalDateTimeSerializer)
            contextual(OffsetDateTimeSerializer)
            contextual(DurationSerializer)
            contextual(InstantSerializer)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    val cbor = Cbor {
        encodeDefaults = true
        alwaysUseByteString = true
        ignoreUnknownKeys = true

        serializersModule = SerializersModule {
            contextual(UUIDByteSerializer)
            contextual(DateSerializer)
            contextual(LocalDateSerializer)
            contextual(LocalDateTimeSerializer)
            contextual(OffsetDateTimeSerializer)
            contextual(DurationSerializer)
            contextual(InstantSerializer)
        }
    }
}