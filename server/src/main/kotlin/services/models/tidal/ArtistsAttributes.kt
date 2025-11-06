package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtistsAttributes(
    val name: String,

    /* Artist popularity (0.0 - 1.0) */
    val popularity: Double,
    val contributionsEnabled: Boolean? = null,
    val contributionsSalesPitch: String? = null,
    val externalLinks: List<ExternalLink>? = null,
    val handle: String? = null,
    val spotlighted: Boolean? = null
): BaseAttributes()