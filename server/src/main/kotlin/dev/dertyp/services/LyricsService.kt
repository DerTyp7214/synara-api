package dev.dertyp.services

import dev.dertyp.ApiClient
import dev.dertyp.PlatformUUID
import dev.dertyp.db.SyncedLyricsTable
import dev.dertyp.dbQuery
import dev.dertyp.serializers.AppCbor
import dev.dertyp.services.models.SyncedLyrics
import dev.dertyp.services.schedule.LyricsSyncWorker
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import org.koin.core.component.inject
import java.io.File

@OptIn(ExperimentalSerializationApi::class)
class LyricsService : ILyricsService, Service() {
    private val environment by inject<ApplicationEnvironment>()
    private val songService by inject<SongService>()
    private val lyricsSyncWorker by inject<LyricsSyncWorker>()

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    private val transcriberUrl: String
        get() = environment.config.propertyOrNull("transcriber.url")?.getString() ?: "http://localhost:8000"

    override suspend fun getSyncedLyrics(songId: PlatformUUID): SyncedLyrics? = dbQuery {
        SyncedLyricsTable.select(SyncedLyricsTable.content)
            .where { SyncedLyricsTable.songId eq songId }
            .singleOrNull()?.let {
                it[SyncedLyricsTable.content]?.let { bytes -> AppCbor.decodeFromByteArray<SyncedLyrics>(bytes) }
            }
    }

    override suspend fun transcribeLyrics(songId: PlatformUUID, lyrics: String?): SyncedLyrics? {
        val song = songService.byId(songId) ?: return null
        val file = File(song.path)
        if (!file.exists()) {
            logger.error("File not found for transcription: ${file.absolutePath}")
            return null
        }

        var rawLyrics: String? = lyrics
        if (rawLyrics == null) {
            rawLyrics = dbQuery {
                SyncedLyricsTable.select(SyncedLyricsTable.rawLyrics)
                    .where { SyncedLyricsTable.songId eq songId }
                    .singleOrNull()?.get(SyncedLyricsTable.rawLyrics)
            }
        }

        if (rawLyrics.isNullOrBlank()) {
            rawLyrics = song.lyrics
        }

        if (rawLyrics.isBlank()) {
            val lyricsSearch by inject<LyricsSearch>()
            val artistName = song.artists.firstOrNull()?.name ?: ""
            rawLyrics = try {
                lyricsSearch.searchLyrics(artistName, song.title, syncedOnly = false).joinToString("\n")
            } catch (_: Exception) {
                ""
            }
        }

        val response = try {
            ApiClient.instance.post("$transcriberUrl/transcribe") {
                contentType(ContentType.Application.Json)
                setBody(mapOf(
                    "path" to file.absolutePath,
                    "artist" to (song.artists.firstOrNull()?.name ?: ""),
                    "title" to song.title,
                    "lyrics" to rawLyrics.ifBlank { null }
                ))
            }
        } catch (e: Exception) {
            logger.error("Failed to connect to transcriber: ${e.message}")
            return null
        }

        if (response.status != HttpStatusCode.OK) {
            logger.error("Transcriber returned error: ${response.status} (${response.body<String>()})")
            return null
        }

        val syncedLyrics = response.body<SyncedLyrics>()

        dbQuery {
            SyncedLyricsTable.upsert(SyncedLyricsTable.songId) {
                it[SyncedLyricsTable.songId] = songId
                it[SyncedLyricsTable.content] = AppCbor.encodeToByteArray(syncedLyrics)
                it[SyncedLyricsTable.rawLyrics] = rawLyrics
                it[SyncedLyricsTable.provider] = "whisperx_v1"
            }
        }

        return syncedLyrics
    }

    override suspend fun startSyncWorker(): Boolean {
        serviceScope.launch {
            lyricsSyncWorker.run()
        }
        return true
    }

    fun isConfigured(): Boolean {
        return environment.config.propertyOrNull("transcriber.url")?.getString() != null
    }
}
