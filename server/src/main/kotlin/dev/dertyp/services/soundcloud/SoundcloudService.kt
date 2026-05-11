package dev.dertyp.services.soundcloud

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
import dev.dertyp.services.download.*
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
import java.util.UUID
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.extension

@OptIn(ExperimentalAtomicApi::class)
class SoundcloudService(
    indexer: IPluginIndexer,
    storageService: IServerStorageService,
    private val lrcLibService: LrcLibService,
    private val musicBrainzService: MusicBrainzService
) : BaseDownloader(indexer, storageService) {
    override val id: String = ID
    override val enabled: Boolean get() = ytdlpPath != null

    private val songService by inject<SongService>()
    private val userPlaylistService by inject<UserPlaylistService>()
    private val downloadService by inject<DownloadService>()

    override val loginCommand: MutableList<String> = mutableListOf()
    override val downloadCommand: MutableList<String> = mutableListOf(
        "yt-dlp", "-x", "--audio-format", "flac", "--no-progress", "--convert-thumbnails", "jpg"
    )
    override val favDownloadCommand: MutableList<String> = mutableListOf()

    companion object {
        val ID = DownloadBackend.Soundcloud.id
    }

    override fun authorizedCheck(result: ProcessExecutionResult): Boolean = true

    override fun canHandle(url: String): Boolean {
        return try {
            val uri = URI(url)
            val host = uri.host?.lowercase() ?: ""
            host == "soundcloud.com" || host.endsWith(".soundcloud.com")
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
        if (host != "soundcloud.com" && !host.endsWith(".soundcloud.com")) return null

        val pathParts = uri.path.trim('/').split('/')
        return when (pathParts.size) {
            1 -> pathParts[0] to Type.ARTIST
            2 -> {
                if (pathParts[1] == "reposts") pathParts[0] to Type.ARTIST
                else uri.path.trim('/') to Type.SONG
            }
            3 -> {
                if (pathParts[1] == "sets") uri.path.trim('/') to Type.PLAYLIST
                else uri.path.trim('/') to Type.SONG
            }
            else -> uri.path.trim('/') to Type.SONG
        }
    }

    override suspend fun getWrapper(type: Type, ids: List<String>, user: User): IdsWrapper {
        if (type == Type.PLAYLIST) {
            val groups = ids.asFlow().map { id ->
                val url = if (id.startsWith("http")) id else "https://soundcloud.com/$id"
                val info = fetchPlaylistInfo(url)
                @Suppress("UNCHECKED_CAST")
                val entries = info?.get("entries") as? List<Map<String, String>> ?: emptyList()

                IdsGroup(
                    id = id,
                    ids = entries.mapNotNull { it["id"] }.withIndex().map { it.index.toLong() to it.value }.asFlow(),
                    metadata = info?.let {
                        IMetadataService.FlowPlaylist(
                            id = id,
                            name = it["title"] as? String ?: "SoundCloud Playlist",
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
    override suspend fun downloadIds(
        ids: List<String>,
        type: Type,
        user: User,
        callback: suspend (List<String>) -> Unit
    ): Pair<Boolean, List<UserSong>> {
        val wrapper = getWrapper(type, ids, user)
        var contentToDownload = false

        when (type) {
            Type.PLAYLIST -> {
                wrapper.idGroups.buffer(2).collect { idGroup ->
                    val playlistId = idGroup.metadata?.let { playlist ->
                        if (playlist is IMetadataService.FlowPlaylist) {
                            val url = if (idGroup.id.startsWith("http")) idGroup.id else "https://soundcloud.com/${idGroup.id}"
                            userPlaylistService.getOrAddPlaylist(
                                user, idGroup.id, InsertablePlaylist(
                                    name = playlist.name,
                                    description = playlist.description,
                                    songPaths = emptyList(),
                                    imageHash = null,
                                    origin = url
                                )
                            )
                        } else null
                    }

                    idGroup.ids.buffer(100).chunked(50).collect { trackChunk ->
                        contentToDownload = true
                        downloadService.addToQueue(
                            UrlDownloadQueueEntry(
                                urls = trackChunk.map { if (it.second.startsWith("http")) it.second else "https://soundcloud.com/${it.second}" }.toMutableList(),
                                ids = trackChunk.map { it.second },
                                byUser = user.id,
                                type = Type.SONG,
                                downloader = DownloadBackend(id)
                            ) {
                                val songs = songService.byOriginalIds(
                                    trackChunk.map { if (it.second.startsWith("http")) it.second else "https://soundcloud.com/${it.second}" },
                                    user.id
                                )
                                val songIds = trackChunk.mapNotNull { entry ->
                                    val songId = songs.find { 
                                        it.originalUrl == entry.second || it.originalUrl.endsWith("/${entry.second}")
                                    }?.id
                                    if (songId != null) entry.first to songId else null
                                }
                                
                                if (playlistId != null) userPlaylistService.addToPlaylist(playlistId, songIds)
                            }
                        )
                    }
                }
            }
            Type.SONG -> {
                val urls = ids.map { if (it.startsWith("http")) it else "https://soundcloud.com/$it" }
                val existingSongs = songService.byOriginalIds(urls, user.id)
                val existingUrls = existingSongs.map { it.originalUrl }
                val toDownload = urls.filter { it !in existingUrls }

                if (toDownload.isNotEmpty()) {
                    contentToDownload = true
                    downloadService.addToQueue(
                        UrlDownloadQueueEntry(
                            urls = toDownload.toMutableList(),
                            ids = ids,
                            byUser = user.id,
                            type = type,
                            downloader = DownloadBackend(id)
                        ) {
                            callback(ids)
                        }
                    )
                }
                return contentToDownload to existingSongs
            }
            else -> {}
        }

        return contentToDownload to emptyList()
    }

    private suspend fun fetchPlaylistInfo(url: String): Map<String, Any>? {
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
                        entryMap["id"] = obj["url"]?.jsonPrimitive?.content ?: obj["id"]?.jsonPrimitive?.content ?: return@let null
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

    override suspend fun executeDownloader(
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

        return executeCommand(cmd, aliveCheck, logger, directory, onLineReceived = onLineReceived)
    }

    override suspend fun downloadContent(
        urls: List<String>,
        maxRetries: Int,
        aliveCheck: suspend () -> Boolean,
        userId: PlatformUUID?,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult {
        loggingIn.waitForChange(false)

        val existingUrls = if (userId != null) songService.byOriginalIds(urls, userId).map { it.originalUrl } else emptyList()

        var finalResult = ProcessExecutionResult.EMPTY
        for (url in urls) {
            if (url in existingUrls) {
                onLiveOutput("Skipping already downloaded song: $url")
                continue
            }

            onLiveOutput("Fetching metadata for: $url")
            val cmd = downloadCommand.toMutableList()

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
                val artist = info["artist"] ?: info["uploader"] ?: ""
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

            val soundcloudId = info?.get("id") ?: UUID.randomUUID().toString()
            val playlistId = info?.get("playlist_id") ?: ""
            val albumDir = finalMbReleaseId ?: playlistId.ifBlank { null } ?: soundcloudId
            val songFile = finalMbId ?: soundcloudId

            if (albumDir.isNotBlank() && songFile.isNotBlank()) {
                cmd.add("-o")
                cmd.add("$albumDir/$songFile.%(ext)s")
            }

            cmd.add("--add-metadata")
            cmd.add("--embed-metadata")
            if (coverData == null) {
                cmd.add("--embed-thumbnail")
            }

            onLiveOutput("Starting download for: $url")
            val (result, _) = collectDownloadedFiles(cmd + url, maxRetries, 0, aliveCheck, userId, onLiveOutput) { paths ->
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
                        audioFile.setOriginalUrl(url)

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
                            audioFile.setCoverImage(
                                data = data,
                                imageUrl = finalCoverUrl,
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
                
                map["thumbnail"] = json["thumbnail"]?.jsonPrimitive?.content ?: ""
                
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
