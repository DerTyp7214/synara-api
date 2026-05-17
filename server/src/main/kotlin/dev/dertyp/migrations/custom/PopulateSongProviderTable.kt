package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.db.SongProviderTable
import dev.dertyp.db.SongTable
import dev.dertyp.dbQuery
import dev.dertyp.utils.parsers.ParserFactory
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

@Migration("1.6")
class PopulateSongProviderTable : CustomMigration() {
    override suspend fun migrate() {
        val songs = dbQuery {
            SongTable
                .selectAll()
                .where { SongTable.originalUrl neq "" }
                .map { it[SongTable.id].value to it[SongTable.originalUrl] }
        }

        songs.forEach { (songId, url) ->
            val parser = ParserFactory.getParser(url)
            val parsed = parser?.parse(url)
            val provider = parser?.name ?: "unknown"
            val externalId = parsed?.first ?: url

            dbQuery {
                SongProviderTable.upsert(SongProviderTable.songId, SongProviderTable.provider, SongProviderTable.externalId) {
                    it[SongProviderTable.songId] = songId
                    it[SongProviderTable.provider] = provider
                    it[SongProviderTable.externalId] = externalId
                    it[SongProviderTable.type] = parsed?.second?.value
                    it[SongProviderTable.rawUrl] = url
                }
            }
        }
    }
}
