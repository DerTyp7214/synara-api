@file:OptIn(ExperimentalSerializationApi::class)

package dev.dertyp.core

import dev.dertyp.core.wire.LegacyFieldSource
import dev.dertyp.core.wire.LegacyWire
import dev.dertyp.core.wire.WireJson
import dev.dertyp.core.wire.legacyCbor
import dev.dertyp.data.BaseSong
import dev.dertyp.serializers.AppCbor
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.LegacyLyricsCache
import kotlinx.rpc.krpc.serialization.KrpcSerialFormatConfiguration
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.plus
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.util.concurrent.ConcurrentHashMap

val ServerWire = LegacyWire(emptyList())

val LegacyServerJson = Json(AppJson) { serializersModule = AppJson.serializersModule + ServerWire.module }

val LegacyServerCbor = Cbor(AppCbor) { serializersModule = AppCbor.serializersModule + ServerWire.module }

private val PlainWireJson = WireJson(AppJson)

private object LegacyLyricsSource : KoinComponent {
    fun lyrics(value: Any): String = get<LegacyLyricsCache>().lyrics((value as BaseSong).id)
}

val LegacyWireFields: List<LegacyFieldSource> = listOf(
    LegacyFieldSource("lyrics", ClientFeature.LYRICS_SERVICE.minApiVersion - 1, LegacyLyricsSource::lyrics),
)

class ClientWire(val wire: LegacyWire) {
    val json: WireJson = WireJson(Json(AppJson) { serializersModule = AppJson.serializersModule + wire.module }, wire)

    val cbor: Cbor = Cbor(AppCbor) { serializersModule = AppCbor.serializersModule + wire.module }
}

private data class WireRules(val renames: Boolean, val fields: List<LegacyFieldSource>)

private val clientWires = ConcurrentHashMap<WireRules, ClientWire>()

fun wireFor(client: ClientInfo): ClientWire? {
    val rules = WireRules(
        renames = !client.supports(ClientFeature.FIELD_RENAMES),
        fields = LegacyWireFields.filter { client.apiVersion <= it.untilApiVersion },
    )
    if (!rules.renames && rules.fields.isEmpty()) return null
    return clientWires.computeIfAbsent(rules) {
        ClientWire(LegacyWire(emptyList(), it.renames, it.fields))
    }
}

fun jsonFor(client: ClientInfo): WireJson = wireFor(client)?.json ?: PlainWireJson

fun KrpcSerialFormatConfiguration.cborFor(client: ClientInfo) {
    val served = wireFor(client)
    if (served == null) cbor(AppCbor) else legacyCbor(served.cbor, served.wire)
}
