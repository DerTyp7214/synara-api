package dev.dertyp.services

import dev.dertyp.ApiClient
import dev.dertyp.core.safeGet
import dev.dertyp.core.sha256
import dev.dertyp.data.InsertableImage
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.services.metadata.MetadataService
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.component.inject
import java.util.UUID
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

@OptIn(ExperimentalAtomicApi::class)
class MetadataFetchingService(private val environment: ApplicationEnvironment) : Service() {
    private val imageService by inject<ImageService>()
    private val genreService by inject<GenreService>()

    data class ArtistToFetch(val id: UUID, val name: String, val mbid: String?)
    data class AlbumToFetch(val id: UUID, val name: String, val mbid: String?)
    data class TrackToFetch(val id: UUID, val name: String, val mbid: String?)

    suspend fun fetchMetadata(
        metadataProvider: MetadataService.Companion.MetadataType,
        onProgress: suspend (Double, String) -> Unit = { _, _ -> }
    ): Map<String, Int> {
        if (!MetadataService.isFetching.compareAndSet(expectedValue = false, newValue = true)) {
            onProgress(0.0, "Fetching is already in progress.")
            return emptyMap()
        }

        val results = mutableMapOf<String, Int>()
        try {
            results.putAll(fetchArtistMetadataInternal(metadataProvider) { p, m ->
                onProgress(p / 3.0, m)
            })
            results.putAll(fetchAlbumMetadataInternal(metadataProvider) { p, m ->
                onProgress(33.33 + (p / 3.0), m)
            })
            results.putAll(fetchSongMetadataInternal(metadataProvider) { p, m ->
                onProgress(66.66 + (p / 3.0), m)
            })
            onProgress(100.0, "Metadata fetching finished.")
        } finally {
            MetadataService.isFetching.store(false)
        }
        return results
    }

    suspend fun fetchArtistImages(
        metadataProvider: MetadataService.Companion.MetadataType,
        onProgress: suspend (Double, String) -> Unit = { _, _ -> }
    ): Map<String, Int> {
        if (!MetadataService.isFetching.compareAndSet(expectedValue = false, newValue = true)) {
            onProgress(0.0, "Fetching is already in progress.")
            return emptyMap()
        }

        return try {
            val service = MetadataService.getMetadataService(metadataProvider, environment)
            var foundCount = 0
            var totalChecked = 0
            val thirtyDaysAgo = Clock.System.now() - 30.days
            val artists = dbQuery {
                ArtistTable
                    .leftJoin(ArtistMusicBrainzTable)
                    .select(ArtistTable.id, ArtistTable.name, ArtistMusicBrainzTable.musicBrainzId)
                    .where { (ArtistTable.image eq null) and (ArtistTable.lastImageCheck eq 0L or (ArtistTable.lastImageCheck less thirtyDaysAgo.toEpochMilliseconds())) }
                    .map { ArtistToFetch(it[ArtistTable.id].value, it[ArtistTable.name], it.getOrNull(ArtistMusicBrainzTable.musicBrainzId)) }
            }

            logger.info("Starting artist image fetch for ${artists.size} artists")
            onProgress(0.0, "Starting fetch for ${artists.size} artists")

            val artistChannel = Channel<ArtistToFetch>(Channel.UNLIMITED)
            val totalToFetch = artists.size

            coroutineScope {
                repeat(1) {
                    launch {
                        for (artistData in artistChannel) {
                            val id = artistData.id
                            val name = artistData.name
                            val mbid = artistData.mbid

                            totalChecked++
                            val progress = (totalChecked.toDouble() / totalToFetch) * 100.0
                            onProgress(progress, "Fetching image for: $name")

                            val artist = if (metadataProvider == MetadataService.Companion.MetadataType.theAudioDB && mbid != null) {
                                try {
                                    service.getArtistByMbId(mbid)
                                } catch (e: Exception) {
                                    logger.error("Error fetching artist by MBID for $name ($mbid)", e)
                                    null
                                }
                            } else {
                                val response = try {
                                    service.searchArtists(name, 20)
                                } catch (e: Exception) {
                                    logger.error("Error searching artists for $name", e)
                                    emptyList()
                                }

                                response.sortedByDescending { it.popularity }.firstOrNull { a ->
                                    a.name.replace(".", "")
                                        .equals(name.replace(".", ""), ignoreCase = true)
                                }
                            }

                            if (artist == null || artist.images.isEmpty()) {
                                onProgress(progress, "No images for \"$name\" found.")
                                updateLastCheck(id)
                                continue
                            }

                            val images = artist.images
                            val image = images.maxByOrNull { it.width }
                            if (image == null) {
                                onProgress(progress, "No image for \"$name\"")
                                updateLastCheck(id)
                                continue
                            }

                            val imageBytes = ApiClient.instance.safeGet<ByteArray>(image.url)
                            if (imageBytes == null) {
                                onProgress(progress, "Failed to download image for \"$name\"")
                                updateLastCheck(id)
                                continue
                            }

                            val imageId = imageService.createBatch(
                                listOf(
                                    InsertableImage(
                                        data = imageBytes,
                                        imageHash = imageBytes.sha256(),
                                        origin = image.url
                                    )
                                )
                            ).firstOrNull()

                            if (imageId == null) {
                                onProgress(progress, "Error inserting image for \"$name\"")
                                updateLastCheck(id)
                                continue
                            }

                            val updates = dbQuery {
                                ArtistTable.update({ ArtistTable.id eq id }) {
                                    it[ArtistTable.image] = EntityID(imageId, ImageTable)
                                    it[ArtistTable.lastImageCheck] = System.currentTimeMillis()
                                }
                            }

                            if (updates == 1) {
                                onProgress(progress, "Updated \"$name\" with an image.")
                                foundCount++
                            }
                            else onProgress(progress, "Something went wrong updating $name")
                        }
                    }
                }

                for (artist in artists) {
                    artistChannel.send(artist)
                    ensureActive()
                }

                artistChannel.close()
            }

            onProgress(100.0, "Loading artist images done.")
            mapOf("checked" to totalChecked, "found" to foundCount)
        } finally {
            MetadataService.isFetching.store(false)
        }
    }

