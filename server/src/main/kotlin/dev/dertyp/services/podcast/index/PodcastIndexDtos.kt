package dev.dertyp.services.podcast.index

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class PodcastIndexSearchResponse(
    val status: String = "",
    val feeds: List<PodcastIndexFeed> = emptyList(),
    val count: Int = 0,
    val description: String = "",
)

@Serializable
data class PodcastIndexFeed(
    val id: Long = 0,
    val title: String = "",
    val url: String = "",
    val originalUrl: String? = null,
    val link: String? = null,
    val description: String? = null,
    val author: String? = null,
    val ownerName: String? = null,
    val image: String? = null,
    val artwork: String? = null,
    val language: String? = null,
    val explicit: Boolean = false,
    val episodeCount: Int? = null,
    val newestItemPubdate: Long? = null,
    val categories: JsonElement? = null,
) {
    val categoryNames: List<String>
        get() = (categories as? JsonObject)
            ?.values
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
}
