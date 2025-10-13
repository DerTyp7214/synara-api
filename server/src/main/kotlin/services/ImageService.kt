package dev.dertyp.services

import dev.dertyp.core.paging
import dev.dertyp.data.Image
import dev.dertyp.data.InsertableImage
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.db.ImageTable
import dev.dertyp.dbQuery
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import kotlin.io.path.*

class ImageService(database: Database, environment: ApplicationEnvironment) : Service() {
    private val imagesPath = environment.config.property("data.images").getString().removeSuffix("/")

    init {
        transaction(database) {
            execInBatch(listOf("PRAGMA foreign_keys = ON"))
            SchemaUtils.create(ImageTable)
        }

        Path(imagesPath).toFile().mkdirs()

        instance = this
    }

    companion object {
        var instance: ImageService? = null
            private set


        fun mapImage(resultRow: ResultRow): Image {
            val id = resultRow[ImageTable.id].value
            val path = Path(instance!!.imagesPath, resultRow[ImageTable.path]).absolutePathString()
            val imageHash = resultRow[ImageTable.imageHash]

            return Image(
                id = id,
                path = path,
                imageHash = imageHash
            )
        }
    }

    fun map(resultRow: ResultRow): Image = mapImage(resultRow)

    suspend fun byId(id: UUID): Image? = querySingle {
        where { ImageTable.id eq id }
    }

    suspend fun byHash(hash: String): Image? = querySingle {
        where { ImageTable.imageHash eq hash }
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
            data = data.take(pageSize),
            page = page,
            pageSize = pageSize,
            hasNextPage = data.size == pageSize + offset,
        )
    }

    suspend fun getCoverHashes(hashes: List<String>): Map<String, UUID> = dbQuery {
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
                    imagesPath,
                    *it.imageHash.windowed(2, 2).take(4).toTypedArray(),
                    "${it.imageHash.drop(2 * 4)}.jpeg"
                )
                if (imagePath.exists()) return@map Pair(it, imagePath)

                imagePath.parent.toFile().mkdirs()

                imagePath.writeBytes(it.data)
                Pair(it, imagePath)
            }) { (image, path) ->
                this[ImageTable.path] = Path(imagesPath).relativize(path).pathString
                this[ImageTable.imageHash] = image.imageHash
            }.map { it[ImageTable.id].value }
        } + existingImages
    }
}