    private suspend fun fetchArtistMetadataInternal(
        metadataProvider: MetadataService.Companion.MetadataType,
        onProgress: suspend (Double, String) -> Unit = { _, _ -> }
    ): Map<String, Int> {
        val service = MetadataService.getMetadataService(metadataProvider, environment)

        var foundCount = 0
        var totalChecked = 0
        val thirtyDaysAgo = Clock.System.now() - 30.days
        val artists = dbQuery {
            ArtistTable
                .leftJoin(ArtistMusicBrainzTable)
                .select(ArtistTable.id, ArtistTable.name, ArtistMusicBrainzTable.musicBrainzId)
                .where { (ArtistTable.image eq null or ArtistTable.about.eq("")) and (ArtistTable.lastImageCheck eq 0L or (ArtistTable.lastImageCheck less thirtyDaysAgo.toEpochMilliseconds())) }
                .map { ArtistToFetch(it[ArtistTable.id].value, it[ArtistTable.name], it.getOrNull(ArtistMusicBrainzTable.musicBrainzId)) }
        }

        logger.info("Starting artist metadata fetch for ${artists.size} artists")
        onProgress(0.0, "Starting fetch for ${artists.size} artists")

        val artistChannel = Channel<ArtistToFetch>(Channel.UNLIMITED)
        val totalToFetch = artists.size

        coroutineScope {
            repeat(1) {
                launch {
                    for (artistData in artistChannel) {
                        val id = artistData.id
                        val name = artistData.name
                        val mbid = artistData.mbid

                        totalChecked++
                        val progress = (totalChecked.toDouble() / totalToFetch) * 100.0
                        onProgress(progress, "Fetching metadata for: $name")

                        val artist = if (metadataProvider == MetadataService.Companion.MetadataType.theAudioDB && mbid != null) {
                            try {
                                service.getArtistByMbId(mbid)
                            } catch (e: Exception) {
                                logger.error("Error fetching artist by MBID for $name ($mbid)", e)
                                null
                            }
                        } else {
                            val response = try {
                                service.searchArtists(name, 20)
                            } catch (e: Exception) {
                                logger.error("Error searching artists for $name", e)
                                emptyList()
                            }

                            response.sortedByDescending { it.popularity }.firstOrNull { a ->
                                a.name.replace(".", "")
                                    .equals(name.replace(".", ""), ignoreCase = true)
                            }
                        }

                        if (artist == null) {
                            onProgress(progress, "No metadata for \"$name\" found.")
                            updateLastCheck(id)
                            continue
                        }

                        // Save Genres/Styles
                        val genresToStore = (artist.genres + artist.styles).distinct()
                        if (genresToStore.isNotEmpty()) {
                            val genreIds = genreService.getOrCreateGenres(genresToStore)
                            dbQuery {
                                ArtistGenreTable.deleteWhere { ArtistGenreTable.artistId eq id }
                                ArtistGenreTable.batchInsert(genreIds) { genreId ->
                                    this[ArtistGenreTable.artistId] = id
                                    this[ArtistGenreTable.genreId] = genreId
                                }
                            }
                        }

                        // Save Biography
                        if (!artist.biography.isNullOrBlank()) {
                            dbQuery {
                                ArtistTable.update({ ArtistTable.id eq id }) {
                                    it[ArtistTable.about] = artist.biography!!
                                }
                            }
                        }

                        // Save Image
                        val images = artist.images
                        val image = images.maxByOrNull { it.width }
                        if (image != null) {
                            val imageBytes = ApiClient.instance.safeGet<ByteArray>(image.url)
                            if (imageBytes != null) {
                                val imageId = imageService.createBatch(
                                    listOf(
                                        InsertableImage(
                                            data = imageBytes,
                                            imageHash = imageBytes.sha256(),
                                            origin = image.url
                                        )
                                    )
                                ).firstOrNull()

                                if (imageId != null) {
                                    dbQuery {
                                        ArtistTable.update({ ArtistTable.id eq id }) {
                                            it[ArtistTable.image] = EntityID(imageId, ImageTable)
                                        }
                                    }
                                }
                            }
                        }

                        updateLastCheck(id)
                        onProgress(progress, "Updated \"$name\" with metadata.")
                        foundCount++
                    }
                }
            }

            for (artist in artists) {
                artistChannel.send(artist)
                ensureActive()
            }

            artistChannel.close()
        }

        onProgress(100.0, "Loading artist metadata done.")
        return mapOf(
            "artistsChecked" to totalChecked,
            "artistsFound" to foundCount
        )
    }

