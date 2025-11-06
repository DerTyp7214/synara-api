package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlayQueuesFutureResourceIdentifier(
    val id: String,
    val type: String,
    val meta: PlayQueuesFutureResourceIdentifierMeta? = null
)