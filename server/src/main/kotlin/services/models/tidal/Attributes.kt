package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class Attributes(
    val collectionType: CollectionType,
    val name: String
): BaseAttributes() {
    @Suppress("unused")
    enum class CollectionType {
        PLAYLISTS;
    }
}

