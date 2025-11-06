package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistBiographyUpdateBodyData(
    val attributes: ArtistBiographyUpdateBodyDataAttributes,
    val id: String,
    val type: Type
) {
    @Suppress("EnumEntryName")
    enum class Type {
        artistBiographies;
    }
}

