package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistClaimsCreateOperationPayloadData(
    val attributes: ArtistClaimsCreateOperationPayloadDataAttributes,
    val type: Type
) {
    @Suppress("EnumEntryName")
    enum class Type {
        artistClaims;
    }
}

