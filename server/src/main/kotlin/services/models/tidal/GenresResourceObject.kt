package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class GenresResourceObject(
    val id: String,
    val type: String,
    val attributes: GenresAttributes? = null
)