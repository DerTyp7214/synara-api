package dev.dertyp.services

import dev.dertyp.core.paging
import dev.dertyp.core.sha256
import dev.dertyp.data.Image
import dev.dertyp.data.InsertableImage
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.db.ImageTable
import dev.dertyp.dbQuery
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.utils.LogParam
import net.coobird.thumbnailator.Thumbnails
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.koin.core.context.GlobalContext
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.RedisClient
import java.io.ByteArrayOutputStream
import java.util.*
import kotlin.io.path.*

class ImageService : IImageService, Service() {
    init {
        Path(storageService.imagesPath).toFile().mkdirs()
    }

    companion object {
        private val storageService = GlobalContext.get().get<StorageService>()
        private val redisConfig = GlobalContext.get().get<RedisCacheProvider.Config>()
        private val jedis by lazy {
            if (redisConfig.host != "none") RedisClient.create(
                HostAndPort(redisConfig.host, redisConfig.port)
            ) else null
        }

        fun mapImage(resultRow: ResultRow): Image {
            val id = resultRow[ImageTable.id].value
            val path = Path(storageService.imagesPath, resultRow[ImageTable.path]).absolutePathString()
            val imageHash = resultRow[ImageTable.imageHash]
            val origin = resultRow[ImageTable.origin]

            return Image(
                id = id,
                path = path,
                imageHash = imageHash,
                origin = origin,
            )
        }
    }

    fun map(resultRow: ResultRow): Image = mapImage(resultRow)

    override suspend fun byId(id: UUID): Image? = querySingle {
        where { ImageTable.id eq id }
    }

    override suspend fun byHash(hash: String): Image? = querySingle {
        where { ImageTable.imageHash eq hash }
    }

    override suspend fun getImageData(id: UUID, size: Int): ByteArray? {
        val cacheKey = "image:$id:$size".toByteArray()
        val cached = jedis?.get(cacheKey)
        if (cached != null) return cached

        val image = byId(id) ?: return null

        val path = Path(image.path)
        if (!path.exists()) return null

        val bytes = if (size > 0) {
            val outputStream = ByteArrayOutputStream()
            Thumbnails.of(path.toFile())
                .size(size, size)
                .outputFormat("jpeg")
                .toOutputStream(outputStream)
            outputStream.toByteArray()
        } else {
            path.readBytes()
        }

        jedis?.set(cacheKey, bytes)
        return bytes
    }

    override suspend fun createImage(@LogParam("size") bytes: ByteArray, origin: String): UUID {
        val hash = bytes.sha256()
        val insertableImage = InsertableImage(bytes, hash, origin)
        return createBatch(listOf(insertableImage)).first()
    }

    private suspend fun querySingle(query: Query.() -> Query) =
        queryImages(0, Int.MAX_VALUE, query).data.singleOrNull()

    private suspend fun queryImages(page: Int, pageSize: Int, query: Query.() -> Query = { this }) = dbQuery {
        val offset = if (pageSize == Int.MAX_VALUE) 0 else 1
        val data = ImageTable
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

    override suspend fun getCoverHashes(hashes: List<String>): Map<String, UUID> = dbQuery {
        ImageTable
            .select(ImageTable.id, ImageTable.imageHash)
            .where { ImageTable.imageHash inList hashes }
            .associate { it[ImageTable.imageHash] to it[ImageTable.id].value }
    }

    suspend fun createBatch(insertableImages: List<InsertableImage>): List<UUID> {
        if (insertableImages.isEmpty()) return emptyList()

        val images = dbQuery {
            ImageTable
                .select(ImageTable.id, ImageTable.imageHash)
                .where { ImageTable.imageHash inList insertableImages.map { it.imageHash } }
                .map { Pair(it[ImageTable.id].value, it[ImageTable.imageHash]) }
        }.toMap()

        val imageHashes = images.values
        val newImages = insertableImages.filter { it.imageHash !in imageHashes }
        val existingImages = images.filter { (_, imageHash) -> imageHash !in newImages.map { it.imageHash } }.keys

        return dbQuery {
            ImageTable.batchInsert(newImages.map {
                val imagePath = Path(
                    storageService.imagesPath,
                    *it.imageHash.windowed(2, 2).take(4).toTypedArray(),
                    "${it.imageHash.drop(2 * 4)}.jpeg"
                )
                if (imagePath.exists()) return@map Pair(it, imagePath)

                imagePath.parent.toFile().mkdirs()

                imagePath.writeBytes(it.data)
                Pair(it, imagePath)
            }) { (image, path) ->
                this[ImageTable.path] = Path(storageService.imagesPath).relativize(path).pathString
                this[ImageTable.imageHash] = image.imageHash
                this[ImageTable.origin] = image.origin
            }.map { it[ImageTable.id].value }
        } + existingImages
    }
}