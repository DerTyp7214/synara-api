package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class PlaylistUpdateOperationPayloadDataAttributes(
    val accessType: AccessType? = null,
    val description: String? = null,
    val name: String? = null
): BaseAttributes() {
    @Suppress("unused")
    enum class AccessType {
        PUBLIC,
        UNLISTED;
    }
}