    suspend fun fetchAlbumImages(
        metadataProvider: MetadataService.Companion.MetadataType,
        onProgress: suspend (Double, String) -> Unit = { _, _ -> }
    ): Map<String, Int> {
        if (!MetadataService.isFetching.compareAndSet(expectedValue = false, newValue = true)) {
            onProgress(0.0, "Fetching is already in progress.")
            return emptyMap()
        }

        return try {
            val service = MetadataService.getMetadataService(metadataProvider, environment)
            var foundCount = 0
            var totalChecked = 0
            val albums = dbQuery {
                AlbumTable
                    .leftJoin(AlbumMusicBrainzTable)
                    .select(AlbumTable.id, AlbumTable.name, AlbumMusicBrainzTable.musicBrainzId)
                    .where { AlbumTable.cover eq null }
                    .map { AlbumToFetch(it[AlbumTable.id].value, it[AlbumTable.name], it.getOrNull(AlbumMusicBrainzTable.musicBrainzId)) }
            }

            logger.info("Starting album image fetch for ${albums.size} albums")
            onProgress(0.0, "Starting fetch for ${albums.size} albums")

            val albumChannel = Channel<AlbumToFetch>(Channel.UNLIMITED)
            val totalToFetch = albums.size

            coroutineScope {
                repeat(1) {
                    launch {
                        for (albumData in albumChannel) {
                            val id = albumData.id
                            val name = albumData.name
                            val mbid = albumData.mbid

                            totalChecked++
                            val progress = (totalChecked.toDouble() / totalToFetch) * 100.0
                            onProgress(progress, "Fetching image for: $name")

                            val images = if (metadataProvider == MetadataService.Companion.MetadataType.theAudioDB && mbid != null) {
                                try {
                                    service.getImageUrlByAlbumMbId(mbid)
                                } catch (e: Exception) {
                                    logger.error("Error fetching images by MBID for $name ($mbid)", e)
                                    emptyList()
                                }
                            } else {
                                try {
                                    service.searchAlbums(name, 20)
                                        .firstOrNull { it.title.equals(name, ignoreCase = true) }
                                        ?.images ?: emptyList()
                                } catch (e: Exception) {
                                    logger.error("Error searching albums for $name", e)
                                    emptyList()
                                }
                            }

                            if (images.isEmpty()) {
                                onProgress(progress, "No images for \"$name\" found.")
                                continue
                            }

                            val image = images.maxByOrNull { it.width }
                            if (image == null) {
                                onProgress(progress, "No image for \"$name\"")
                                continue
                            }

                            val imageBytes = ApiClient.instance.safeGet<ByteArray>(image.url)
                            if (imageBytes == null) {
                                onProgress(progress, "Failed to download image for \"$name\"")
                                continue
                            }

                            val imageId = imageService.createBatch(
                                listOf(
                                    InsertableImage(
                                        data = imageBytes,
                                        imageHash = imageBytes.sha256(),
                                        origin = image.url
                                    )
                                )
                            ).firstOrNull()

                            if (imageId == null) {
                                onProgress(progress, "Error inserting image for \"$name\"")
                                continue
                            }

                            val updates = dbQuery {
                                AlbumTable.update({ AlbumTable.id eq id }) {
                                    it[AlbumTable.cover] = EntityID(imageId, ImageTable)
                                }
                            }

                            if (updates == 1) {
                                onProgress(progress, "Updated \"$name\" with an image.")
                                foundCount++
                            }
                            else onProgress(progress, "Something went wrong updating $name")
                        }
                    }
                }

                for (album in albums) {
                    albumChannel.send(album)
                    ensureActive()
                }

                albumChannel.close()
            }

            onProgress(100.0, "Loading album images done.")
            mapOf("checked" to totalChecked, "found" to foundCount)
        } finally {
            MetadataService.isFetching.store(false)
        }
    }

