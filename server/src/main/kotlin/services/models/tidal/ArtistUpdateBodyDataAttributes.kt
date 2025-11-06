package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistUpdateBodyDataAttributes(
    val contributionsEnabled: Boolean? = null,
    val contributionsSalesPitch: String? = null,
    val externalLinks: List<ExternalLinkPayload>? = null,
    val handle: String? = null,
    val name: String? = null
): BaseAttributes()