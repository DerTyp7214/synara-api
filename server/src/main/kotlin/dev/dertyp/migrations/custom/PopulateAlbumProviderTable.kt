package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.db.AlbumProviderTable
import dev.dertyp.db.AlbumTable
import dev.dertyp.dbQuery
import dev.dertyp.services.import.Type
import dev.dertyp.utils.parsers.ParserFactory
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

@Migration("1.7")
class PopulateAlbumProviderTable : CustomMigration() {
    override suspend fun migrate() {
        val albums = dbQuery {
            AlbumTable
                .selectAll()
                .where { AlbumTable.originalId.isNotNull() }
                .map { it[AlbumTable.id].value to it[AlbumTable.originalId]!! }
        }

        albums.forEach { (albumId, originalId) ->
            val parser = ParserFactory.getParser(originalId)
            val parsed = parser?.parse(originalId)
            val provider = parser?.name ?: "unknown"
            val externalId = parsed?.first ?: (if (originalId.contains(":")) originalId.substringAfter(":") else originalId)

            dbQuery {
                AlbumProviderTable.upsert(AlbumProviderTable.albumId, AlbumProviderTable.provider, AlbumProviderTable.externalId) {
                    it[AlbumProviderTable.albumId] = albumId
                    it[AlbumProviderTable.provider] = provider
                    it[AlbumProviderTable.externalId] = externalId
                    it[AlbumProviderTable.type] = parsed?.second?.value ?: Type.ALBUM.value
                    it[AlbumProviderTable.rawUrl] = originalId
                }
            }
        }
    }
}
