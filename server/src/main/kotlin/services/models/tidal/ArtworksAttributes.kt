package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtworksAttributes(
    val files: List<ArtworkFile>,
    val mediaType: MediaType,
    val sourceFile: ArtworkSourceFile? = null
): BaseAttributes() {
    @Suppress("unused")
    enum class MediaType {
        IMAGE,
        VIDEO;
    }
}

