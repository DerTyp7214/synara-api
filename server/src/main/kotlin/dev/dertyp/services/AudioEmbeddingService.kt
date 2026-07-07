package dev.dertyp.services

import dev.dertyp.ApiClient
import dev.dertyp.PlatformUUID
import dev.dertyp.db.SongAudioEmbeddingTable
import dev.dertyp.db.SongTable
import dev.dertyp.dbQuery
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.koin.core.component.inject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class SongFileRef(val id: PlatformUUID, val path: String)

class AudioEmbeddingService : Service() {
    private val environment by inject<ApplicationEnvironment>()

    private val url: String?
        get() = environment.config.propertyOrNull("audioEmbed.url")?.getString()?.takeIf { it.isNotBlank() }

    fun isConfigured(): Boolean = url != null

    suspend fun getUnembeddedSongs(): List<SongFileRef> = dbQuery {
        val embedded = SongAudioEmbeddingTable.selectAll()
            .map { it[SongAudioEmbeddingTable.songId].value }
            .toHashSet()
        SongTable.select(SongTable.id, SongTable.filePath)
            .where { SongTable.filePath neq "" }
            .mapNotNull { row ->
                val id = row[SongTable.id].value
                if (id in embedded) null else SongFileRef(id, row[SongTable.filePath])
            }
    }

    suspend fun embedAndStore(batch: List<SongFileRef>): Int {
        val endpoint = url ?: return 0
        if (batch.isEmpty()) return 0

        val byPath = batch.associateBy { it.path }
        val response = ApiClient.instance.post("$endpoint/embed") {
            contentType(ContentType.Application.Json)
            timeout {
                requestTimeoutMillis = 15.minutes.inWholeMilliseconds
                connectTimeoutMillis = 10.seconds.inWholeMilliseconds
                socketTimeoutMillis = 15.minutes.inWholeMilliseconds
            }
            setBody(EmbedRequest(batch.map { it.path }))
        }

        if (response.status != HttpStatusCode.OK) {
            logger.error("audio-embed /embed returned ${response.status}")
            return 0
        }

        val body = response.body<EmbedResponse>()
        val now = System.currentTimeMillis()
        var stored = 0
        dbQuery {
            body.results.forEach { result ->
                val vector = result.vector ?: return@forEach
                val ref = byPath[result.path] ?: return@forEach
                SongAudioEmbeddingTable.upsert(SongAudioEmbeddingTable.songId) {
                    it[SongAudioEmbeddingTable.songId] = ref.id
                    it[SongAudioEmbeddingTable.vector] = packFloats(vector)
                    it[SongAudioEmbeddingTable.dim] = body.dim
                    it[SongAudioEmbeddingTable.modelVersion] = body.modelVersion
                    it[SongAudioEmbeddingTable.updatedAt] = now
                }
                stored++
            }
        }
        return stored
    }

    private fun packFloats(values: List<Float>): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        return buffer.array()
    }
}

@Serializable
private data class EmbedRequest(val paths: List<String>)

@Serializable
private data class EmbedResult(val path: String, val vector: List<Float>? = null)

@Serializable
private data class EmbedResponse(
    val modelVersion: String,
    val dim: Int,
    val results: List<EmbedResult>,
)
