package dev.dertyp.services.lyrics.providers

import dev.dertyp.core.HttpClientPriority
import dev.dertyp.services.LrcLibResponse
import dev.dertyp.services.LrcLibService
import dev.dertyp.services.Service
import kotlinx.coroutines.CancellationException
import org.koin.core.component.inject
import kotlin.math.abs
import kotlin.math.roundToInt

class LrcLibLyricsProvider : LyricsProvider, Service() {
    private val lrcLib by inject<LrcLibService>()

    override val id = ID
    override val name = "LRCLIB"
    override val url = "https://lrclib.net"
    override val usuallyWordSynced = false

    override suspend fun fetch(req: LyricsRequest, priority: HttpClientPriority): ProviderResult {
        val results = try {
            lrcLib.search(req.artist, req.title, priority = priority)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ProviderResult.Error(e)
        } ?: return ProviderResult.Miss
        val usable = results.filter { it.instrumental || !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }
        val match = usable.minWithOrNull(
            compareBy<LrcLibResponse>({ entry -> req.durationSec?.let { abs(entry.duration.roundToInt() - it) } ?: 0 })
                .thenBy { if (it.syncedLyrics.isNullOrBlank()) 1 else 0 }
        ) ?: return ProviderResult.Miss
        val synced = match.syncedLyrics
        val plain = match.plainLyrics
        val payload = when {
            !synced.isNullOrBlank() -> LyricsPayload.Lrc(synced)
            match.instrumental -> LyricsPayload.Instrumental
            !plain.isNullOrBlank() -> LyricsPayload.Plain(plain)
            else -> return ProviderResult.Miss
        }
        return ProviderResult.Found(
            payload = payload,
            trackTitle = match.trackName,
            trackArtist = match.artistName,
            trackAlbum = match.albumName.ifBlank { null },
            durationSec = match.duration.roundToInt(),
            verifiedMetadata = true,
        )
    }

    companion object {
        const val ID = "lrclib"
    }
}
