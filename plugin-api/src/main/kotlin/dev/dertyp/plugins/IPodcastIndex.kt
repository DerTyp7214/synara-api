package dev.dertyp.plugins

data class PodcastIndexEntry(
    val feedUrl: String,
    val title: String,
    val description: String = "",
    val author: String? = null,
    val imageUrl: String? = null,
    val link: String? = null,
    val language: String? = null,
    val episodeCount: Int? = null,
    val lastPublishedAt: Long? = null,
    val explicit: Boolean = false,
    val categories: List<String> = emptyList()
)

interface IPodcastIndex {
    val id: String
    val name: String

    suspend fun isConfigured(): Boolean

    suspend fun search(query: String, limit: Int): List<PodcastIndexEntry>
}
