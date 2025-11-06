package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class ArtistBiographyUpdateBodyDataAttributes(
    val text: String? = null
): BaseAttributes()