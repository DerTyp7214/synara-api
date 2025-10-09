package dev.dertyp.services

import dev.dertyp.core.paging
import dev.dertyp.data.Image
import dev.dertyp.data.InsertableImage
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.db.ImageTable
import dev.dertyp.dbQuery
import io.ktor.util.logging.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class ImageService(database: Database) {
    private val logger = KtorSimpleLogger("ImageService")

    init {
        transaction(database) {
            SchemaUtils.create(ImageTable)
        }

        instance = this
    }

    companion object {
        var instance: ImageService? = null
            private set


        fun mapImage(resultRow: ResultRow): Image {
            val id = resultRow[ImageTable.id].value
            val data = resultRow[ImageTable.data]
            val imageHash = resultRow[ImageTable.imageHash]

            return Image(
                id = id,
                data = data.bytes,
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

        val imageIds = dbQuery {
            ImageTable
                .select(ImageTable.id, ImageTable.imageHash)
                .where { ImageTable.imageHash inList insertableImages.map { it.imageHash } }
                .map { it[ImageTable.imageHash] }
        }

        return dbQuery {
            ImageTable.batchInsert(insertableImages.filter { it.imageHash !in imageIds }) {
                this[ImageTable.data] = ExposedBlob(it.data)
                this[ImageTable.imageHash] = it.imageHash
            }.map { it[ImageTable.id].value }
        }
    }
}