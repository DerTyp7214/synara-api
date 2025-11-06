package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class LyricsUpdateOperationPayloadDataAttributes(
    val lrcText: String? = null,
    val text: String? = null
): BaseAttributes()