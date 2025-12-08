package dev.dertyp.services.models

import kotlinx.serialization.Serializable

@Serializable
data class Image(
    val url: String,
    val width: Int,
    val height: Int,
)