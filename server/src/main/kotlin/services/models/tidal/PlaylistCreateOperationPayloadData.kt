package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistCreateOperationPayloadData(
    val attributes: PlaylistCreateOperationPayloadDataAttributes,
    val type: Type
) {
    @Suppress("EnumEntryName")
    enum class Type {
        playlists;
    }
}

