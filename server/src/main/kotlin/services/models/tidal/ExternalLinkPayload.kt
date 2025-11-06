package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ExternalLinkPayload(
    val meta: ExternalLinkMeta,
    val href: String? = null
)