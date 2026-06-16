package dev.dertyp.services

import dev.dertyp.core.paging
import dev.dertyp.core.sha256
import dev.dertyp.data.AnimatedImage
import dev.dertyp.data.InsertableAnimatedImage
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.AnimatedImageTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.SongTable
import dev.dertyp.dbQuery
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.utils.LogParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.RedisClusterClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.*
import javax.imageio.ImageIO
import kotlin.io.path.*

class AnimatedImageRpcService(
    private val animatedImageService: AnimatedImageService
) : IAnimatedImageService {
    override suspend fun byId(id: UUID): AnimatedImage? = animatedImageService.byId(id)
    override suspend fun byHash(hash: String): AnimatedImage? = animatedImageService.byHash(hash)
    override suspend fun getCoverHashes(hashes: List<String>): Map<String, UUID> = animatedImageService.getCoverHashes(hashes)
    override suspend fun getAnimatedImageData(id: UUID): ByteArray? = animatedImageService.getAnimatedImageData(id)
    override suspend fun createAnimatedImage(bytes: ByteArray, origin: String): UUID = animatedImageService.createAnimatedImage(bytes, origin)
    override suspend fun createBatch(images: List<InsertableAnimatedImage>): Map<String, UUID> = animatedImageService.createBatch(images)
}

