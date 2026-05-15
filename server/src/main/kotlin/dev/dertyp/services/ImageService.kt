package dev.dertyp.services

import com.sksamuel.scrimage.ImmutableImage
import dev.dertyp.core.paging
import dev.dertyp.core.sha256
import dev.dertyp.data.Image
import dev.dertyp.data.InsertableImage
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.User
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.plugins.ImageLibrary
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.utils.ColorUtils
import dev.dertyp.utils.ImageUtils
import dev.dertyp.utils.LogParam
import io.trbl.blurhash.BlurHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.coobird.thumbnailator.Thumbnails
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.RedisClusterClient
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.*
import kotlin.math.roundToInt
import com.sksamuel.scrimage.pixels.Pixel as ScrPixel

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

    override suspend fun generateMosaicImage(image: ByteArray, width: Int, height: Int): ByteArray =
        imageService.generateMosaicImage(image, width, height)
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
        val blurHash = resultRow[ImageTable.blurHash]

        val width = resultRow.getOrNull(ImageMetadataTable.width)
        val height = resultRow.getOrNull(ImageMetadataTable.height)
        val byteSize = resultRow.getOrNull(ImageMetadataTable.byteSize)
        val primaryColor = resultRow.getOrNull(ImageMetadataTable.primaryColor)
        val luminance = resultRow.getOrNull(ImageMetadataTable.luminance)

        val palette = listOfNotNull(
            resultRow.getOrNull(ImageMetadataTable.color1),
            resultRow.getOrNull(ImageMetadataTable.color2),
            resultRow.getOrNull(ImageMetadataTable.color3),
            resultRow.getOrNull(ImageMetadataTable.color4),
            resultRow.getOrNull(ImageMetadataTable.color5)
        ).ifEmpty { null }

        return Image(
            id = id,
            path = path,
            imageHash = imageHash,
            origin = origin,
            blurHash = blurHash,
            width = width,
            height = height,
            byteSize = byteSize,
            primaryColor = primaryColor,
            luminance = luminance,
            palette = palette
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
            .leftJoin(ImageMetadataTable)
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

    suspend fun getUnanalyzedImageIds(): List<UUID> = dbQuery {
        val analyzedIds = ImageMetadataTable.selectAll().map { it[ImageMetadataTable.imageId].value }
        ImageTable.selectAll()
            .where { ImageTable.id notInList analyzedIds }
            .map { it[ImageTable.id].value }
    }

    suspend fun analyzeImage(imageId: UUID) {
        val image = byId(imageId) ?: return
        val path = Path(image.path)
        if (!path.exists()) return

        val bytes = withContext(Dispatchers.IO) { path.readBytes() }
        val bufferedImage = withContext(Dispatchers.IO) { ImageIO.read(ByteArrayInputStream(bytes)) } ?: return
        val scrImage = ImmutableImage.fromAwt(bufferedImage)

        val width = bufferedImage.width
        val height = bufferedImage.height
        val byteSize = bytes.size.toLong()

        val blurHash = try {
            BlurHash.encode(bufferedImage, 4, 3)
        } catch (_: Exception) {
            null
        }

        val smallImage = scrImage.max(64, 64)
        val pixels = smallImage.pixels()
        
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        pixels.forEach {
            rSum += it.red()
            gSum += it.green()
            bSum += it.blue()
        }
        val avgR = (rSum / pixels.size).toInt()
        val avgG = (gSum / pixels.size).toInt()
        val avgB = (bSum / pixels.size).toInt()
        val primaryColor = (255 shl 24) or (avgR shl 16) or (avgG shl 8) or avgB

        val luminance = (0.2126 * avgR + 0.7152 * avgG + 0.0722 * avgB) / 255.0

        val (hue, saturation, lightness) = ColorUtils.rgbToHsl(avgR, avgG, avgB)
        val (labL, labA, labB) = ColorUtils.rgbToLab(avgR, avgG, avgB)

        val palette = try {
            extractPalette(pixels, 5)
        } catch (_: Exception) {
            listOf(primaryColor)
        }

        dbQuery {
            ImageTable.update({ ImageTable.id eq imageId }) {
                it[ImageTable.blurHash] = blurHash
            }

            ImageMetadataTable.upsert(ImageMetadataTable.imageId) {
                it[ImageMetadataTable.imageId] = imageId
                it[ImageMetadataTable.width] = width
                it[ImageMetadataTable.height] = height
                it[ImageMetadataTable.byteSize] = byteSize
                it[ImageMetadataTable.primaryColor] = primaryColor
                it[ImageMetadataTable.red] = avgR
                it[ImageMetadataTable.green] = avgG
                it[ImageMetadataTable.blue] = avgB
                it[ImageMetadataTable.luminance] = luminance
                it[ImageMetadataTable.hue] = hue
                it[ImageMetadataTable.saturation] = saturation
                it[ImageMetadataTable.lightness] = lightness
                it[ImageMetadataTable.labL] = labL
                it[ImageMetadataTable.labA] = labA
                it[ImageMetadataTable.labB] = labB
                it[color1] = palette.getOrNull(0)
                it[color2] = palette.getOrNull(1)
                it[color3] = palette.getOrNull(2)
                it[color4] = palette.getOrNull(3)
                it[color5] = palette.getOrNull(4)
            }
        }
    }

    private fun extractPalette(pixels: Array<ScrPixel>, k: Int): List<Int> {
        if (pixels.isEmpty()) return emptyList()
        
        val samples = if (pixels.size > 1000) {
            val step = pixels.size / 1000
            (0 until 1000).map { pixels[it * step] }
        } else {
            pixels.toList()
        }

        var centroids = samples.shuffled().take(k).map { 
            doubleArrayOf(it.red().toDouble(), it.green().toDouble(), it.blue().toDouble())
        }

        repeat(10) {
            val clusters = Array(centroids.size) { mutableListOf<DoubleArray>() }
            
            for (pixel in samples) {
                val p = doubleArrayOf(pixel.red().toDouble(), pixel.green().toDouble(), pixel.blue().toDouble())
                var minDist = Double.MAX_VALUE
                var closestIndex = 0
                
                for (i in centroids.indices) {
                    val dist = sqDist(p, centroids[i])
                    if (dist < minDist) {
                        minDist = dist
                        closestIndex = i
                    }
                }
                clusters[closestIndex].add(p)
            }
            
            centroids = clusters.mapIndexed { i, cluster ->
                if (cluster.isEmpty()) centroids[i]
                else {
                    val avg = DoubleArray(3)
                    for (p in cluster) {
                        avg[0] += p[0]
                        avg[1] += p[1]
                        avg[2] += p[2]
                    }
                    avg[0] /= cluster.size
                    avg[1] /= cluster.size
                    avg[2] /= cluster.size
                    avg
                }
            }
        }

        return centroids.map { 
            val r = it[0].roundToInt().coerceIn(0, 255)
            val g = it[1].roundToInt().coerceIn(0, 255)
            val b = it[2].roundToInt().coerceIn(0, 255)
            ((0xFF shl 24) or (r shl 16) or (g shl 8) or b)
        }
    }

    suspend fun generateMosaicImage(image: ByteArray, width: Int, height: Int): ByteArray {
        val maxTiles = 256 * 256
        if (width * height > maxTiles) throw IllegalArgumentException("Grid size too large (max 65,536 tiles)")

        val allPixels = ImageUtils.extractColors(image, width, height)
        val pixelRanks = mutableMapOf<ImageUtils.Pixel, Int>()
        val pixelWithRank = allPixels.map { pixel ->
            val rank = pixelRanks.getOrDefault(pixel, 0)
            pixelRanks[pixel] = rank + 1
            pixel to rank
        }

        val distinctPixels = allPixels.distinct()
        val idsByPixel = mutableMapOf<ImageUtils.Pixel, List<UUID>>()

        distinctPixels.forEach { pixel ->
            val count = pixelRanks[pixel] ?: 0
            val (l, a, b) = ColorUtils.rgbToLab(pixel.r, pixel.g, pixel.b)
            val ids = dbQuery {
                ImageMetadataTable
                    .select(ImageMetadataTable.imageId)
                    .filterByColor(l, a, b, 50)
                    .orderByColorDistance(l, a, b)
                    .limit(count)
                    .map { it[ImageMetadataTable.imageId].value }
            }
            idsByPixel[pixel] = ids
        }

        val outputSize = 8192
        val mosaic = BufferedImage(outputSize, outputSize, BufferedImage.TYPE_INT_RGB)
        val g = mosaic.createGraphics()

        val allImageIds = idsByPixel.values.flatten().distinct()
        val imagePaths = dbQuery {
            ImageTable.select(ImageTable.id, ImageTable.path)
                .where { ImageTable.id inList allImageIds }
                .associate { it[ImageTable.id].value to it[ImageTable.path] }
        }

        val imageCache = mutableMapOf<UUID, BufferedImage?>()

        pixelWithRank.forEachIndexed { index, (pixel, rank) ->
            val pool = idsByPixel[pixel] ?: emptyList()
            val imageId = if (pool.isNotEmpty()) pool[rank % pool.size] else null

            val ix = index % width
            val iy = index / width
            
            val x = (ix * outputSize) / width
            val nextX = ((ix + 1) * outputSize) / width
            val currentTileWidth = nextX - x

            val y = (iy * outputSize) / height
            val nextY = ((iy + 1) * outputSize) / height
            val currentTileHeight = nextY - y

            if (imageId != null) {
                val tile = imageCache.getOrPut(imageId) {
                    val path = imagePaths[imageId]
                    if (path != null) {
                        val fullPath = Path(storageService.imagesPath, path)
                        if (fullPath.exists()) {
                            val img = try { withContext(Dispatchers.IO) { ImageIO.read(fullPath.toFile()) } } catch (_: Exception) { null }
                            if (img != null) {
                                val scaled = BufferedImage(currentTileWidth, currentTileHeight, BufferedImage.TYPE_INT_RGB)
                                val sg = scaled.createGraphics()
                                sg.drawImage(img, 0, 0, currentTileWidth, currentTileHeight, null)
                                sg.dispose()
                                scaled
                            } else null
                        } else null
                    } else null
                }

                if (tile != null) {
                    g.drawImage(tile, x, y, null)
                } else {
                    g.color = Color(pixel.r, pixel.g, pixel.b)
                    g.fillRect(x, y, currentTileWidth, currentTileHeight)
                }
            } else {
                g.color = Color(pixel.r, pixel.g, pixel.b)
                g.fillRect(x, y, currentTileWidth, currentTileHeight)
            }
        }

        g.dispose()
        val baos = ByteArrayOutputStream()
        withContext(Dispatchers.IO) {
            ImageIO.write(mosaic, "jpeg", baos)
        }
        return baos.toByteArray()
    }

    private fun sqDist(p1: DoubleArray, p2: DoubleArray): Double {
        val r = p1[0] - p2[0]
        val g = p1[1] - p2[1]
        val b = p1[2] - p2[2]
        return r * r + g * g + b * b
    }
}
