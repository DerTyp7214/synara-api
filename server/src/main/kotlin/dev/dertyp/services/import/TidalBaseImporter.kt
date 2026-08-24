package dev.dertyp.services.import

import dev.dertyp.ApiClient
import dev.dertyp.PlatformUUID
import dev.dertyp.core.*
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.getISOFromDate
import dev.dertyp.plugins.*
import dev.dertyp.services.*
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.IMusicBrainzService
import dev.dertyp.services.metadata.MetadataService
import dev.dertyp.utils.parsers.ParserFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.component.get
import org.koin.core.component.inject
import java.util.UUID
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.nameWithoutExtension

private data class TrackMetadata(
    val url: String,
    val tidalId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val date: String?,
    val mbId: String?,
    val mbReleaseId: String?,
    val coverUrl: String?,
    val coverData: ByteArray?,
    val originalTitle: String,
    val originalArtists: List<String>,
    val animatedCoverUrl: String? = null,
    val tidalAlbumId: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TrackMetadata

        if (url != other.url) return false
        if (tidalId != other.tidalId) return false
        if (title != other.title) return false
        if (artist != other.artist) return false
        if (album != other.album) return false
        if (date != other.date) return false
        if (mbId != other.mbId) return false
        if (mbReleaseId != other.mbReleaseId) return false
        if (coverUrl != other.coverUrl) return false
        if (coverData != null) {
            if (other.coverData == null) return false
            if (!coverData.contentEquals(other.coverData)) return false
        } else if (other.coverData != null) return false
        if (originalTitle != other.originalTitle) return false
        if (originalArtists != other.originalArtists) return false

        return true
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + tidalId.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (date?.hashCode() ?: 0)
        result = 31 * result + (mbId?.hashCode() ?: 0)
        result = 31 * result + (mbReleaseId?.hashCode() ?: 0)
        result = 31 * result + (coverUrl?.hashCode() ?: 0)
        result = 31 * result + (coverData?.contentHashCode() ?: 0)
        result = 31 * result + originalTitle.hashCode()
        result = 31 * result + originalArtists.hashCode()
        return result
    }
}

