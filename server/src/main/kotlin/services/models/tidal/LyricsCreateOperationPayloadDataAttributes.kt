package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class LyricsCreateOperationPayloadDataAttributes(
    val text: String? = null
): BaseAttributes()