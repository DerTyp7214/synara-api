package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class ArtistClaimsCreateOperationPayloadDataAttributes(
    val artistId: String,
    val provider: Provider
): BaseAttributes() {
    @Suppress("unused")
    enum class Provider {
        DISTROKID,
        CDBABY,
        TUNECORE;
    }
}

