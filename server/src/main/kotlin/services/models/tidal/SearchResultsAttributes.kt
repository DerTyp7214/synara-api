package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class SearchResultsAttributes(
    val trackingId: String,
    val didYouMean: String? = null
): BaseAttributes()