    private suspend fun fetchAlbumMetadataInternal(
        metadataProvider: MetadataService.Companion.MetadataType,
        onProgress: suspend (Double, String) -> Unit = { _, _ -> }
    ): Map<String, Int> {
        val service = MetadataService.getMetadataService(metadataProvider, environment)

        var foundCount = 0
        var totalChecked = 0
        val albums = dbQuery {
            AlbumTable
                .leftJoin(AlbumMusicBrainzTable)
                .select(AlbumTable.id, AlbumTable.name, AlbumMusicBrainzTable.musicBrainzId)
                .where { AlbumTable.cover eq null }
                .map { AlbumToFetch(it[AlbumTable.id].value, it[AlbumTable.name], it.getOrNull(AlbumMusicBrainzTable.musicBrainzId)) }
        }

        logger.info("Starting album metadata fetch for ${albums.size} albums")
        onProgress(0.0, "Starting fetch for ${albums.size} albums")

        val albumChannel = Channel<AlbumToFetch>(Channel.UNLIMITED)
        val totalToFetch = albums.size

        coroutineScope {
            repeat(1) {
                launch {
                    for (albumData in albumChannel) {
                        val id = albumData.id
                        val name = albumData.name
                        val mbid = albumData.mbid

                        totalChecked++
                        val progress = (totalChecked.toDouble() / totalToFetch) * 100.0
                        onProgress(progress, "Fetching metadata for: $name")

                        val albumMetadata = if (metadataProvider == MetadataService.Companion.MetadataType.theAudioDB && mbid != null) {
                            try {
                                service.getAlbumByMbId(mbid)
                            } catch (e: Exception) {
                                logger.error("Error fetching album by MBID for $name ($mbid)", e)
                                null
                            }
                        } else {
                            try {
                                service.searchAlbums(name, 20)
                                    .firstOrNull { it.title.equals(name, ignoreCase = true) }
                            } catch (e: Exception) {
                                logger.error("Error searching albums for $name", e)
                                null
                            }
                        }

                        if (albumMetadata == null) {
                            onProgress(progress, "No metadata for \"$name\" found.")
                            continue
                        }

                        // Save Genres
                        if (albumMetadata.genres.isNotEmpty()) {
                            val genreIds = genreService.getOrCreateGenres(albumMetadata.genres)
                            dbQuery {
                                AlbumGenreTable.deleteWhere { AlbumGenreTable.albumId eq id }
                                AlbumGenreTable.batchInsert(genreIds) { genreId ->
                                    this[AlbumGenreTable.albumId] = id
                                    this[AlbumGenreTable.genreId] = genreId
                                }
                            }
                        }

                        // Save Image
                        val images = albumMetadata.images
                        val image = images.maxByOrNull { it.width }
                        if (image != null) {
                            val imageBytes = ApiClient.instance.safeGet<ByteArray>(image.url)
                            if (imageBytes != null) {
                                val imageId = imageService.createBatch(
                                    listOf(
                                        InsertableImage(
                                            data = imageBytes,
                                            imageHash = imageBytes.sha256(),
                                            origin = image.url
                                        )
                                    )
                                ).firstOrNull()

                                if (imageId != null) {
                                    dbQuery {
                                        AlbumTable.update({ AlbumTable.id eq id }) {
                                            it[AlbumTable.cover] = EntityID(imageId, ImageTable)
                                        }
                                    }
                                }
                            }
                        }

                        onProgress(progress, "Updated \"$name\" with metadata.")
                        foundCount++
                    }
                }
            }

            for (album in albums) {
                albumChannel.send(album)
                ensureActive()
            }

            albumChannel.close()
        }

        onProgress(100.0, "Loading album metadata done.")
        return mapOf(
            "albumsChecked" to totalChecked,
            "albumsFound" to foundCount
        )
    }

