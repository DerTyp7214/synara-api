package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class LyricsCreateOperationPayloadMeta(
    val generate: Boolean? = null
)