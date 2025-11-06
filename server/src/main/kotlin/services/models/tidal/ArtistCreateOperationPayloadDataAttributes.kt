package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class ArtistCreateOperationPayloadDataAttributes(
    val name: String,
    val handle: String? = null
): BaseAttributes()