    private suspend fun fetchSongMetadataInternal(
        metadataProvider: MetadataService.Companion.MetadataType,
        onProgress: suspend (Double, String) -> Unit = { _, _ -> }
    ): Map<String, Int> {
        val service = MetadataService.getMetadataService(metadataProvider, environment)

        var foundCount = 0
        var totalChecked = 0
        val tracks = dbQuery {
            SongTable
                .leftJoin(SongMusicBrainzTable)
                .leftJoin(SongGenreTable)
                .select(SongTable.id, SongTable.title, SongMusicBrainzTable.musicBrainzId)
                .where { SongGenreTable.songId eq null }
                .map { TrackToFetch(it[SongTable.id].value, it[SongTable.title], it.getOrNull(SongMusicBrainzTable.musicBrainzId)) }
        }

        logger.info("Starting song metadata fetch for ${tracks.size} tracks")
        onProgress(0.0, "Starting fetch for ${tracks.size} tracks")

        val trackChannel = Channel<TrackToFetch>(Channel.UNLIMITED)
        val totalToFetch = tracks.size

        coroutineScope {
            repeat(1) {
                launch {
                    for (trackData in trackChannel) {
                        val id = trackData.id
                        val name = trackData.name
                        val mbid = trackData.mbid

                        totalChecked++
                        val progress = (totalChecked.toDouble() / totalToFetch) * 100.0
                        onProgress(progress, "Fetching metadata for: $name")

                        if (mbid == null) {
                            onProgress(progress, "No MBID for \"$name\"")
                            continue
                        }

                        val trackMetadata = try {
                            service.getTrackByMbId(mbid)
                        } catch (e: Exception) {
                            logger.error("Error fetching track by MBID for $name ($mbid)", e)
                            null
                        }

                        if (trackMetadata == null || trackMetadata.genres.isEmpty()) {
                            onProgress(progress, "No metadata for \"$name\" found.")
                            continue
                        }

                        val genreIds = genreService.getOrCreateGenres(trackMetadata.genres)
                        dbQuery {
                            SongGenreTable.deleteWhere { SongGenreTable.songId eq id }
                            SongGenreTable.batchInsert(genreIds) { genreId ->
                                this[SongGenreTable.songId] = id
                                this[SongGenreTable.genreId] = genreId
                            }
                        }

                        onProgress(progress, "Updated \"$name\" with metadata.")
                        foundCount++
                    }
                }
            }

            for (track in tracks) {
                trackChannel.send(track)
                ensureActive()
            }

            trackChannel.close()
        }

        onProgress(100.0, "Loading song metadata done.")
        return mapOf(
            "songsChecked" to totalChecked,
            "songsFound" to foundCount
        )
    }

    private suspend fun updateLastCheck(id: UUID) = dbQuery {
        ArtistTable.update({ ArtistTable.id eq id }) {
            it[ArtistTable.lastImageCheck] = System.currentTimeMillis()
        }
    }
}
