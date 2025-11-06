package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class ArtworkCreateOperationPayloadDataAttributesSourceFile(
    val md5Hash: String,
    val propertySize: Long
)