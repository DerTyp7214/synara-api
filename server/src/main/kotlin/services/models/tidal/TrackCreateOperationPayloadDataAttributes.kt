package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class TrackCreateOperationPayloadDataAttributes(
    val accessType: AccessType,
    val title: String,
    val explicit: Boolean? = null
): BaseAttributes() {
    @Suppress("unused")
    enum class AccessType {
        PUBLIC,
        UNLISTED,
        PRIVATE;
    }
}

