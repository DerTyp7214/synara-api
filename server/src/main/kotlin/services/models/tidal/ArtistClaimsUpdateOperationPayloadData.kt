package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class ArtistClaimsUpdateOperationPayloadData(
    val type: Type,
    val id: String? = null
) {
    @Suppress("EnumEntryName")
    enum class Type {
        artistClaims;
    }
}

