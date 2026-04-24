package dev.dertyp.services

import dev.dertyp.core.paging
import dev.dertyp.core.sha256
import dev.dertyp.data.Image
import dev.dertyp.data.InsertableImage
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.User
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.PlaylistTable
import dev.dertyp.db.RecentReleaseTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.UserPlaylistTable
import dev.dertyp.db.UserTable
import dev.dertyp.dbQuery
import dev.dertyp.plugins.ImageLibrary
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.utils.LogParam
import net.coobird.thumbnailator.Thumbnails
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.RedisClusterClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.pathString
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

class ImageRpcService(private val user: User?, private val imageService: ImageService) : IImageService {
    override suspend fun byId(id: UUID): Image? = imageService.byId(id)
    override suspend fun byHash(hash: String): Image? = imageService.byHash(hash)
    override suspend fun getCoverHashes(hashes: List<String>): Map<String, UUID> = imageService.getCoverHashes(hashes)
    override suspend fun getImageData(id: UUID, size: Int): ByteArray? = imageService.getImageData(id, size)
    override suspend fun createImage(bytes: ByteArray, origin: String): UUID = imageService.createImage(bytes, origin)
    override suspend fun createBatch(images: List<InsertableImage>): Map<String, UUID> = imageService.createBatch(images)
    override suspend fun moveImages(oldPath: String, newPath: String): Int {
        if (user == null) throw IllegalStateException("No user found")
        if (!user.isAdmin) throw IllegalStateException("Only admins can move images")
        return imageService.moveImages(oldPath, newPath)
    }
}

