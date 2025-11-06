package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class ResourceIdentifier(
    val id: String,
    val type: String
): BaseAttributes()