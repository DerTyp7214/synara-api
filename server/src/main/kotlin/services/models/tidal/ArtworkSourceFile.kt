package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtworkSourceFile(
    val md5Hash: String,
    val propertySize: Long,
    val status: FileStatus,
    val uploadLink: FileUploadLink
)