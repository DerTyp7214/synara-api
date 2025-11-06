package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistClaimsAttributes(
    val artistId: String,
    val provider: Provider,
    val status: Status,
    val recommendation: Recommendation? = null,
    val redirectUrl: String? = null,
    val retrievedUpcs: List<BarcodeId>? = null
): BaseAttributes() {
    @Suppress("unused")
    enum class Provider {
        DISTROKID,
        CDBABY,
        TUNECORE;
    }

    @Suppress("unused")
    enum class Status {
        AWAITING_OAUTH,
        FETCHING_CONTENT,
        VERIFIED,
        NO_MATCHES,
        AUTHENTICATION_FAILED,
        PROCESSING,
        COMPLETED,
        FAILED,
        CANCELLED;
    }

    @Suppress("unused")
    enum class Recommendation {
        DSP_PROFILE_CLAIMED,
        CONTENT_MIGRATED_TO_UPLOADS,
        NO_CONTENT_MATCHED;
    }
}
