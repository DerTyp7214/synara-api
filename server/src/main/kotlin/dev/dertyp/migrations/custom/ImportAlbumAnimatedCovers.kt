package dev.dertyp.migrations.custom

import dev.dertyp.ApiClient
import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.core.safeQueuedGet
import dev.dertyp.core.sha256
import dev.dertyp.data.InsertableAnimatedImage
import dev.dertyp.db.AlbumProviderTable
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.AnimatedImageTable
import dev.dertyp.db.SongTable
import dev.dertyp.dbQuery
import dev.dertyp.services.AnimatedImageService
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.MetadataService
import io.ktor.server.application.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.component.inject

@Migration("3.0")
class ImportAlbumAnimatedCovers : CustomMigration() {
    private val animatedImageService by inject<AnimatedImageService>()
    private val environment by inject<ApplicationEnvironment>()

    override suspend fun migrate() {
        logTask("Import Album Animated Covers") {
            val ctx = this
            var albumsUpdated = 0
            var songsAffected = 0

            coroutineScope {
                val tidalProviderValue = IMetadataService.MetadataType.tidal.value

                val albumsWithAnimatedCover = dbQuery {
                    AlbumTable
                        .select(AlbumTable.id)
                        .where { AlbumTable.animatedCover.isNotNull() }
                        .map { it[AlbumTable.id].value }
                        .toSet()
                }

                val allTidalAlbums = dbQuery {
                    AlbumProviderTable
                        .select(AlbumProviderTable.albumId, AlbumProviderTable.externalId)
                        .where { AlbumProviderTable.provider eq tidalProviderValue }
                        .map { it[AlbumProviderTable.albumId].value to it[AlbumProviderTable.externalId] }
                        .filter { it.first !in albumsWithAnimatedCover }
                }

                if (allTidalAlbums.isEmpty()) return@coroutineScope

                val distinctTidalIds = allTidalAlbums.map { it.second }.distinct()
                ctx.log("Found ${allTidalAlbums.size} Tidal album(s) without animated cover (${distinctTidalIds.size} distinct IDs).")

                val metadataService = MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, environment)

                val tidalIdToAnimatedUrl = mutableMapOf<String, String>()
                val chunks = distinctTidalIds.chunked(20)
                chunks.forEachIndexed { index, chunk ->
                    try {
                        val albums = metadataService.getAlbumsByIds(chunk)
                        albums.forEach { album ->
                            val animatedImage = album.images.filter { it.animated }.maxByOrNull { it.width } ?: return@forEach
                            tidalIdToAnimatedUrl[album.id] = animatedImage.url
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to fetch Tidal album metadata batch ${index + 1}/${chunks.size}", e)
                    }
                    ctx.updateProgress(
                        (index + 1).toDouble() / chunks.size * 0.5,
                        "Fetching metadata: ${index + 1}/${chunks.size} batches | ${tidalIdToAnimatedUrl.size} animated URLs found"
                    )
                }

                if (tidalIdToAnimatedUrl.isEmpty()) return@coroutineScope

                val uniqueUrls = tidalIdToAnimatedUrl.values.distinct()
                ctx.log("Downloading ${uniqueUrls.size} unique animated image(s) in batches...")

                val urlToAnimId = mutableMapOf<String, java.util.UUID>()
                var downloaded = 0
                val downloadBatches = uniqueUrls.chunked(5)
                downloadBatches.forEachIndexed { batchIndex, batch ->
                    val batchBytes = batch.map { url ->
                        async {
                            try {
                                url to ApiClient.instance.safeQueuedGet<ByteArray>(url)
                            } catch (_: Exception) {
                                url to null
                            }
                        }
                    }.awaitAll()

                    val insertables = batchBytes.mapNotNull { (url, bytes) ->
                        bytes?.let { InsertableAnimatedImage(it, it.sha256(), url) }
                    }.distinctBy { it.contentHash }

                    if (insertables.isNotEmpty()) {
                        val hashToId = animatedImageService.createBatch(insertables)
                        batchBytes.forEach { (url, bytes) ->
                            bytes?.sha256()?.let { hash -> hashToId[hash]?.let { urlToAnimId[url] = it } }
                        }
                        downloaded += insertables.size
                    }

                    ctx.updateProgress(
                        0.5 + (batchIndex + 1).toDouble() / downloadBatches.size * 0.3,
                        "Downloaded $downloaded/${uniqueUrls.size} animated images"
                    )
                }

                if (urlToAnimId.isEmpty()) return@coroutineScope

                ctx.updateProgress(0.8, "Updating albums and songs...")

                dbQuery {
                    val albumToAnimId = allTidalAlbums.mapNotNull { (albumId, tidalId) ->
                        val url = tidalIdToAnimatedUrl[tidalId] ?: return@mapNotNull null
                        val animId = urlToAnimId[url] ?: return@mapNotNull null
                        albumId to animId
                    }.toMap()

                    if (albumToAnimId.isEmpty()) return@dbQuery

                    albumToAnimId.forEach { (albumId, animId) ->
                        AlbumTable.update({ AlbumTable.id eq albumId }) {
                            it[AlbumTable.animatedCover] = EntityID(animId, AnimatedImageTable)
                        }
                    }
                    albumsUpdated = albumToAnimId.size

                    val coverToAnimId = albumToAnimId.keys.toList().chunked(1000).flatMap { chunk ->
                        AlbumTable
                            .select(AlbumTable.id, AlbumTable.cover)
                            .where { AlbumTable.id inList chunk }
                            .mapNotNull { row ->
                                val coverId = row[AlbumTable.cover]?.value ?: return@mapNotNull null
                                val animId = albumToAnimId[row[AlbumTable.id].value] ?: return@mapNotNull null
                                coverId to animId
                            }
                    }.toMap()

                    if (coverToAnimId.isEmpty()) return@dbQuery

                    val animIdToCovers = coverToAnimId.entries.groupBy({ it.value }, { it.key })

                    animIdToCovers.forEach { (animId, coverIds) ->
                        val entityId = EntityID(animId, AnimatedImageTable)

                        coverIds.chunked(1000).forEach { chunk ->
                            AlbumTable.update({
                                (AlbumTable.cover inList chunk) and AlbumTable.animatedCover.isNull()
                            }) { it[AlbumTable.animatedCover] = entityId }

                            songsAffected += SongTable.update({
                                (SongTable.cover inList chunk) and SongTable.animatedCover.isNull()
                            }) { it[SongTable.animatedCover] = entityId }
                        }
                    }
                }

                ctx.updateProgress(1.0, "Updated $albumsUpdated album(s), $songsAffected song(s)")
            }

            mapOf("albumsUpdated" to albumsUpdated, "songsAffected" to songsAffected)
        }
    }
}
