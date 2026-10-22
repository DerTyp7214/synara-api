package dev.dertyp.services.lyrics.providers

import dev.dertyp.core.HttpClientPriority
import dev.dertyp.services.Service
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SidecarLyricsProvider : LyricsProvider, Service() {
    override val id = ID
    override val name = "Lyrics file"
    override val url: String? = null
    override val usuallyWordSynced = false

    override suspend fun fetch(req: LyricsRequest, priority: HttpClientPriority): ProviderResult {
        val path = req.songPath ?: return ProviderResult.Miss
        return withContext(Dispatchers.IO) {
            try {
                read(File(path))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ProviderResult.Error(e)
            }
        }
    }

    private fun read(audio: File): ProviderResult {
        val dir = audio.absoluteFile.parentFile ?: return ProviderResult.Miss
        for ((suffix, payload) in SIDECARS) {
            val file = File(dir, audio.nameWithoutExtension + suffix)
            if (!file.isFile || file.length() > MAX_BYTES) continue
            val text = file.readBytes().decodeToString().removePrefix(BOM)
            if (text.isBlank()) continue
            return ProviderResult.Found(payload(text))
        }
        return ProviderResult.Miss
    }

    companion object {
        const val ID = "sidecar"
        const val MAX_BYTES = 256L * 1024
        private val SIDECARS: List<Pair<String, (String) -> LyricsPayload>> = listOf(
            ".lyricsfile.yaml" to LyricsPayload::LyricsFile,
            ".ttml" to LyricsPayload::Ttml,
            ".lrc" to LyricsPayload::Lrc,
        )
    }
}
