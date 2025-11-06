package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ExternalLink(
    val href: String,
    val meta: ExternalLinkMeta
)