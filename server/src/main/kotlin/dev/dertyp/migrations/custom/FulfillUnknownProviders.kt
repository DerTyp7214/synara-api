package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.db.AlbumProviderTable
import dev.dertyp.db.SongProviderTable
import dev.dertyp.dbQuery
import dev.dertyp.utils.parsers.ParserFactory
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

@Migration("1.9")
class FulfillUnknownProviders : CustomMigration() {
    override suspend fun migrate() {
        processSongs()
        processAlbums()
    }

    private suspend fun processSongs() {
        val unknowns = dbQuery {
            SongProviderTable.selectAll()
                .where { SongProviderTable.provider eq "unknown" }
                .map {
                    Triple(
                        it[SongProviderTable.songId].value,
                        it[SongProviderTable.rawUrl],
                        it[SongProviderTable.externalId]
                    )
                }
        }

        unknowns.forEach { (id, url, oldExtId) ->
            val parser = ParserFactory.getParser(url)
            val parsed = parser?.parse(url)
            val provider = parser?.name ?: "unknown"
            val externalId = parsed?.first ?: url

            if (provider != "unknown") {
                dbQuery {
                    SongProviderTable.deleteWhere {
                        (SongProviderTable.songId eq id) and
                                (SongProviderTable.provider eq "unknown") and
                                (SongProviderTable.externalId eq oldExtId)
                    }
                    SongProviderTable.upsert(
                        SongProviderTable.songId,
                        SongProviderTable.provider,
                        SongProviderTable.externalId
                    ) {
                        it[SongProviderTable.songId] = id
                        it[SongProviderTable.provider] = provider
                        it[SongProviderTable.externalId] = externalId
                        it[SongProviderTable.type] = parsed?.second?.value
                        it[SongProviderTable.rawUrl] = url
                    }
                }
            }
        }
    }

    private suspend fun processAlbums() {
        val unknowns = dbQuery {
            AlbumProviderTable.selectAll()
                .where { AlbumProviderTable.provider eq "unknown" }
                .map {
                    Triple(
                        it[AlbumProviderTable.albumId].value,
                        it[AlbumProviderTable.rawUrl],
                        it[AlbumProviderTable.externalId]
                    )
                }
        }

        unknowns.forEach { (id, url, oldExtId) ->
            val parser = ParserFactory.getParser(url)
            val parsed = parser?.parse(url)
            val provider = parser?.name ?: "unknown"
            val externalId = parsed?.first ?: url

            if (provider != "unknown") {
                dbQuery {
                    AlbumProviderTable.deleteWhere {
                        (AlbumProviderTable.albumId eq id) and
                                (AlbumProviderTable.provider eq "unknown") and
                                (AlbumProviderTable.externalId eq oldExtId)
                    }
                    AlbumProviderTable.upsert(
                        AlbumProviderTable.albumId,
                        AlbumProviderTable.provider,
                        AlbumProviderTable.externalId
                    ) {
                        it[AlbumProviderTable.albumId] = id
                        it[AlbumProviderTable.provider] = provider
                        it[AlbumProviderTable.externalId] = externalId
                        it[AlbumProviderTable.type] = parsed?.second?.value
                        it[AlbumProviderTable.rawUrl] = url
                    }
                }
            }
        }
    }
}
