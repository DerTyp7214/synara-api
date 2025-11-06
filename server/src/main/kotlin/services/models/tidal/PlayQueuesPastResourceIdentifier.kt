package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlayQueuesPastResourceIdentifier(
    val id: String,
    val type: String,
    val meta: PlayQueuesPastResourceIdentifierMeta? = null
)