class AnimatedImageService(
    private val storageService: StorageService,
    private val redisConfig: RedisCacheProvider.Config,
    private val imageService: ImageService
) : IAnimatedImageService, Service() {
    init {
        Path(storageService.animatedImagesPath).toFile().mkdirs()
    }

    private val jedis by lazy {
        if (redisConfig.host != "none") RedisClusterClient.create(
            HostAndPort(redisConfig.host, redisConfig.port)
        ) else null
    }

    fun map(resultRow: ResultRow): AnimatedImage {
        val id = resultRow[AnimatedImageTable.id].value
        val path = Path(storageService.animatedImagesPath, resultRow[AnimatedImageTable.path]).absolutePathString()
        val contentHash = resultRow[AnimatedImageTable.contentHash]
        val origin = resultRow[AnimatedImageTable.origin]
        val format = resultRow[AnimatedImageTable.format]
        val imageId = resultRow[AnimatedImageTable.imageId]?.value
        val blurHash = resultRow.getOrNull(ImageTable.blurHash)

        return AnimatedImage(
            id = id,
            path = path,
            contentHash = contentHash,
            origin = origin,
            format = format,
            imageId = imageId,
            blurHash = blurHash
        )
    }

    override suspend fun byId(id: UUID): AnimatedImage? = querySingle {
        where { AnimatedImageTable.id eq id }
    }

    suspend fun byIds(ids: List<UUID>): List<AnimatedImage> = queryAnimated(0, ids.size) {
        where { AnimatedImageTable.id inList ids }
    }.data

    override suspend fun byHash(hash: String): AnimatedImage? = querySingle {
        where { AnimatedImageTable.contentHash eq hash }
    }

    override suspend fun getCoverHashes(hashes: List<String>): Map<String, UUID> = dbQuery {
        AnimatedImageTable
            .select(AnimatedImageTable.id, AnimatedImageTable.contentHash)
            .where { AnimatedImageTable.contentHash inList hashes }
            .associate { it[AnimatedImageTable.contentHash] to it[AnimatedImageTable.id].value }
    }

    override suspend fun getAnimatedImageData(id: UUID): ByteArray? {
        val cache = if (redisConfig.cacheAnimatedImages) jedis else null
        val cacheKey = "animated:$id".toByteArray()
        val cached = cache?.get(cacheKey)
        if (cached != null) return cached

        val animatedImage = byId(id) ?: return null
        val path = Path(animatedImage.path)
        if (!path.exists()) return null

        val bytes = withContext(Dispatchers.IO) { path.readBytes() }
        cache?.set(cacheKey, bytes)
        return bytes
    }

    override suspend fun createAnimatedImage(@LogParam("size") bytes: ByteArray, origin: String): UUID {
        val hash = bytes.sha256()
        val insertable = InsertableAnimatedImage(bytes, hash, origin)
        return createBatch(listOf(insertable)).values.first()
    }

    private suspend fun querySingle(query: Query.() -> Query) =
        queryAnimated(0, Int.MAX_VALUE, query).data.singleOrNull()

    private suspend fun queryAnimated(page: Int, pageSize: Int, query: Query.() -> Query = { this }) = dbQuery {
        val offset = if (pageSize == Int.MAX_VALUE) 0 else 1
        val data = AnimatedImageTable
            .leftJoin(ImageTable)
            .selectAll()
            .query()
            .paging(page, pageSize)
            .map { map(it) }

        PaginatedResponse(
            data = data.drop(page * pageSize).take(pageSize),
            total = data.size,
            page = page,
            pageSize = pageSize,
            hasNextPage = data.drop(page * pageSize).size >= pageSize + offset,
        )
    }

    suspend fun deleteUnreferencedAnimatedImages(onProgress: suspend (Double, String) -> Unit = { _, _ -> }): Int = dbQuery {
        val referencedIds = mutableSetOf<UUID>()
        referencedIds.addAll(AlbumTable.selectAll().mapNotNull { it[AlbumTable.animatedCover]?.value })
        referencedIds.addAll(SongTable.selectAll().mapNotNull { it[SongTable.animatedCover]?.value })

        val allAnimatedImages = AnimatedImageTable.selectAll().map {
            it[AnimatedImageTable.id].value to it[AnimatedImageTable.path]
        }

        val unreferenced = allAnimatedImages.filter { (id, _) -> id !in referencedIds }
        onProgress(0.0, "Found ${unreferenced.size} unreferenced animated images")

        val chunks = unreferenced.chunked(5000)
        chunks.forEachIndexed { index, batch ->
            val progress = (index.toDouble() / chunks.size) * 100.0
            onProgress(progress, "Deleting batch ${index + 1}/${chunks.size} (${batch.size} animated images)")

            val idsToDelete = batch.map { it.first }

            batch.forEach { (_, path) ->
                val filePath = Path(storageService.animatedImagesPath, path)
                if (filePath.exists()) filePath.deleteIfExists()
            }

            AnimatedImageTable.deleteWhere { AnimatedImageTable.id inList idsToDelete }
        }

        onProgress(100.0, "Deleted ${unreferenced.size} animated images")
        logger.info("Deleted ${unreferenced.size} unreferenced animated images")
        unreferenced.size
    }

    override suspend fun createBatch(images: List<InsertableAnimatedImage>): Map<String, UUID> {
        if (images.isEmpty()) return emptyMap()

        val existing = dbQuery {
            AnimatedImageTable
                .select(AnimatedImageTable.id, AnimatedImageTable.contentHash)
                .where { AnimatedImageTable.contentHash inList images.map { it.contentHash } }
                .map { Pair(it[AnimatedImageTable.id].value, it[AnimatedImageTable.contentHash]) }
        }.toMap()

        val existingHashes = existing.values
        val newImages = images.filter { it.contentHash !in existingHashes }

        val prepared = newImages.map { item ->
            val (frameBytes, format) = extractFirstFrameAndFormat(item.data)
            val imageId = frameBytes?.let { imageService.createImage(it, item.origin) }
            val extension = format ?: "mp4"

            val videoPath = Path(
                storageService.animatedImagesPath,
                *item.contentHash.windowed(2, 2).take(4).toTypedArray(),
                "${item.contentHash.drop(2 * 4)}.$extension"
            )
            if (!videoPath.exists()) {
                videoPath.parent.toFile().mkdirs()
                withContext(Dispatchers.IO) { videoPath.writeBytes(item.data) }
            }

            PreparedAnimatedImage(item, videoPath, format, imageId)
        }

        val newlyInserted = if (prepared.isNotEmpty()) {
            dbQuery {
                AnimatedImageTable.batchInsert(prepared) { prep ->
                    this[AnimatedImageTable.path] =
                        Path(storageService.animatedImagesPath).relativize(prep.path).pathString
                    this[AnimatedImageTable.contentHash] = prep.item.contentHash
                    this[AnimatedImageTable.origin] = prep.item.origin
                    this[AnimatedImageTable.format] = prep.format
                    this[AnimatedImageTable.imageId] = prep.imageId?.let { EntityID(it, ImageTable) }
                }.associate { it[AnimatedImageTable.contentHash] to it[AnimatedImageTable.id].value }
            }
        } else emptyMap()

        return (existing.entries.associate { it.value to it.key }) + newlyInserted
    }

    private suspend fun extractFirstFrameAndFormat(data: ByteArray): Pair<ByteArray?, String?> =
        withContext(Dispatchers.IO) {
            avutil.av_log_set_level(avutil.AV_LOG_QUIET)
            val grabber = FFmpegFrameGrabber(ByteArrayInputStream(data))
            try {
                grabber.start()
                val format = grabber.format?.let { formatToExtension(it) }
                val frame = grabber.grabImage()
                val frameBytes = frame?.let {
                    Java2DFrameConverter().use { converter ->
                        val bufferedImage = converter.convert(it) ?: return@let null
                        val out = ByteArrayOutputStream()
                        ImageIO.write(bufferedImage, "png", out)
                        out.toByteArray()
                    }
                }
                frameBytes to format
            } catch (e: Throwable) {
                logger.warn("Failed to extract first frame from animated image", e)
                null to null
            } finally {
                try {
                    grabber.stop()
                } catch (_: Throwable) {
                }
                grabber.release()
            }
        }

    private fun formatToExtension(format: String): String {
        val tokens = format.split(",").map { it.trim().lowercase() }
        return when {
            tokens.any { it == "mp4" } -> "mp4"
            tokens.any { it == "webm" } -> "webm"
            tokens.any { it == "gif" } -> "gif"
            tokens.any { it == "matroska" } -> "mkv"
            else -> tokens.firstOrNull()?.takeIf { it.isNotBlank() } ?: "mp4"
        }
    }

    private data class PreparedAnimatedImage(
        val item: InsertableAnimatedImage,
        val path: java.nio.file.Path,
        val format: String?,
        val imageId: UUID?
    )
}
