package dev.dertyp.services

import dev.dertyp.ApiClient
import dev.dertyp.core.safeQueuedGet
import dev.dertyp.server.BuildConfig
import io.ktor.client.request.header
import io.ktor.client.request.parameter

class LrcLibService : ILrcLibService, Service() {
    override suspend fun getLyrics(artist: String, title: String, album: String?, duration: Long?): LrcLibResponse? {
        return try {
            ApiClient.instance.safeQueuedGet<LrcLibResponse>("https://lrclib.net/api/get") {
                header("User-Agent", "Synara/${BuildConfig.VERSION} (https://github.com/dertyp7214/synara_api)")
                parameter("artist_name", artist)
                parameter("track_name", title)
                if (album != null) parameter("album_name", album)
                if (duration != null) parameter("duration", duration / 1000.0)
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch lyrics from LrcLib: ${e.message}")
            null
        }
    }
}
