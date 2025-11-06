package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class ArtistBiographiesAttributes(
    val editable: Boolean,
    val text: String
): BaseAttributes()