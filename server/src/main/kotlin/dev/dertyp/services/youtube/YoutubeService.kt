package dev.dertyp.services.youtube

import dev.dertyp.ApiClient
import dev.dertyp.PlatformUUID
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.cleanTitle
import dev.dertyp.core.safeQueuedGet
import dev.dertyp.core.waitForChange
import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.User
import dev.dertyp.data.UserSong
import dev.dertyp.executeCommand
import dev.dertyp.findInPath
import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.IServerStorageService
import dev.dertyp.plugins.setCoverImage
import dev.dertyp.plugins.setOriginalUrl
import dev.dertyp.services.LrcLibService
import dev.dertyp.services.SongService
import dev.dertyp.services.UserPlaylistService
import dev.dertyp.services.import.*
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.MusicBrainzService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.koin.core.component.inject
import java.io.File
import java.net.URI
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.extension

@OptIn(ExperimentalAtomicApi::class)
open class YoutubeService(
    indexer: IPluginIndexer,
    storageService: IServerStorageService,
    private val youtubeApiService: YoutubeApiService,
    private val lrcLibService: LrcLibService,
    private val musicBrainzService: MusicBrainzService
) : BaseImporter(indexer, storageService) {
    override val id: String = ID
    override val enabled: Boolean get() = ytdlpPath != null

    private val songService by inject<SongService>()
    private val userPlaylistService by inject<UserPlaylistService>()
    private val importService by inject<ImportService>()

    override val loginCommand: MutableList<String> = mutableListOf()
    override val importCommand: MutableList<String> = mutableListOf(
        "yt-dlp", "-x", "--audio-format", "flac", "--no-progress", "--convert-thumbnails", "jpg",
        "--write-auto-subs", "--write-subs", "--sub-langs", "en.*,.*", "--convert-subs", "lrc"
    )
    override val favImportCommand: MutableList<String> = mutableListOf()

    companion object {
        val ID = ImportBackend.Youtube.id
    }

    override fun authorizedCheck(result: ProcessExecutionResult): Boolean = true

    override fun canHandle(url: String): Boolean {
        return try {
            val uri = URI(url)
            val host = uri.host?.lowercase() ?: ""
            host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be"
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun parseUrl(url: String): Pair<String, Type>? {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return null
        }
        val host = uri.host?.lowercase() ?: ""
        val query = uri.query ?: ""

        if (host == "youtu.be") {
            return uri.path.trim('/') to Type.SONG
        }

        if (host == "youtube.com" || host.endsWith(".youtube.com")) {
            val params = query.split("&").associate {
                val parts = it.split("=")
                parts[0] to parts.getOrNull(1)
            }

            params["v"]?.let { return it to Type.SONG }
            params["list"]?.let { return it to Type.PLAYLIST }

            if (uri.path.startsWith("/shorts/")) {
                return uri.path.removePrefix("/shorts/").trim('/') to Type.SONG
            }

            if (uri.path.startsWith("/channel/") || uri.path.startsWith("/user/") || uri.path.startsWith("/@")) {
                return uri.path.trim('/') to Type.ARTIST
            }
        }

        return null
    }

    override suspend fun getWrapper(type: Type, ids: List<String>, user: User): IdsWrapper {
        if (type == Type.PLAYLIST) {
            val groups = ids.asFlow().map { id ->
                val info = fetchPlaylistInfo("https://www.youtube.com/playlist?list=$id")
                @Suppress("UNCHECKED_CAST")
                val entries = info?.get("entries") as? List<Map<String, String>> ?: emptyList()

                IdsGroup(
                    id = id,
                    ids = entries.mapNotNull { it["id"] }.withIndex().map { it.index.toLong() to it.value }.asFlow(),
                    metadata = info?.let {
                        IMetadataService.FlowPlaylist(
                            id = id,
                            name = it["title"] as? String ?: "YouTube Playlist",
                            description = it["description"] as? String ?: "",
                            trackCount = entries.size,
                            tracks = emptyFlow(),
                            images = emptyList()
                        )
                    }
                )
            }
            return IdsWrapper(type, groups)
        }
        return super.getWrapper(type, ids, user)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun importIds(
        ids: List<String>,
        type: Type,
        user: User,
        callback: suspend (List<String>) -> Unit
    ): Pair<Boolean, List<UserSong>> {
        val wrapper = getWrapper(type, ids, user)
        var contentToImport = false

        when (type) {
            Type.PLAYLIST -> {
                wrapper.idGroups.buffer(2).collect { idGroup ->
                    val playlistId = idGroup.metadata?.let { playlist ->
                        if (playlist is IMetadataService.FlowPlaylist) {
                            userPlaylistService.getOrAddPlaylist(
                                user, idGroup.id, InsertablePlaylist(
                                    name = playlist.name,
                                    description = playlist.description,
                                    songPaths = emptyList(),
                                    imageHash = null,
                                    origin = "https://www.youtube.com/playlist?list=${playlist.id}"
                                )
                            )
                        } else null
                    }

                    idGroup.ids.buffer(100).chunked(50).collect { trackChunk ->
                        contentToImport = true
                        importService.addToQueue(
                            UrlImportQueueEntry(
                                urls = trackChunk.map { "https://www.youtube.com/watch?v=${it.second}" }.toMutableList(),
                                ids = trackChunk.map { it.second },
                                byUser = user.id,
                                type = Type.SONG,
                                importer = ImportBackend(id)
                            ) {
                                val songs = songService.byOriginalIds(
                                    trackChunk.map { "https://www.youtube.com/watch?v=${it.second}" },
                                    user.id
                                )
                                val songIds = trackChunk.mapNotNull { entry ->
                                    val songId = songs.find { 
                                        it.originalUrl.endsWith("v=${entry.second}") || it.originalUrl.contains("/${entry.second}")
                                    }?.id
                                    if (songId != null) entry.first to songId else null
                                }
                                
                                if (playlistId != null) userPlaylistService.addToPlaylist(playlistId, songIds)
                            }
                        )
                    }
                }
            }
            Type.SONG, Type.MIX -> {
                val urls = ids.map { "https://www.youtube.com/watch?v=$it" }
                val existingSongs = songService.byOriginalIds(urls, user.id)
                val existingUrls = existingSongs.map { it.originalUrl }
                val toImport = urls.filter { it !in existingUrls }

                if (toImport.isNotEmpty()) {
                    contentToImport = true
                    importService.addToQueue(
                        UrlImportQueueEntry(
                            urls = toImport.toMutableList(),
                            ids = ids,
                            byUser = user.id,
                            type = type,
                            importer = ImportBackend(id)
                        ) {
                            callback(ids)
                        }
                    )
                }
                return contentToImport to existingSongs
            }
            else -> {}
        }

        return contentToImport to emptyList()
    }

    private suspend fun fetchPlaylistInfo(url: String): Map<String, Any>? {
        if (youtubeApiService.enabled) {
            val parsed = parseUrl(url)
            if (parsed != null && parsed.second == Type.PLAYLIST) {
                val playlist = youtubeApiService.getPlaylistMetadata(parsed.first)
                val items = youtubeApiService.getPlaylistItems(parsed.first)
                
                if (playlist != null || items.isNotEmpty()) {
                    val map = mutableMapOf<String, Any>()
                    map["id"] = parsed.first
                    map["title"] = playlist?.snippet?.title ?: "YouTube Playlist"
                    map["description"] = playlist?.snippet?.description ?: ""
                    
                    val entries = items.map { item ->
                        val entryMap = mutableMapOf<String, String>()
                        entryMap["id"] = item.contentDetails?.videoId ?: item.snippet?.resourceId?.videoId ?: ""
                        entryMap["title"] = item.snippet?.title ?: ""
                        entryMap
                    }
                    map["entries"] = entries
                    return map
                }
            }
        }

        if (ytdlpPath == null) return null
        val cmd = listOf(ytdlpPath, "-J", "--flat-playlist", url)
        val result = executeCommand(cmd, { true }, logger) {}
        if (result.exitCode == 0) {
            try {
                val jsonStartIndex = result.fullOutput.indexOf("{")
                if (jsonStartIndex == -1) return null
                val jsonString = result.fullOutput.substring(jsonStartIndex)
                
                val json = ApplicationScope.json.parseToJsonElement(jsonString).jsonObject
                val map = mutableMapOf<String, Any>()
                map["id"] = json["id"]?.jsonPrimitive?.content ?: ""
                map["title"] = json["title"]?.jsonPrimitive?.content ?: ""
                map["description"] = json["description"]?.jsonPrimitive?.content ?: ""
                
                val entries = json["entries"]?.jsonArray?.mapNotNull { 
                    it.jsonObject.let { obj ->
                        val entryMap = mutableMapOf<String, String>()
                        entryMap["id"] = obj["id"]?.jsonPrimitive?.content ?: return@let null
                        entryMap["title"] = obj["title"]?.jsonPrimitive?.content ?: ""
                        entryMap
                    }
                } ?: emptyList()
                map["entries"] = entries
                
                return map
            } catch (e: Exception) {
                logger.error("Failed to parse yt-dlp playlist output", e)
            }
        }
        return null
    }

    private val ytdlpPath = findInPath("yt-dlp")

    override suspend fun executeImporter(
        command: Collection<String>,
        aliveCheck: suspend () -> Boolean,
        directory: File?,
        onLineReceived: suspend (String) -> Unit
    ): ProcessExecutionResult {
        val cmd = command.toMutableList()
        if (cmd.isEmpty() || cmd[0] != "yt-dlp") {
            return ProcessExecutionResult(-1, "Invalid command", "")
        }

        if (ytdlpPath == null) {
            return ProcessExecutionResult(-1, "Error: The yt-dlp path does not exist.", "")
        }

        cmd[0] = ytdlpPath

        directory?.mkdirs()

        return executeCommand(cmd, aliveCheck, logger, directory, onLineReceived = onLineReceived)
    }

    override suspend fun importContent(
        urls: List<String>,
        maxRetries: Int,
        aliveCheck: suspend () -> Boolean,
        userId: PlatformUUID?,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult {
        loggingIn.waitForChange(false)

        val normalizedUrls = urls.map { url ->
            val parsed = parseUrl(url)
            if (parsed != null && parsed.second == Type.SONG) "https://www.youtube.com/watch?v=${parsed.first}"
            else url
        }

        val existingUrls = if (userId != null) songService.byOriginalIds(normalizedUrls, userId).map { it.originalUrl } else emptyList()

        var finalResult = ProcessExecutionResult.EMPTY
        for ((index, url) in urls.withIndex()) {
            val normalized = normalizedUrls[index]
            if (normalized in existingUrls) {
                onLiveOutput("Skipping already imported song: $url")
                continue
            }

            onLiveOutput("Fetching metadata for: $url")
            val cmd = importCommand.toMutableList()

            val info = fetchInfo(url, aliveCheck)
            var coverData: ByteArray? = null
            var finalCoverUrl: String? = null
            var finalTitle: String? = null
            var finalArtist: String? = null
            var finalAlbum: String? = null
            var finalDate: String? = null
            var finalMbId: String? = null
            var finalMbReleaseId: String? = null

            if (info != null) {
                val title = info["track"] ?: info["title"] ?: ""
                val artist = info["artist"] ?: info["uploader"]?.removeSuffix("- Topic")?.trim() ?: ""
                val album = info["album"] ?: ""

                val mbRecording = musicBrainzService.searchRecordingMb(title, artist.split(",").map(String::trim))
                if (mbRecording != null) {
                    onLiveOutput("Matched MusicBrainz Recording: ${mbRecording.title}")
                    finalTitle = mbRecording.title ?: title
                    finalArtist = mbRecording.artistCredit?.joinToString(indexer.artistDelimiter) { it.name ?: it.artist?.name ?: "" } ?: artist
                    val firstRelease = mbRecording.releases?.firstOrNull { it.title?.cleanTitle()?.equals(album.cleanTitle(), true) == true } ?: mbRecording.releases?.firstOrNull()
                    finalAlbum = firstRelease?.title
                    finalDate = firstRelease?.date
                    finalMbId = mbRecording.id.toString()
                    finalMbReleaseId = firstRelease?.id?.toString()

                    val coverUrl = when {
                        firstRelease?.id != null -> "https://coverartarchive.org/release/${firstRelease.id}/front"
                        else -> null
                    }
                    finalCoverUrl = coverUrl

                    cmd.add("--postprocessor-args")
                    val metadataFields = mutableListOf<String>()
                    metadataFields.add("title='$finalTitle'")
                    metadataFields.add("artist='$finalArtist'")
                    finalAlbum?.let { metadataFields.add("album='$it'") }
                    finalDate?.let { metadataFields.add("date='$it'") }

                    cmd.add("ffmpeg:" + metadataFields.joinToString(" ") { "-metadata $it" })
                } else {
                    finalTitle = title
                    finalArtist = artist.split(",").joinToString(indexer.artistDelimiter, transform = String::trim)
                    finalCoverUrl = info["thumbnail"]
                }
            }

            if (finalCoverUrl != null) {
                coverData = try {
                    ApiClient.instance.safeQueuedGet<ByteArray>(finalCoverUrl)
                } catch (_: Exception) {
                    null
                }
            }

            val youtubeId = info?.get("id") ?: ""
            val playlistId = info?.get("playlist_id") ?: ""
            val albumDir = finalMbReleaseId ?: playlistId.ifBlank { null } ?: youtubeId
            val songFile = finalMbId ?: youtubeId

            if (albumDir.isNotBlank() && songFile.isNotBlank()) {
                cmd.add("-o")
                cmd.add("$albumDir/$songFile.%(ext)s")
            }

            cmd.add("--add-metadata")
            cmd.add("--embed-metadata")
            if (coverData == null) {
                cmd.add("--embed-thumbnail")
            }

            onLiveOutput("Starting import for: $url")
            val (result, _) = collectImportedFiles(cmd + url, maxRetries, 0, aliveCheck, userId, onLiveOutput) { paths ->
                paths.filter { it.extension == "flac" }.forEach { path ->
                    onLiveOutput("Post-processing: ${path.absolutePathString()}")
                    try {
                        val audioFile = AudioFileIO.read(path.toFile())
                        val tag = audioFile.tag
                        
                        onLiveOutput("Tagged: $finalTitle - $finalArtist")
                        finalTitle?.let { tag.setField(FieldKey.TITLE, it) }
                        finalArtist?.let { tag.setField(FieldKey.ARTIST, it) }
                        finalAlbum?.let { tag.setField(FieldKey.ALBUM, it) }
                        finalDate?.let { tag.setField(FieldKey.YEAR, it) }
                        finalMbId?.let { tag.setField(FieldKey.MUSICBRAINZ_TRACK_ID, it) }
                        finalMbReleaseId?.let { tag.setField(FieldKey.MUSICBRAINZ_RELEASEID, it) }
                        audioFile.setOriginalUrl(normalized)

                        if (finalArtist != null && finalTitle != null) {
                            val duration = (audioFile.audioHeader.preciseTrackLength * 1000).toLong()
                            val lyricsResponse = lrcLibService.getLyrics(finalArtist, finalTitle, finalAlbum, duration)
                            lyricsResponse?.syncedLyrics?.let { synced ->
                                if (synced.isNotBlank()) {
                                    tag.setField(FieldKey.LYRICS, synced)
                                }
                            }
                        }

                        val data = coverData
                        if (data != null) {
                            val isYoutubeThumbnail = finalCoverUrl == info?.get("thumbnail")
                            audioFile.setCoverImage(
                                data = data,
                                imageUrl = finalCoverUrl,
                                width = if (isYoutubeThumbnail) info?.get("width")?.toIntOrNull() else null,
                                height = if (isYoutubeThumbnail) info?.get("height")?.toIntOrNull() else null
                            )
                        }
                        audioFile.commit()
                    } catch (e: Exception) {
                        logger.error("Failed to set metadata for $path", e)
                    }
                }
            }
            finalResult = result
        }

        return finalResult
    }

    private suspend fun fetchInfo(url: String, aliveCheck: suspend () -> Boolean): Map<String, String>? {
        val parsed = parseUrl(url)
        val videoId = if (parsed?.second == Type.SONG) parsed.first else null

        if (youtubeApiService.enabled && videoId != null) {
            val metadata = youtubeApiService.getVideoMetadata(videoId)
            if (metadata != null) return metadata
        }

        if (ytdlpPath == null) return null
        val cmd = listOf(ytdlpPath, "-J", "--simulate", url)
        val result = executeCommand(cmd, aliveCheck, logger) {}
        if (result.exitCode == 0) {
            try {
                val jsonStartIndex = result.fullOutput.indexOf("{")
                if (jsonStartIndex == -1) return null
                val jsonString = result.fullOutput.substring(jsonStartIndex)

                val json = ApplicationScope.json.parseToJsonElement(jsonString).jsonObject
                val map = mutableMapOf<String, String>()
                
                map["id"] = json["id"]?.jsonPrimitive?.content ?: ""
                map["title"] = json["title"]?.jsonPrimitive?.content ?: ""
                map["uploader"] = json["uploader"]?.jsonPrimitive?.content ?: ""
                map["playlist_id"] = json["playlist_id"]?.jsonPrimitive?.content ?: ""
                
                val thumbnails = json["thumbnails"]?.jsonArray
                val squareThumbnail = thumbnails?.mapNotNull { it.jsonObject }?.find { 
                    val w = it["width"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val h = it["height"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    w == h && w > 0
                }

                val bestThumbnail = squareThumbnail ?: thumbnails?.mapNotNull { it.jsonObject }?.find { 
                    it["id"]?.jsonPrimitive?.content == "maxresdefault" 
                } ?: thumbnails?.mapNotNull { it.jsonObject }?.maxByOrNull { 
                    it["width"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 
                }

                bestThumbnail?.let {
                    map["thumbnail"] = it["url"]?.jsonPrimitive?.content ?: ""
                    it["width"]?.jsonPrimitive?.content?.let { w -> map["width"] = w }
                    it["height"]?.jsonPrimitive?.content?.let { h -> map["height"] = h }
                } ?: run {
                    map["thumbnail"] = json["thumbnail"]?.jsonPrimitive?.content ?: ""
                }

                if (videoId != null && (map["width"] != map["height"])) {
                    youtubeApiService.getYoutubeMusicCover(videoId)?.let { 
                        map["thumbnail"] = it
                        map.remove("width")
                        map.remove("height")
                    }
                }
                
                json["track"]?.jsonPrimitive?.content?.let { if (it != "NA") map["track"] = it }
                json["artist"]?.jsonPrimitive?.content?.let { if (it != "NA") map["artist"] = it }
                json["album"]?.jsonPrimitive?.content?.let { if (it != "NA") map["album"] = it }
                
                return map
            } catch (e: Exception) {
                logger.error("Failed to parse yt-dlp json output", e)
            }
        }
        return null
    }
}
