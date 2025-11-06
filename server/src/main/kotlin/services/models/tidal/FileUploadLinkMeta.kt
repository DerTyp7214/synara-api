package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class FileUploadLinkMeta(
    val method: String,
    val headers: Map<String, String>? = null
)