package dev.dertyp.services.podcast

import dev.dertyp.data.PodcastIndexInfo
import dev.dertyp.data.PodcastIndexResult
import dev.dertyp.plugins.IPodcastIndex
import dev.dertyp.plugins.PluginManager
import dev.dertyp.plugins.PodcastIndexEntry
import dev.dertyp.services.Service
import kotlinx.coroutines.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PodcastIndexService(
    private val pluginManager: PluginManager,
    private val podcastService: PodcastService,
    private val feedService: PodcastFeedService
) : Service() {
    internal var providerTimeout: Duration = PROVIDER_TIMEOUT

    suspend fun indexes(): List<PodcastIndexInfo> = pluginManager.getPodcastIndexes().map {
        PodcastIndexInfo(it.id, it.name, isConfigured(it))
    }

    suspend fun search(query: String, limit: Int, indexes: List<String>): List<PodcastIndexResult> {
        require(query.isNotBlank()) { "Search term must not be blank" }

        val term = query.trim()
        val count = limit.coerceIn(1, MAX_LIMIT)
        val registered = pluginManager.getPodcastIndexes()

        val selected = if (indexes.isEmpty()) {
            registered
        } else {
            indexes.map { id ->
                registered.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown podcast index: $id")
            }
        }

        val providers = selected.filter { isConfigured(it) }
        if (providers.isEmpty()) return emptyList()

        val answers = coroutineScope {
            providers.map { index -> async { runProvider(index, term, count) } }.awaitAll()
        }

        val candidates = providers.indices
            .flatMap { position -> answers[position].mapNotNull { candidate(providers[position], it) } }
            .distinctBy { it.sourceKey }

        val known = podcastService.knownSourceKeys(candidates.map { it.sourceKey })

        return candidates.asSequence()
            .filter { it.sourceKey !in known }
            .take(count)
            .map { it.toResult() }
            .toList()
    }

    private suspend fun isConfigured(index: IPodcastIndex): Boolean = try {
        index.isConfigured()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.warn("Podcast index ${index.id} failed: ${e.message}", e)
        false
    }

    private suspend fun runProvider(index: IPodcastIndex, term: String, count: Int): List<PodcastIndexEntry> = try {
        val results = withTimeoutOrNull(providerTimeout) { index.search(term, count) }
        if (results == null) {
            logger.warn("Podcast index ${index.id} did not answer within $providerTimeout")
            emptyList()
        } else {
            results
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.warn("Podcast index ${index.id} failed: ${e.message}", e)
        emptyList()
    }

    private fun candidate(index: IPodcastIndex, entry: PodcastIndexEntry): IndexCandidate? {
        if (entry.title.isBlank()) return null

        val normalized = try {
            feedService.normalizeFeedUrl(entry.feedUrl)
        } catch (e: IllegalArgumentException) {
            return null
        }

        return IndexCandidate(index, entry, normalized, PodcastKeys.feedSourceKey(normalized))
    }

    private data class IndexCandidate(
        val index: IPodcastIndex,
        val entry: PodcastIndexEntry,
        val feedUrl: String,
        val sourceKey: String
    ) {
        fun toResult() = PodcastIndexResult(
            indexId = index.id,
            indexName = index.name,
            feedUrl = feedUrl,
            title = entry.title,
            description = entry.description,
            author = entry.author,
            imageUrl = entry.imageUrl,
            link = entry.link,
            language = entry.language,
            episodeCount = entry.episodeCount,
            lastPublishedAt = entry.lastPublishedAt,
            explicit = entry.explicit,
            categories = entry.categories
        )
    }

    companion object {
        const val MAX_LIMIT = 100
        val PROVIDER_TIMEOUT = 10.seconds
    }
}
