package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class ArtistRolesAttributes(
    val name: String? = null
): BaseAttributes()