@OptIn(ExperimentalAtomicApi::class)
abstract class TidalBaseImporter(
    indexer: IPluginIndexer,
    storageService: IServerStorageService
) : BaseImporter(indexer, storageService) {
    override val metadataType = IMetadataService.MetadataType.tidal

    private val songService by inject<SongService>()
    private val userPlaylistService by inject<UserPlaylistService>()
    private val imageService by inject<ImageService>()
    private val animatedImageService by inject<AnimatedImageService>()
    private val importService by inject<ImportService>()
    private val lrcLibService by inject<ILrcLibService>()
    private val musicBrainzService by inject<IMusicBrainzService>()

    override suspend fun importContent(
        urls: List<String>,
        maxRetries: Int,
        aliveCheck: suspend () -> Boolean,
        userId: PlatformUUID?,
        metadata: IMetadataService.BaseMetadata?,
        onLiveOutput: suspend (String) -> Unit
    ): ProcessExecutionResult = coroutineScope {
        loggingIn.waitForChange(false)

        val metadataService = MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, get())

        onLiveOutput("Fetching metadata for ${urls.size} tracks...")

        val tidalTracks = urls.map { url ->
            async {
                val parsed = parseUrl(url) ?: return@async null
                if (parsed.second != Type.SONG) return@async null
                val tidalTrack = metadataService.getTrackById(parsed.first, HttpClientPriority.HIGH) ?: return@async null
                url to tidalTrack
            }
        }.awaitAll().filterNotNull().toMap()

        val trackMetadataMap = mutableMapOf<String, TrackMetadata>()
        val tracksByAlbum = tidalTracks.values.groupBy { it.albumId ?: it.albumTitle ?: "unknown" }

        onLiveOutput("Grouping ${tidalTracks.size} tracks into ${tracksByAlbum.size} album(s) for MusicBrainz matching...")

        val coverDownloads = mutableMapOf<String, Deferred<ByteArray?>>()
        val animatedCoverDownloads = mutableMapOf<String, Deferred<ByteArray?>>()

        tracksByAlbum.forEach { (albumId, tracks) ->
            val firstTrack = tracks.first()
            val albumTitle = firstTrack.albumTitle
            val albumArtists = firstTrack.artists

            var mbRelease: MusicBrainzRelease? = null

            if (albumId != "unknown") {
                try {
                    val tidalAlbum = metadataService.getAlbumsByIds(listOf(albumId)).firstOrNull()
                    val barcode = tidalAlbum?.barcode
                    if (barcode != null && barcode.length >= 8 && barcode.uppercase() != "BARCODE") {
                        onLiveOutput("Searching MusicBrainz for album by barcode: $barcode")
                        mbRelease = musicBrainzService.searchReleaseByBarcode(barcode, albumArtists)
                        if (mbRelease != null) {
                            onLiveOutput("Matched MusicBrainz Release by barcode: ${mbRelease.title}")
                            val fullRelease = musicBrainzService.getRelease(mbRelease.id)
                            if (fullRelease != null) mbRelease = fullRelease
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to fetch Tidal album metadata for barcode matching: $albumId", e)
                }
            }

            if (mbRelease == null && metadata is IMetadataService.Album) {
                val mbid = try { UUID.fromString(metadata.id) } catch (_: Exception) { null }
                if (mbid != null) {
                    onLiveOutput("Using provided MusicBrainz metadata for album: ${metadata.title}")
                    mbRelease = musicBrainzService.getRelease(mbid)
                }
            }
            
            if (mbRelease == null && !albumTitle.isNullOrBlank()) {
                onLiveOutput("Searching MusicBrainz for album: $albumTitle")
                mbRelease = musicBrainzService.searchRelease(albumTitle, albumArtists)
                if (mbRelease != null) {
                    onLiveOutput("Matched MusicBrainz Release: ${mbRelease.title}")
                    val fullRelease = musicBrainzService.getRelease(mbRelease.id)
                    if (fullRelease != null) mbRelease = fullRelease
                } else {
                    onLiveOutput("No direct MusicBrainz match found for album: $albumTitle")
                }
            }

            tracks.forEach { tidalTrack ->
                val url = tidalTracks.entries.find { it.value.id == tidalTrack.id }?.key ?: return@forEach

                onLiveOutput("Resolving metadata for: ${tidalTrack.title}")

                var finalTitle = tidalTrack.title
                var finalArtist = tidalTrack.artists.joinToString(indexer.artistDelimiter)
                var finalAlbum = tidalTrack.albumTitle
                var finalDate: String? = null
                var finalMbId: String? = null
                var finalMbReleaseId: String? = null
                var finalCoverUrl = tidalTrack.images.largest.url

                if (metadata is IMetadataService.Track) {
                    val mbid = try { UUID.fromString(metadata.id) } catch (_: Exception) { null }
                    if (mbid != null) {
                        onLiveOutput("Using provided MusicBrainz metadata for track: ${metadata.title}")
                        finalMbId = metadata.id
                        finalTitle = metadata.title
                        finalArtist = metadata.artists.joinToString(indexer.artistDelimiter)
                        finalAlbum = metadata.albumTitle
                        finalMbReleaseId = metadata.albumId
                    }
                }

                val mbTrack = mbRelease?.media?.flatMap { it.tracks ?: emptyList() }?.find {
                    (it.recording?.isrcs?.contains(tidalTrack.isrc) == true) ||
                            it.title?.cleanTitle()?.equals(tidalTrack.title.cleanTitle(), true) == true
                }

                if (mbTrack != null) {
                    onLiveOutput("Found track '${tidalTrack.title}' in matched release '${mbRelease.title}'")
                    finalTitle = mbTrack.title ?: finalTitle
                    finalArtist = mbTrack.recording?.artistCredit?.joinToString(indexer.artistDelimiter) { it.name ?: it.artist?.name ?: "" } ?: finalArtist
                    finalAlbum = mbRelease.title ?: finalAlbum
                    finalDate = mbRelease.date
                    finalMbId = mbTrack.recording?.id?.toString()
                    finalMbReleaseId = mbRelease.id.toString()
                } else if (finalMbId == null) {
                    if (mbRelease != null) {
                        onLiveOutput("Track '${tidalTrack.title}' not found in album '${mbRelease.title}'. Falling back to recording search.")
                    }
                    val mbRecording = musicBrainzService.searchRecording(finalTitle, tidalTrack.artists)
                    if (mbRecording != null) {
                        onLiveOutput("Matched MusicBrainz Recording: ${mbRecording.title}")
                        finalTitle = mbRecording.title ?: finalTitle
                        finalArtist = mbRecording.artistCredit?.joinToString(indexer.artistDelimiter) { it.name ?: it.artist?.name ?: "" } ?: finalArtist

                        val bestRelease = mbRecording.releases?.find { it.title?.cleanTitle()?.equals(albumTitle?.cleanTitle(), true) == true } 
                            ?: mbRecording.releases?.firstOrNull()
                        
                        finalAlbum = bestRelease?.title ?: finalAlbum
                        finalDate = bestRelease?.date
                        finalMbId = mbRecording.id.toString()
                        finalMbReleaseId = bestRelease?.id?.toString()
                    } else {
                        onLiveOutput("No MusicBrainz recording match found for: ${tidalTrack.title}")
                    }
                }

                if (finalMbReleaseId != null) {
                    finalCoverUrl = "https://coverartarchive.org/release/$finalMbReleaseId/front"
                }

                @Suppress("UnusedVariable", "unused")
                val coverDeferred = coverDownloads.getOrPut(finalCoverUrl) {
                    async {
                        try {
                            if (finalCoverUrl != tidalTrack.images.largest.url) {
                                onLiveOutput("Fetching enriched cover art for: $finalTitle")
                            }
                            ApiClient.instance.safeQueuedGet<ByteArray>(finalCoverUrl)
                        } catch (_: Exception) {
                            null
                        }
                    }
                }

                val animatedImage = tidalTrack.images.filter { it.animated }.maxByOrNull { it.width }
                val animatedCoverUrl = animatedImage?.url
                if (animatedCoverUrl != null) {
                    @Suppress("UnusedVariable", "unused")
                    val animatedCoverDeferred = animatedCoverDownloads.getOrPut(animatedCoverUrl) {
                        async {
                            try {
                                ApiClient.instance.safeQueuedGet<ByteArray>(animatedCoverUrl)
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                }

                trackMetadataMap[url] = TrackMetadata(
                    url = url,
                    tidalId = tidalTrack.id,
                    title = finalTitle,
                    artist = finalArtist,
                    album = finalAlbum,
                    date = finalDate,
                    mbId = finalMbId,
                    mbReleaseId = finalMbReleaseId,
                    coverUrl = finalCoverUrl,
                    coverData = null,
                    originalTitle = tidalTrack.title,
                    originalArtists = tidalTrack.artists,
                    animatedCoverUrl = animatedCoverUrl,
                    tidalAlbumId = tidalTrack.albumId,
                )
            }
        }

        onLiveOutput("Metadata pre-fetch complete. Starting import process...")

        val command = importCommand + urls
        val (result, _) = collectImportedFiles(command, maxRetries, 0, aliveCheck, userId, onLiveOutput) { paths ->
            onLiveOutput("Waiting for cover art imports to finish...")
            val coverDataMap = coverDownloads.mapValues { it.value.await() }

            paths.filter { indexer.isAudio(it) }.forEach { path ->
                onLiveOutput("Post-processing: ${path.absolutePathString()}")
                try {
                    val audioFile = AudioFileIO.read(path.toFile())
                    val tag = audioFile.tag
                    val fileTitle = audioFile.title
                    val fileArtists = audioFile.getArtists(indexer.artistDelimiter)

                    val metadata = trackMetadataMap.values.find { meta ->
                        val filenameMatch = path.nameWithoutExtension == meta.tidalId
                        val titleArtistMatch = meta.originalTitle.equals(fileTitle, true) &&
                                meta.originalArtists.any { artist ->
                                    fileArtists.any { it.equals(artist, true) }
                                }

                        filenameMatch || titleArtistMatch
                    }

                    if (metadata != null) {
                        onLiveOutput("Tagged: ${metadata.title} - ${metadata.artist}")
                        tag.setField(FieldKey.TITLE, metadata.title)
                        tag.setField(FieldKey.ARTIST, metadata.artist)
                        metadata.album?.let { tag.setField(FieldKey.ALBUM, it) }
                        metadata.date?.let { tag.setField(FieldKey.YEAR, it) }
                        if (tag.getFirst(FieldKey.MUSICBRAINZ_TRACK_ID).isNullOrBlank()) {
                            metadata.mbId?.let { tag.setField(FieldKey.MUSICBRAINZ_TRACK_ID, it) }
                        }
                        if (tag.getFirst(FieldKey.MUSICBRAINZ_RELEASEID).isNullOrBlank()) {
                            metadata.mbReleaseId?.let { tag.setField(FieldKey.MUSICBRAINZ_RELEASEID, it) }
                        }
                        audioFile.setOriginalUrl(metadata.url)

                        if (tag.getFirst(FieldKey.LYRICS).isNullOrBlank()) {
                            val duration = (audioFile.audioHeader.preciseTrackLength * 1000).toLong()
                            val lyricsResponse = lrcLibService.getLyrics(metadata.artist, metadata.title, metadata.album, duration)
                            lyricsResponse?.syncedLyrics?.let { synced ->
                                if (synced.isNotBlank()) {
                                    tag.setField(FieldKey.LYRICS, synced)
                                }
                            }
                        }

                        if (audioFile.coverImage == null) {
                            val coverData = metadata.coverUrl?.let { coverDataMap[it] }
                            coverData?.let { data ->
                                audioFile.setCoverImage(data, imageUrl = metadata.coverUrl)
                            }
                        }
                        audioFile.commit()
                    } else {
                        onLiveOutput("Could not find metadata match for: ${path.absolutePathString()}")
                    }
                } catch (e: Exception) {
                    logger.error("Failed to post-process $path", e)
                }
            }
        }

        val animatedDataMap = animatedCoverDownloads.mapValues { it.value.await() }
        val insertables = trackMetadataMap.values
            .mapNotNull { meta ->
                val bytes = animatedDataMap[meta.animatedCoverUrl] ?: return@mapNotNull null
                InsertableAnimatedImage(bytes, bytes.sha256(), meta.animatedCoverUrl!!)
            }
            .distinctBy { it.contentHash }

        if (insertables.isNotEmpty()) {
            try {
                val hashToId = animatedImageService.createBatch(insertables)
                val providerName = metadataType.value

                dbQuery {
                    val tidalIdToSongId = SongProviderTable
                        .select(SongProviderTable.songId, SongProviderTable.externalId)
                        .where { SongProviderTable.provider eq providerName }
                        .andWhere { SongProviderTable.externalId inList trackMetadataMap.values.map { it.tidalId } }
                        .associate { it[SongProviderTable.externalId] to it[SongProviderTable.songId].value }

                    trackMetadataMap.values.forEach { meta ->
                        val bytes = animatedDataMap[meta.animatedCoverUrl] ?: return@forEach
                        val animId = hashToId[bytes.sha256()] ?: return@forEach
                        val songId = tidalIdToSongId[meta.tidalId] ?: return@forEach
                        SongTable.update({ SongTable.id eq songId }) {
                            it[SongTable.animatedCover] = EntityID(animId, AnimatedImageTable)
                        }
                    }

                    val animatedByAlbum = trackMetadataMap.values
                        .filter { it.animatedCoverUrl != null && it.tidalAlbumId != null }
                        .groupBy { it.tidalAlbumId!! }

                    if (animatedByAlbum.isNotEmpty()) {
                        val tidalAlbumIdToAlbumId = AlbumProviderTable
                            .select(AlbumProviderTable.albumId, AlbumProviderTable.externalId)
                            .where { AlbumProviderTable.provider eq providerName }
                            .andWhere { AlbumProviderTable.externalId inList animatedByAlbum.keys.toList() }
                            .associate { it[AlbumProviderTable.externalId] to it[AlbumProviderTable.albumId].value }

                        animatedByAlbum.forEach { (tidalAlbumId, tracks) ->
                            val meta = tracks.first()
                            val bytes = animatedDataMap[meta.animatedCoverUrl] ?: return@forEach
                            val animId = hashToId[bytes.sha256()] ?: return@forEach
                            val albumId = tidalAlbumIdToAlbumId[tidalAlbumId] ?: return@forEach
                            AlbumTable.update({ AlbumTable.id eq albumId }) {
                                it[AlbumTable.animatedCover] = EntityID(animId, AnimatedImageTable)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to save animated covers", e)
            }
        }

        result
    }

    override suspend fun getWrapper(type: Type, ids: List<String>, user: User): IdsWrapper {
        val metadataService = MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, get())
        return when (type) {
            Type.MIX -> IdsWrapper.from(type, ids.associateBy { UUID.randomUUID().mostSignificantBits })
            Type.SONG, Type.VIDEO -> IdsWrapper.from(
                type,
                ids.associateBy { UUID.randomUUID().mostSignificantBits })

            Type.ARTIST -> {
                val groups = ids.asFlow().map { id ->
                    val tracks = metadataService.getArtistTracks(id, priority = HttpClientPriority.HIGH)
                    IdsGroup(
                        id,
                        emptyFlow(),
                        IMetadataService.FlowArtist(
                            id = id,
                            tracks = tracks
                        )
                    )
                }
                IdsWrapper(type, groups)
            }

            Type.ALBUM -> {
                val groups = ids.asFlow().map { id ->
                    IdsGroup(
                        id,
                        emptyFlow(),
                        IMetadataService.Album(
                            id = id,
                            title = "",
                            tracks = metadataService.getAlbumTracks(id, priority = HttpClientPriority.HIGH),
                        )
                    )
                }
                IdsWrapper(type, groups)
            }

            Type.PLAYLIST -> {
                val groups = metadataService.getPlaylistsByIds(ids, true, user, priority = HttpClientPriority.HIGH).map { playlist ->
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
                IdsWrapper(type, groups)
            }
        }
    }

    override suspend fun updateAlbumMetadata(albumId: PlatformUUID, originalId: String): Boolean {
        return try {
            val tidalId = originalId.removePrefix("tidal:")
            val metadataService = MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, get())
            val tidalAlbums = metadataService.getAlbumsByIds(listOf(tidalId))
            val tidalAlbum = tidalAlbums.firstOrNull()
            if (tidalAlbum != null) {
                dbQuery {
                    AlbumTable.update({ AlbumTable.id eq albumId }) {
                        it[AlbumTable.songCount] = tidalAlbum.trackCount
                        it[AlbumTable.releaseDate] = getISOFromDate(tidalAlbum.releaseDate)
                    }
                }
                true
            } else false
        } catch (e: Exception) {
            logger.error("Failed to update Tidal album metadata for $originalId", e)
            false
        }
    }

    override fun extractLoginUrl(log: String): String? {
        val regex = Regex("""https?://link\.tidal\.com/[^\s,]+""")
        return regex.find(log)?.value
    }

    override suspend fun parseUrl(url: String): Pair<String, Type?>? {
        return ParserFactory.getParserForProvider("tidal")?.parse(url)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun importIds(
        ids: List<String>,
        type: Type,
        user: User,
        callback: suspend (List<String>) -> Unit
    ): Pair<Boolean, List<UserSong>> {
        val downloadStage = mutableListOf<String>()
        val downloadStageMutex = Mutex()

        val wrapper = getWrapper(type, ids, user)
        
        val existingSongs = if (wrapper.fetchExistingSongs()) {
            songService.byOriginalIds(ids.map { "https://tidal.com/track/$it" }, user.id)
        } else emptyList()

        val existingUrls = existingSongs.map { it.originalUrl }
        var contentToDownload = false

        when (type) {
            Type.PLAYLIST -> {
                wrapper.idGroups.buffer(2).collect { idGroup ->
                    val playlistId = idGroup.metadata?.let { playlist ->
                        if (playlist is IMetadataService.FlowPlaylist) {
                            val image = playlist.images.largest
                            val imageBytes = ApiClient.instance.safeGet<ByteArray>(image.url)

                            val imageHash = imageBytes?.let { bytes ->
                                val hash = bytes.sha256()
                                imageService.createBatch(
                                    listOf(
                                        InsertableImage(
                                            data = bytes,
                                            imageHash = hash,
                                            origin = image.url
                                        )
                                    )
                                )
                                hash
                            }

                            userPlaylistService.getOrAddPlaylist(
                                user.id, idGroup.id, InsertablePlaylist(
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
                        if (playlist is IMetadataService.FlowPlaylist) {
                            playlist.sharedTracks
                                .buffer(100)
                                .filterExisting(
                                    songService = songService,
                                    user = user
                                ) { songIds ->
                                    if (playlistId != null) {
                                        userPlaylistService.addToPlaylist(playlistId, songIds)
                                        importService.logger.info("Added ${songIds.size} songs to playlist $playlistId")
                                    }
                                }
                                .collect { trackChunk ->
                                    contentToDownload = true
                                    importService.addToQueue(
                                        UrlImportQueueEntry(
                                            urls = trackChunk
                                                .map { track -> "https://tidal.com/track/${track.id}" }
                                                .toMutableList(),
                                            ids = trackChunk.map { it.id },
                                            byUser = user.id,
                                            maxRetries = trackChunk.size,
                                            type = Type.SONG,
                                            importer = ImportBackend(id)
                                        ) {
                                            val songs = songService.byOriginalIds(
                                                trackChunk.map { it.id },
                                                user.id
                                            )
                                            val songIds = trackChunk.map { track ->
                                                track.addedAt?.toInstant()
                                                    ?.toEpochMilli() to songs.find {
                                                    it.originalUrl.endsWith(
                                                        "/${track.id}"
                                                    )
                                                }?.id
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

            Type.ALBUM -> {
                wrapper.idGroups.buffer(2).collect { idGroup ->
                    idGroup.metadata?.let { metadata ->
                        when (metadata) {
                            is IMetadataService.Album -> metadata.tracks
                            else -> emptyFlow()
                        }
                            .buffer(100)
                            .filterExisting(
                                songService = songService,
                                user = user,
                                chunkSize = 100,
                                deduplicateByIsrc = false
                            ).collect { trackChunk ->
                                importService.addToQueue(
                                    UrlImportQueueEntry(
                                        urls = trackChunk
                                            .map { track -> "https://tidal.com/track/${track.id}" }
                                            .toMutableList(),
                                        ids = trackChunk.map { it.id },
                                        byUser = user.id,
                                        maxRetries = trackChunk.size,
                                        type = Type.SONG,
                                        importer = ImportBackend(id)
                                    )
                                )
                            }
                    }
                }
            }

            Type.ARTIST -> {
                wrapper.idGroups.buffer(2).collect { idGroup ->
                    idGroup.metadata?.let { metadata ->
                        when (metadata) {
                            is IMetadataService.FlowArtist -> metadata.sharedTracks
                            else -> emptyFlow()
                        }
                            .buffer(100)
                            .filterExisting(
                                songService = songService,
                                user = user,
                                deduplicateByIsrc = false
                            ).collect { trackChunk ->
                                importService.addToQueue(
                                    UrlImportQueueEntry(
                                        urls = trackChunk
                                            .map { track -> "https://tidal.com/track/${track.id}" }
                                            .toMutableList(),
                                        ids = trackChunk.map { it.id },
                                        byUser = user.id,
                                        maxRetries = trackChunk.size,
                                        type = Type.SONG,
                                        importer = ImportBackend(id)
                                    )
                                )
                            }
                    }
                }
            }

            Type.MIX, Type.SONG, Type.VIDEO -> {
                downloadStageMutex.withLock {
                    downloadStage.addAll(wrapper.getIds().toList().filter { id ->
                        existingUrls.none { url ->
                            url.split("/").contains(id)
                        }
                    })
                }

                val chunkSize = 20
                while (downloadStageMutex.withLock { downloadStage.isNotEmpty() }) {
                    contentToDownload = true
                    val urls = downloadStageMutex.withLock { 
                        val chunk = downloadStage.take(chunkSize)
                        downloadStage.removeAll(chunk)
                        chunk
                    }
                    importService.addToQueue(
                        UrlImportQueueEntry(
                            urls = urls.map { "https://tidal.com/${wrapper.type.value}/${it}" }
                                .toMutableList(),
                            ids = urls,
                            byUser = user.id,
                            type = wrapper.type,
                            importer = ImportBackend(id)
                        ) {
                            callback(urls)
                        })
                    if (downloadStage.isEmpty()) break
                }
            }
        }

        return Pair(contentToDownload, existingSongs)
    }
}
