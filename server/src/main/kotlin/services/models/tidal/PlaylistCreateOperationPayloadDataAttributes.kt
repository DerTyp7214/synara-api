package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class PlaylistCreateOperationPayloadDataAttributes(
    val name: String,
    val accessType: AccessType? = null,
    val description: String? = null
): BaseAttributes() {
    @Suppress("unused")
    enum class AccessType {
        PUBLIC,
        UNLISTED;
    }
}

