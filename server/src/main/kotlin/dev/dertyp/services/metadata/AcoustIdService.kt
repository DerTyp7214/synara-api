package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.PlatformUUID
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.core.RetryOnError
import dev.dertyp.core.RetryPolicy
import dev.dertyp.core.cleanTitle
import dev.dertyp.core.retryingGet
import dev.dertyp.data.BaseSong
import dev.dertyp.db.SongAcoustIdTable
import dev.dertyp.core.db.dbQuery
import dev.dertyp.server.BuildConfig
import dev.dertyp.services.Service
import dev.dertyp.toPlatformUUID
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

@Serializable
data class AcoustIdLookupResponse(
    val status: String? = null,
    val results: List<AcoustIdResult> = emptyList(),
    val error: AcoustIdError? = null,
)

@Serializable
data class AcoustIdError(val code: Int? = null, val message: String? = null)

@Serializable
data class AcoustIdResult(
    val id: String,
    val score: Double = 0.0,
    val recordings: List<AcoustIdRecording> = emptyList(),
)

@Serializable
data class AcoustIdRecording(
    val id: String,
    val title: String? = null,
    val duration: Double? = null,
    val artists: List<AcoustIdArtist> = emptyList(),
    val releasegroups: List<AcoustIdReleaseGroup> = emptyList(),
)

@Serializable
data class AcoustIdArtist(val id: String? = null, val name: String? = null)

@Serializable
data class AcoustIdReleaseGroup(val id: String? = null, val title: String? = null, val type: String? = null)

data class AcoustIdMatch(val acoustId: String, val recordingId: PlatformUUID, val score: Double)

class AcoustIdService(
    private val fingerprintService: AcoustIdFingerprintService,
    private val credentials: AcoustIdCredentialSource,
) : Service() {
    private val missingKeyLogged = AtomicBoolean(false)

    companion object {
        const val LOOKUP_URL = "https://api.acoustid.org/v2/lookup"
        const val MIN_SCORE = 0.85
        const val DURATION_TOLERANCE_SECONDS = 3.0
        val NEGATIVE_RECHECK = 30.days
        val RETRY_POLICY = RetryPolicy(maxAttempts = 1, onError = RetryOnError.GIVE_UP)

        fun selectRecording(song: BaseSong, results: List<AcoustIdResult>): AcoustIdMatch? {
            val candidates = results
                .filter { it.score >= MIN_SCORE }
                .flatMap { result -> result.recordings.map { result to it } }
                .filter { (_, recording) -> recording.id.toRecordingId() != null }
            if (candidates.isEmpty()) return null

            val byRecording = candidates.groupBy { (_, recording) -> recording.id }.values.map { group ->
                val best = group.maxBy { (result, _) -> result.score }
                val merged = group.map { it.second }.reduce { acc, recording ->
                    acc.copy(
                        title = acc.title ?: recording.title,
                        duration = acc.duration ?: recording.duration,
                        artists = (acc.artists + recording.artists).distinct(),
                        releasegroups = (acc.releasegroups + recording.releasegroups).distinct(),
                    )
                }
                best.first to merged
            }
            if (byRecording.size == 1) return byRecording.single().toMatch()

            val songTitle = song.title.cleanTitle().normalizedKey()
            val songArtists = song.artists.map { it.name.normalizedKey() }.filter { it.isNotEmpty() }.toSet()
            val songAlbum = song.album?.name?.normalizedKey()?.takeIf { it.isNotEmpty() }
            val songSeconds = song.duration / 1000.0

            val narrowers: List<(AcoustIdRecording) -> Boolean> = listOf(
                { recording ->
                    recording.title?.cleanTitle()?.normalizedKey() == songTitle &&
                        recording.artists.any { it.name?.normalizedKey() in songArtists }
                },
                { recording ->
                    songAlbum != null && recording.releasegroups.any { it.title?.normalizedKey() == songAlbum }
                },
                { recording ->
                    song.duration > 0 && recording.duration?.let { abs(it - songSeconds) <= DURATION_TOLERANCE_SECONDS } == true
                },
            )

            var remaining = byRecording
            for (narrower in narrowers) {
                val kept = remaining.filter { (_, recording) -> narrower(recording) }
                if (kept.size == 1) return kept.single().toMatch()
                if (kept.isNotEmpty()) remaining = kept
            }
            return null
        }

        private fun Pair<AcoustIdResult, AcoustIdRecording>.toMatch(): AcoustIdMatch? =
            second.id.toRecordingId()?.let { AcoustIdMatch(first.id, it, first.score) }

        private fun String.toRecordingId(): PlatformUUID? = try {
            toPlatformUUID()
        } catch (_: Exception) {
            null
        }

        private fun String.normalizedKey(): String =
            Normalizer.normalize(lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
    }

    suspend fun matchRecording(song: BaseSong, priority: HttpClientPriority = HttpClientPriority.NORMAL): PlatformUUID? {
        if (song.path.isBlank()) return null
        val now = Clock.System.now().toEpochMilliseconds()
        val cached = dbQuery {
            SongAcoustIdTable.selectAll()
                .where { SongAcoustIdTable.songId eq song.id }
                .singleOrNull()
        }
        if (cached != null && cached[SongAcoustIdTable.acoustId] == null &&
            now - cached[SongAcoustIdTable.lastCheck] < NEGATIVE_RECHECK.inWholeMilliseconds
        ) return null

        val fingerprint = cached?.let { Fingerprint(it[SongAcoustIdTable.duration], it[SongAcoustIdTable.fingerprint]) }
            ?: fingerprintService.fingerprint(song.path)
            ?: return null

        val response = lookup(fingerprint, priority)
        if (response == null) {
            if (cached == null) store(song.id, fingerprint, null, now = 0L)
            return null
        }
        val match = selectRecording(song, response.results)
        store(song.id, fingerprint, match, now)
        return match?.recordingId
    }

    suspend fun lookup(fingerprint: Fingerprint, priority: HttpClientPriority = HttpClientPriority.NORMAL): AcoustIdLookupResponse? {
        val apiKey = credentials.current()
        if (apiKey == null) {
            if (missingKeyLogged.compareAndSet(false, true)) {
                logger.warn("No AcoustID API key is configured, set ACOUSTID_API_KEY or store one in the admin settings to match songs by fingerprint")
            }
            return null
        }
        val body = retryingGet(
            policy = RETRY_POLICY,
            label = "AcoustID lookup",
            logger = logger,
            request = {
                ApiClient.queueInstance.enqueue(LOOKUP_URL, priority) {
                    parameter("client", apiKey)
                    parameter("meta", "recordings releasegroups")
                    parameter("duration", fingerprint.duration)
                    parameter("fingerprint", fingerprint.fingerprint)
                    header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
                }
            },
            onGiveUp = { response, _ -> logger.warn("AcoustID lookup failed with HTTP ${response.status.value}") },
        ) { it.body<AcoustIdLookupResponse>() } ?: return null
        if (body.status != "ok") {
            logger.warn("AcoustID lookup returned ${body.status}: ${body.error?.message}")
            return null
        }
        return body
    }

    private suspend fun store(songId: PlatformUUID, fingerprint: Fingerprint, match: AcoustIdMatch?, now: Long) = dbQuery {
        SongAcoustIdTable.upsert(SongAcoustIdTable.songId) {
            it[SongAcoustIdTable.songId] = songId
            it[SongAcoustIdTable.fingerprint] = fingerprint.fingerprint
            it[duration] = fingerprint.duration
            it[acoustId] = match?.acoustId
            it[score] = match?.score
            it[lastCheck] = now
        }
    }
}