class ImageService(
    private val storageService: StorageService,
    private val redisConfig: RedisCacheProvider.Config
) : ImageLibrary, Service() {
    init {
        Path(storageService.imagesPath).toFile().mkdirs()
    }

    private val jedis by lazy {
        if (redisConfig.host != "none") RedisClusterClient.create(
            HostAndPort(redisConfig.host, redisConfig.port)
        ) else null
    }

    fun map(resultRow: ResultRow): Image {
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

    suspend fun byId(id: UUID): Image? = querySingle {
        where { ImageTable.id eq id }
    }

    suspend fun byIds(ids: List<UUID>): List<Image> = queryImages(0, ids.size) {
        where { ImageTable.id inList ids }
    }.data

    suspend fun byHash(hash: String): Image? = querySingle {
        where { ImageTable.imageHash eq hash }
    }

    suspend fun getImageData(id: UUID, size: Int): ByteArray? {
        val cacheKey = "image:$id:$size".toByteArray()
        val cached = jedis?.get(cacheKey)
        if (cached != null) return cached

        val image = byId(id) ?: return null

        val path = Path(image.path)
        if (!path.exists()) return null

        val bytes = if (size > 0) {
            try {
                val outputStream = ByteArrayOutputStream()
                Thumbnails.of(path.toFile())
                    .size(size, size)
                    .outputFormat(
                        when (path.extension) {
                            "jpg" -> "jpeg"
                            "jpeg" -> "jpeg"
                            "png" -> "png"
                            else -> "jpeg"
                        }
                    )
                    .toOutputStream(outputStream)
                outputStream.toByteArray()
            } catch (_: Exception) {
                path.readBytes()
            }
        } else {
            path.readBytes()
        }

        jedis?.set(cacheKey, bytes)
        return bytes
    }

    suspend fun createImage(@LogParam("size") bytes: ByteArray, origin: String): UUID {
        val hash = bytes.sha256()
        val insertableImage = InsertableImage(bytes, hash, origin)
        return createBatch(listOf(insertableImage)).values.first()
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

    suspend fun upsertImage(image: Image, data: ByteArray) = dbQuery {
        val extension = try {
            val inputStream = ByteArrayInputStream(data)
            val imageInputStream = ImageIO.createImageInputStream(inputStream)
            val readers = ImageIO.getImageReaders(imageInputStream)
            if (readers.hasNext()) {
                val reader = readers.next()
                reader.formatName.lowercase()
            } else {
                "jpeg"
            }
        } catch (_: Exception) {
            "jpeg"
        }

        val imagePath = Path(
            storageService.imagesPath,
            *image.imageHash.windowed(2, 2).take(4).toTypedArray(),
            "${image.imageHash.drop(2 * 4)}.$extension"
        )

        if (!imagePath.exists()) {
            imagePath.parent.toFile().mkdirs()
            imagePath.writeBytes(data)
        }

        ImageTable.upsert(ImageTable.id) {
            it[id] = image.id
            it[path] = Path(storageService.imagesPath).relativize(imagePath).pathString
            it[imageHash] = image.imageHash
            it[origin] = image.origin
        }
    }

    override suspend fun createBatch(images: List<InsertableImage>): Map<String, UUID> {
        if (images.isEmpty()) return emptyMap()

        val existingImages = dbQuery {
            ImageTable
                .select(ImageTable.id, ImageTable.imageHash)
                .where { ImageTable.imageHash inList images.map { it.imageHash } }
                .map { Pair(it[ImageTable.id].value, it[ImageTable.imageHash]) }
        }.toMap()

        val imageHashes = existingImages.values
        val newImages = images.filter { it.imageHash !in imageHashes }

        val newlyInserted = if (newImages.isNotEmpty()) {
            dbQuery {
                ImageTable.batchInsert(newImages.map {
                    val extension = try {
                        val inputStream = ByteArrayInputStream(it.data)
                        val imageInputStream = ImageIO.createImageInputStream(inputStream)
                        val readers = ImageIO.getImageReaders(imageInputStream)
                        if (readers.hasNext()) {
                            val reader = readers.next()
                            reader.formatName.lowercase()
                        } else {
                            "jpeg"
                        }
                    } catch (_: Exception) {
                        "jpeg"
                    }

                    val imagePath = Path(
                        storageService.imagesPath,
                        *it.imageHash.windowed(2, 2).take(4).toTypedArray(),
                        "${it.imageHash.drop(2 * 4)}.$extension"
                    )
                    if (!imagePath.exists()) {
                        imagePath.parent.toFile().mkdirs()
                        imagePath.writeBytes(it.data)
                    }
                    Pair(it, imagePath)
                }) { (image, path) ->
                    this[ImageTable.path] = Path(storageService.imagesPath).relativize(path).pathString
                    this[ImageTable.imageHash] = image.imageHash
                    this[ImageTable.origin] = image.origin
                }.associate { it[ImageTable.imageHash] to it[ImageTable.id].value }
            }
        } else emptyMap()

        return (existingImages.entries.associate { it.value to it.key }) + newlyInserted
    }

    suspend fun deleteUnreferencedImages(onProgress: suspend (Double, String) -> Unit = { _, _ -> }): Int = dbQuery {
        val referencedImages = mutableSetOf<UUID>()

        referencedImages.addAll(AlbumTable.selectAll().mapNotNull { it[AlbumTable.cover]?.value })
        referencedImages.addAll(ArtistTable.selectAll().mapNotNull { it[ArtistTable.image]?.value })
        referencedImages.addAll(SongTable.selectAll().mapNotNull { it[SongTable.cover]?.value })
        referencedImages.addAll(PlaylistTable.selectAll().mapNotNull { it[PlaylistTable.imageId]?.value })
        referencedImages.addAll(UserPlaylistTable.selectAll().mapNotNull { it[UserPlaylistTable.imageId]?.value })
        referencedImages.addAll(UserTable.selectAll().mapNotNull { it[UserTable.profileImage]?.value })
        referencedImages.addAll(RecentReleaseTable.selectAll().mapNotNull { it[RecentReleaseTable.imageId]?.value })

        val allImages = ImageTable.selectAll().map {
            it[ImageTable.id].value to it[ImageTable.path]
        }

        val unreferencedImages = allImages.filter { (id, _) -> id !in referencedImages }
        onProgress(0.0, "Found ${unreferencedImages.size} unreferenced images")

        val chunks = unreferencedImages.chunked(5000)
        chunks.forEachIndexed { index, batch ->
            val progress = (index.toDouble() / chunks.size) * 100.0
            onProgress(progress, "Deleting batch ${index + 1}/${chunks.size} (${batch.size} images)")

            val idsToDelete = batch.map { it.first }

            batch.forEach { (_, path) ->
                val imagePath = Path(storageService.imagesPath, path)
                if (imagePath.exists()) imagePath.deleteIfExists()
            }

            ImageTable.deleteWhere { ImageTable.id inList idsToDelete }
        }

        onProgress(100.0, "Deleted ${unreferencedImages.size} images")
        logger.info("Deleted ${unreferencedImages.size} unreferenced images")
        unreferencedImages.size
    }

    suspend fun moveImages(oldPath: String, newPath: String): Int = dbQuery {
        val affectedImages = ImageTable.select(ImageTable.id, ImageTable.path)
            .where { ImageTable.path like "$oldPath%" }
            .toList()

        affectedImages.forEach { row ->
            val id = row[ImageTable.id].value
            val currentPath = row[ImageTable.path]
            val newImagePath = currentPath.replaceFirst(oldPath, newPath)

            ImageTable.update({ ImageTable.id eq id }) {
                it[path] = newImagePath
            }
        }

        affectedImages.size
    }
}
