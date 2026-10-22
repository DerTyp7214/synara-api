package dev.dertyp.services.lyrics.providers

import dev.dertyp.core.HttpClientPriority
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.Service
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.core.component.inject
import java.util.Base64
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

class KuGouProvider : LyricsProvider, Service() {
    private val http by inject<LyricsHttp>()

    override val id = ID
    override val name = "KuGou"
    override val url = "https://www.kugou.com"
    override val usuallyWordSynced = true

    override suspend fun fetch(req: LyricsRequest, priority: HttpClientPriority): ProviderResult {
        val keyword = "${req.artist} - ${req.title}"
        return http.get(SONG_SEARCH, TIMEOUT, priority) {
            parameter("format", "json")
            parameter("keyword", "${req.artist} ${req.title}")
            parameter("page", 1)
            parameter("pagesize", SEARCH_SIZE)
            parameter("showtype", 1)
        }.toResult { songBody ->
            val songs = AppJson.decodeFromString<SongSearch>(songBody.text()).data?.info.orEmpty()
            val song = songs.filter { !it.hash.isNullOrBlank() }.closestByDuration(req.durationSec) { it.duration }
                ?: return@toResult ProviderResult.Miss
            val durationMs = (song.duration ?: req.durationSec)?.let { it * 1000L }
            http.get(LYRIC_SEARCH, TIMEOUT, priority) {
                parameter("ver", 1)
                parameter("man", "yes")
                parameter("client", "mobi")
                parameter("keyword", keyword)
                durationMs?.let { parameter("duration", it) }
                parameter("hash", song.hash)
            }.toResult { candidateBody ->
                val candidates = AppJson.decodeFromString<CandidateSearch>(candidateBody.text()).candidates
                    .filter { !it.id.isNullOrBlank() && !it.accesskey.isNullOrBlank() }
                val candidate = candidates.closestByDuration(req.durationSec) { c -> c.duration?.let { (it / 1000.0).toInt() } }
                    ?: return@toResult ProviderResult.Miss
                download(candidate, priority, song)
            }
        }
    }

    private suspend fun download(candidate: Candidate, priority: HttpClientPriority, song: SongInfo): ProviderResult {
        var lastError: ProviderResult.Error? = null
        for (format in FORMATS) {
            val result = http.get(DOWNLOAD, TIMEOUT, priority) {
                parameter("ver", 1)
                parameter("client", "pc")
                parameter("id", candidate.id)
                parameter("accesskey", candidate.accesskey)
                parameter("fmt", format)
                parameter("charset", "utf8")
            }.toResult { body ->
                val download = AppJson.decodeFromString<Download>(body.text())
                val content = download.content?.takeIf { download.status == 200 && it.isNotBlank() }
                    ?: return@toResult ProviderResult.Miss
                val bytes = Base64.getMimeDecoder().decode(content)
                val payload = if (format == "krc") {
                    LyricsPayload.Krc(bytes)
                } else {
                    LyricsPayload.Lrc(bytes.decodeToString().removePrefix(BOM))
                }
                ProviderResult.Found(
                    payload = payload,
                    trackTitle = candidate.song ?: song.songname,
                    trackArtist = candidate.singer ?: song.singername,
                    trackAlbum = song.albumName?.ifBlank { null },
                    durationSec = candidate.duration?.let { (it / 1000.0).roundToInt() } ?: song.duration,
                    verifiedMetadata = true,
                )
            }
            when (result) {
                is ProviderResult.Found -> return result
                is ProviderResult.Error -> lastError = result
                ProviderResult.Miss -> Unit
            }
        }
        return lastError ?: ProviderResult.Miss
    }

    @Serializable
    private data class SongSearch(val data: SongSearchData? = null)

    @Serializable
    private data class SongSearchData(val info: List<SongInfo> = emptyList())

    @Serializable
    private data class SongInfo(
        val hash: String? = null,
        val songname: String? = null,
        val singername: String? = null,
        val duration: Int? = null,
        @SerialName("album_name") val albumName: String? = null,
    )

    @Serializable
    private data class CandidateSearch(val candidates: List<Candidate> = emptyList())

    @Serializable
    private data class Candidate(
        val id: String? = null,
        val accesskey: String? = null,
        val song: String? = null,
        val singer: String? = null,
        val duration: Long? = null,
    )

    @Serializable
    private data class Download(val status: Int? = null, val content: String? = null)

    companion object {
        const val ID = "kugou"
        const val SONG_SEARCH = "http://mobilecdn.kugou.com/api/v3/search/song"
        const val LYRIC_SEARCH = "https://krcs.kugou.com/search"
        const val DOWNLOAD = "https://lyrics.kugou.com/download"
        private const val SEARCH_SIZE = 10
        private val FORMATS = listOf("krc", "lrc")
        private val TIMEOUT = 4.seconds
    }
}
