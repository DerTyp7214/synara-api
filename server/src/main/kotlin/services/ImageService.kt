package dev.dertyp.services

import dev.dertyp.data.Image
import dev.dertyp.data.InsertableImage
import dev.dertyp.db.ImageTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class ImageService(database: Database) {
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

    suspend fun byId(id: UUID): Image? = queryImages {
        where { ImageTable.id eq id }
    }.singleOrNull()

    suspend fun byHash(hash: String): Image? = queryImages {
        where { ImageTable.imageHash eq hash }
    }.singleOrNull()

    private suspend fun queryImages(query: Query.() -> Query = { this }) = dbQuery {
        ImageTable
            .selectAll()
            .query()
            .map { map(it) }
    }

    suspend fun getOrCreate(insertableImage: InsertableImage): UUID? {
        val imageId = dbQuery {
            ImageTable
                .select(ImageTable.id)
                .where { ImageTable.imageHash eq insertableImage.imageHash }
                .map { it[ImageTable.id].value }
        }
        if (imageId.isNotEmpty()) return imageId.singleOrNull()

        return dbQuery {
            ImageTable.insertAndGetId {
                it[ImageTable.data] = ExposedBlob(insertableImage.data)
                it[ImageTable.imageHash] = insertableImage.imageHash
            }
        }.value
    }
}