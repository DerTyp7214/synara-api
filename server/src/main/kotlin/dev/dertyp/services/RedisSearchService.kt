package dev.dertyp.services

import dev.dertyp.plugins.RedisCacheProvider
import org.koin.core.component.inject
import redis.clients.jedis.search.IndexDefinition
import redis.clients.jedis.search.IndexOptions
import redis.clients.jedis.search.Query
import redis.clients.jedis.search.Schema
import java.util.UUID

class RedisSearchService : Service() {
    private val config by inject<RedisCacheProvider.Config>()
    private val redisProvider by inject<RedisCacheProvider>()

    private val jedis get() = redisProvider.jedis

    fun isEnabled() = config.useRedisSearch && config.host != "none"

    fun initIndex() {
        if (!isEnabled()) return

        val prefix = config.indexPrefix
        
        createIndex("${prefix}:song-index", "${prefix}:song:", Schema()
            .addTextField("title", 5.0)
            .addTextField("artist", 2.0)
            .addTextField("album", 1.0)
            .addTextField("metadata", 1.0)
        )

        createIndex("${prefix}:artist-index", "${prefix}:artist:", Schema()
            .addTextField("name", 5.0)
            .addTextField("aliases", 2.0)
            .addTextField("groups", 1.0)
            .addTextField("metadata", 1.0)
        )

        createIndex("${prefix}:album-index", "${prefix}:album:", Schema()
            .addTextField("name", 5.0)
            .addTextField("artists", 2.0)
            .addTextField("groups", 1.0)
        )
    }

    private fun createIndex(indexName: String, prefix: String, schema: Schema) {
        try {
            jedis.ftInfo(indexName)
        } catch (_: Exception) {
            try {
                jedis.ftCreate(
                    indexName,
                    IndexOptions.defaultOptions().setDefinition(IndexDefinition().setPrefixes(prefix)),
                    schema
                )
            } catch (_: Exception) { }
        }
    }

    fun indexSong(id: UUID, title: String, artist: String, album: String, metadata: String) {
        if (!isEnabled()) return
        val key = "${config.indexPrefix}:song:$id"
        jedis.hset(key, mapOf(
            "title" to title,
            "artist" to artist,
            "album" to album,
            "metadata" to metadata
        ))
    }

    fun indexArtist(id: UUID, name: String, aliases: String, groups: String, metadata: String) {
        if (!isEnabled()) return
        val key = "${config.indexPrefix}:artist:$id"
        jedis.hset(key, mapOf(
            "name" to name,
            "aliases" to aliases,
            "groups" to groups,
            "metadata" to metadata
        ))
    }

    fun indexAlbum(id: UUID, name: String, artists: String, groups: String) {
        if (!isEnabled()) return
        val key = "${config.indexPrefix}:album:$id"
        jedis.hset(key, mapOf(
            "name" to name,
            "artists" to artists,
            "groups" to groups
        ))
    }

    data class SearchResult(val ids: List<UUID>, val total: Long)

    fun search(index: String, query: String, offset: Int = 0, limit: Int = 1000): SearchResult {
        if (!isEnabled()) return SearchResult(emptyList(), 0)
        val indexName = "${config.indexPrefix}:$index-index"

        val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return SearchResult(emptyList(), 0)

        val redisQuery = tokens.joinToString(" ") { token ->
            if (token.startsWith("-")) {
                "-${token.substring(1)}"
            } else {
                if (token.length >= 3) {
                    "($token* | %$token%)"
                } else {
                    "$token*"
                }
            }
        }

        val q = Query(redisQuery).limit(offset, limit)
        val result = try {
            jedis.ftSearch(indexName, q)
        } catch (e: Exception) {
            logger.error("Redis search failed: ${e.message}")
            return SearchResult(emptyList(), 0)
        }
        
        val ids = result.documents.map { doc ->
            UUID.fromString(doc.id.substringAfterLast(":"))
        }
        return SearchResult(ids, result.totalResults)
    }

    fun getMemoryUsage(): Long {
        if (!isEnabled()) return 0
        return try {
            val songInfo = jedis.ftInfo("${config.indexPrefix}:song-index")
            val artistInfo = jedis.ftInfo("${config.indexPrefix}:artist-index")
            val albumInfo = jedis.ftInfo("${config.indexPrefix}:album-index")

            fun extract(map: Map<String, Any?>): Long {
                val value = map["total_index_memory_sz_mb"] 
                    ?: map["inverted_sz_mb"] 
                    ?: map["total_index_memory_mib"] 
                    ?: return 0L
                
                return when (value) {
                    is Number -> value.toLong()
                    is String -> value.toDoubleOrNull()?.toLong() ?: 0L
                    else -> 0L
                }
            }

            extract(songInfo) + extract(artistInfo) + extract(albumInfo)
        } catch (e: Exception) {
            logger.error("Failed to get Redis memory usage: ${e.message}")
            0
        }
    }
}
