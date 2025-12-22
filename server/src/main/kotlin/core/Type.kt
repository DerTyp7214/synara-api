package dev.dertyp.core

import dev.dertyp.ApiClient
import dev.dertyp.data.InsertableImage
import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.User
import dev.dertyp.services.ImageService
import dev.dertyp.services.SongService
import dev.dertyp.services.UserPlaylistService
import dev.dertyp.services.metadata.MetadataService
import dev.dertyp.services.tdn.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.context.GlobalContext
import java.util.*

fun Type.getWrapper(metadataService: MetadataService?, user: User, ids: List<String>): IdsWrapper {
    return when (this) {
        Type.SONG -> IdsWrapper.from(this, ids.associateBy { UUID.randomUUID().mostSignificantBits })
        Type.ARTIST -> IdsWrapper.from(this, ids.associateBy { UUID.randomUUID().mostSignificantBits })
        Type.ALBUM -> IdsWrapper.from(this, ids.associateBy { UUID.randomUUID().mostSignificantBits })
        Type.PLAYLIST -> {
            if (metadataService == null) IdsWrapper.from(this, emptyMap())
            else {
                val groups = metadataService.getPlaylistsByIds(ids, true, user).map { playlist ->
                    IdsGroup(
                        playlist.id,
                        playlist.sharedTracks.map {
                            Pair(
                                it.addedAt?.toInstant()?.toEpochMilli()
                                    ?: UUID.randomUUID().mostSignificantBits, it.id
                            )
                        },
                        playlist
                    )
                }
                IdsWrapper(this, groups)
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun Type.download(
    downloadService: DownloadService,
    wrapper: IdsWrapper,
    user: User,
    existingUrls: List<String> = emptyList(),
    downloadStage: MutableList<String>,
    downloadStageMutex: Mutex,
    callback: suspend (List<String>) -> Unit
): Boolean {
    var contentToDownload = false
    when (this) {
        Type.PLAYLIST -> {
            val userPlaylistService = GlobalContext.get().get<UserPlaylistService>()
            val imageService = GlobalContext.get().get<ImageService>()
            val songService = GlobalContext.get().get<SongService>()

            wrapper.idGroups.buffer(2).collect { idGroup ->
                val playlistId = idGroup.metadata?.let { playlist ->
                    if (playlist is MetadataService.FlowPlaylist) {
                        val image = playlist.images.largest
                        val imageBytes = ApiClient.instance.safeGet<ByteArray>(image.url)

                        val imageHash = imageBytes?.let { imageBytes ->
                            val hash = imageBytes.sha256()
                            imageService.createBatch(
                                listOf(
                                    InsertableImage(
                                        data = imageBytes,
                                        imageHash = hash,
                                        origin = image.url
                                    )
                                )
                            )
                            hash
                        }

                        userPlaylistService.getOrAddPlaylist(
                            user, idGroup.id, InsertablePlaylist(
                                name = playlist.name,
                                description = playlist.description,
                                songPaths = emptyList(),
                                imageHash = imageHash,
                                origin = "https://tidal.com/playlist/${playlist.id}"
                            )
                        )
                    } else null
                }

                idGroup.metadata?.let { playlist ->
                    if (playlist is MetadataService.FlowPlaylist) {
                        playlist.sharedTracks
                            .buffer(100)
                            .chunked(20)
                            .map { tracks ->
                                val existingSongs = songService.byTidalTrackIds(
                                    tracks.map { it.id },
                                    user.id
                                )
                                val existingUrls = existingSongs.map { track -> track.originalUrl }

                                val songIds = tracks.map { track ->
                                    track.addedAt?.toInstant()
                                        ?.toEpochMilli() to existingSongs.find { it.originalUrl.endsWith("/${track.id}") }?.id
                                }.filterNotNull()

                                if (playlistId != null) userPlaylistService.addToPlaylist(
                                    playlistId,
                                    songIds
                                ).let { result ->
                                    downloadService.logger.info("Added ${result.size} songs to playlist $playlistId")
                                }

                                tracks.filter { track ->
                                    existingUrls.none { url ->
                                        url.endsWith("/${track.id}")
                                    }
                                }.asFlow()
                            }
                            .flattenConcat()
                            .chunked(20)
                            .collect { trackChunk ->
                                downloadService.addToQueue(
                                    UrlDownloadQueueEntry(
                                        urls = trackChunk
                                            .map { track -> "https://tidal.com/track/${track.id}" }
                                            .toMutableList(),
                                        ids = trackChunk.map { it.id },
                                        byUser = user.id,
                                        maxRetries = trackChunk.size,
                                        type = Type.SONG
                                    ) {
                                        val songs = songService.byTidalTrackIds(trackChunk.map { it.id }, user.id)
                                        val songIds = trackChunk.map { track ->
                                            track.addedAt?.toInstant()
                                                ?.toEpochMilli() to songs.find { it.originalUrl.endsWith("/${track.id}") }?.id
                                        }.filterNotNull()
                                        if (playlistId != null) userPlaylistService.addToPlaylist(
                                            playlistId,
                                            songIds
                                        )
                                    }
                                )
                            }
                    }
                }
            }
        }

        Type.ALBUM, Type.SONG, Type.ARTIST -> {
            downloadStageMutex.withLock {
                downloadStage.addAll(wrapper.filter { (_, id) ->
                    existingUrls.none {
                        it.split("/").contains(id)
                    }
                }.map { it.second }.toList())
            }

            val chunkSize = 20
            while (downloadStageMutex.withLock { downloadStage.size > chunkSize }) {
                contentToDownload = true
                val urls = downloadStageMutex.withLock { downloadStage.splice(0, chunkSize) }
                downloadService.addToQueue(
                    UrlDownloadQueueEntry(
                        urls = urls.map { "https://tidal.com/${wrapper.type.value}/${it}" }.toMutableList(),
                        ids = urls,
                        byUser = user.id,
                        type = wrapper.type
                    ) {
                        callback(urls)
                    })
            }
        }
    }

    return contentToDownload
}