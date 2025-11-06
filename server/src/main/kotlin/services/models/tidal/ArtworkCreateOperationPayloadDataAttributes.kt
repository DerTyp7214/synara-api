package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtworkCreateOperationPayloadDataAttributes(
    val mediaType: MediaType,
    val sourceFile: ArtworkCreateOperationPayloadDataAttributesSourceFile
): BaseAttributes() {
    @Suppress("unused")
    enum class MediaType {
        IMAGE,
        VIDEO;
    }
}

