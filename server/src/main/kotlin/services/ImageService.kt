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


        suspend fun mapImage(resultRow: ResultRow): Image {
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

    suspend fun map(resultRow: ResultRow): Image = mapImage(resultRow)

    suspend fun byId(id: UUID): Image? = dbQuery {
        ImageTable
            .selectAll()
            .where { ImageTable.id eq id }
            .map { map(it) }.singleOrNull()
    }

    suspend fun byHash(hash: String): Image? = dbQuery {
        ImageTable
            .selectAll()
            .where { ImageTable.imageHash eq hash }
            .map { map(it) }
            .singleOrNull()
    }

    suspend fun getOrCreate(insertableImage: InsertableImage): UUID? {
        val image = byHash(insertableImage.imageHash)
        if (image != null) return image.id

        return dbQuery {
            ImageTable.insertAndGetId {
                it[ImageTable.data] = ExposedBlob(insertableImage.data)
                it[ImageTable.imageHash] = insertableImage.imageHash
            }
        }.value
    }
}

