@file:OptIn(ExperimentalSerializationApi::class)

package dev.dertyp.core

import dev.dertyp.core.wire.LegacyWire
import dev.dertyp.core.wire.WireJson
import dev.dertyp.core.wire.legacyCbor
import dev.dertyp.serializers.AppCbor
import dev.dertyp.serializers.AppJson
import kotlinx.rpc.krpc.serialization.KrpcSerialFormatConfiguration
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.plus

val ServerWire = LegacyWire(emptyList())

val LegacyServerJson = Json(AppJson) { serializersModule = AppJson.serializersModule + ServerWire.module }

val LegacyServerCbor = Cbor(AppCbor) { serializersModule = AppCbor.serializersModule + ServerWire.module }

private val PlainWireJson = WireJson(AppJson)

private val LegacyWireJson = WireJson(LegacyServerJson, ServerWire)

fun jsonFor(client: ClientInfo): WireJson =
    if (client.supports(ClientFeature.FIELD_RENAMES)) PlainWireJson else LegacyWireJson

fun KrpcSerialFormatConfiguration.cborFor(client: ClientInfo) {
    if (client.supports(ClientFeature.FIELD_RENAMES)) cbor(AppCbor) else legacyCbor(LegacyServerCbor, ServerWire)
}
