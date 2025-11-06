package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class GenresAttributes(
    val genreName: String
): BaseAttributes()