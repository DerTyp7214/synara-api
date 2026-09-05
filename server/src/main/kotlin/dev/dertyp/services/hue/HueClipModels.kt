package dev.dertyp.services.hue

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HuePairRequest(val devicetype: String, val generateclientkey: Boolean = true)

@Serializable
data class HuePairSuccess(val username: String, val clientkey: String? = null)

@Serializable
data class HuePairError(val type: Int, val description: String? = null)

@Serializable
data class HuePairResponseEntry(val success: HuePairSuccess? = null, val error: HuePairError? = null)

@Serializable
data class ClipError(val description: String? = null)

@Serializable
data class ClipResponse<T>(val errors: List<ClipError> = emptyList(), val data: List<T> = emptyList())

@Serializable
data class ClipMetadata(val name: String? = null, val archetype: String? = null)

@Serializable
data class ClipXy(val x: Double, val y: Double)

@Serializable
data class ClipGamut(val red: ClipXy? = null, val green: ClipXy? = null, val blue: ClipXy? = null)

@Serializable
data class ClipColor(
    val xy: ClipXy? = null,
    val gamut: ClipGamut? = null,
    @SerialName("gamut_type") val gamutType: String? = null,
)

@Serializable
data class ClipOn(val on: Boolean)

@Serializable
data class ClipDimming(val brightness: Double)

@Serializable
data class ClipDynamics(val duration: Int)

@Serializable
data class ClipResourceRef(val rid: String, val rtype: String)

@Serializable
data class ClipLight(
    val id: String,
    val metadata: ClipMetadata? = null,
    val on: ClipOn? = null,
    val dimming: ClipDimming? = null,
    val color: ClipColor? = null,
    val owner: ClipResourceRef? = null,
)

@Serializable
data class ClipGroup(
    val id: String,
    val metadata: ClipMetadata? = null,
    val children: List<ClipResourceRef> = emptyList(),
    val services: List<ClipResourceRef> = emptyList(),
) {
    val groupedLightId: String? get() = services.firstOrNull { it.rtype == "grouped_light" }?.rid
}

@Serializable
data class ClipGroupedLight(
    val id: String,
    val owner: ClipResourceRef? = null,
    val on: ClipOn? = null,
    val dimming: ClipDimming? = null,
)

@Serializable
data class ClipBridge(
    val id: String,
    @SerialName("bridge_id") val bridgeId: String? = null,
)

@Serializable
data class ClipColorUpdate(val xy: ClipXy)

@Serializable
data class LightUpdate(
    val on: ClipOn? = null,
    val dimming: ClipDimming? = null,
    val color: ClipColorUpdate? = null,
    val dynamics: ClipDynamics? = null,
)
