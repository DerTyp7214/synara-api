package dev.dertyp.services.metadata

import dev.dertyp.core.HttpClientPriority
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.services.Service
import dev.dertyp.services.import.Type
import dev.dertyp.utils.parsers.ParserFactory
import io.ktor.server.application.ApplicationEnvironment
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.util.UUID

class AppleMusicArtistResolver(private val environment: ApplicationEnvironment) : Service() {

    private val appleMusicService by lazy {
        MetadataService.getMetadataService(
            IMetadataService.MetadataType.appleMusic,
            environment
        ) as AppleMusicService
    }

    private class CallBudget(private var remaining: Int) {
        fun take(): Boolean {
            if (remaining <= 0) return false
            remaining--
            return true
        }
    }

    suspend fun resolve(artistId: UUID, priority: HttpClientPriority = HttpClientPriority.LOW): String? {
        val apple = appleMusicService
        if (!apple.catalogEnabled) return null

        val artistName = dbQuery {
            ArtistTable.selectAll()
                .where { ArtistTable.id eq artistId }
                .singleOrNull()
                ?.get(ArtistTable.name)
        } ?: return null

        storedAppleId(artistId)?.let { return it }

        musicBrainzRelationId(artistId)?.let {
            persist(artistId, it)
            return it
        }

        val budget = CallBudget(MAX_CATALOG_CALLS)

        val albumIds = linkedAlbumIds(artistId)
        if (albumIds.isNotEmpty() && budget.take()) {
            apple.getCatalogAlbumsByIds(albumIds, priority).forEach { album ->
                pickArtistId(album.artistIds, artistName, apple, budget, priority)?.let {
                    persist(artistId, it)
                    return it
                }
            }
        }

        val songIds = linkedSongIds(artistId)
        if (songIds.isNotEmpty() && budget.take()) {
            apple.getCatalogSongsByIds(songIds, priority).forEach { song ->
                pickArtistId(song.artistIds, artistName, apple, budget, priority)?.let {
                    persist(artistId, it)
                    return it
                }
            }
        }

        for (upc in albumBarcodes(artistId)) {
            if (!budget.take()) break
            apple.getCatalogAlbumsByUpc(upc, priority).forEach { album ->
                pickArtistId(album.artistIds, artistName, apple, budget, priority)?.let {
                    persist(artistId, it)
                    return it
                }
            }
        }

        for (isrc in songIsrcs(artistId)) {
            if (!budget.take()) break
            apple.getCatalogSongsByIsrc(isrc, priority).forEach { song ->
                pickArtistId(song.artistIds, artistName, apple, budget, priority)?.let {
                    persist(artistId, it)
                    return it
                }
            }
        }

        logger.info("No Apple Music artist id resolved for $artistName ($artistId)")
        return null
    }

    private suspend fun pickArtistId(
        ids: List<String>,
        artistName: String,
        apple: AppleMusicService,
        budget: CallBudget,
        priority: HttpClientPriority
    ): String? {
        val candidates = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        if (!budget.take()) return null

        val names = apple.getCatalogArtistNames(candidates.take(CANDIDATE_LIMIT), priority)
        val matches = names.entries.filter { it.value.equals(artistName, ignoreCase = true) }
        return matches.singleOrNull()?.key
    }

    private suspend fun storedAppleId(artistId: UUID): String? = dbQuery {
        ArtistProviderTable.selectAll()
            .where { ArtistProviderTable.artistId eq artistId }
            .andWhere { ArtistProviderTable.provider eq PROVIDER }
            .andWhere { ArtistProviderTable.type eq Type.ARTIST.value }
            .mapNotNull { row -> row[ArtistProviderTable.externalId].takeIf { it.isNotBlank() } }
            .firstOrNull()
    }

    private suspend fun musicBrainzRelationId(artistId: UUID): String? = dbQuery {
        val musicBrainzId = ArtistMusicBrainzTable.selectAll()
            .where { ArtistMusicBrainzTable.artistId eq artistId }
            .singleOrNull()
            ?.get(ArtistMusicBrainzTable.musicBrainzId)
            ?.value
            ?: return@dbQuery null

        MBRelationProviderTable.selectAll()
            .where { MBRelationProviderTable.ownerId eq musicBrainzId }
            .andWhere { MBRelationProviderTable.provider eq PROVIDER }
            .andWhere { MBRelationProviderTable.type eq Type.ARTIST.value }
            .mapNotNull { row ->
                row[MBRelationProviderTable.externalId].trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            }
            .firstOrNull()
    }

    private suspend fun linkedAlbumIds(artistId: UUID): List<String> = dbQuery {
        val fromProviders = AlbumProviderTable.innerJoin(
            AlbumArtistTable,
            onColumn = { AlbumProviderTable.albumId },
            otherColumn = { AlbumArtistTable.albumId }
        ).selectAll()
            .where { AlbumArtistTable.artistId eq artistId }
            .andWhere { AlbumProviderTable.provider eq PROVIDER }
            .andWhere { (AlbumProviderTable.type eq Type.ALBUM.value) or AlbumProviderTable.type.isNull() }
            .orderBy(AlbumProviderTable.addedAt, SortOrder.DESC)
            .map { it[AlbumProviderTable.externalId] }

        val fromOriginalIds = AlbumTable.innerJoin(
            AlbumArtistTable,
            onColumn = { AlbumTable.id },
            otherColumn = { AlbumArtistTable.albumId }
        ).selectAll()
            .where { AlbumArtistTable.artistId eq artistId }
            .andWhere { AlbumTable.originalId like "$ORIGINAL_ID_PREFIX%" }
            .mapNotNull { it[AlbumTable.originalId]?.removePrefix(ORIGINAL_ID_PREFIX) }

        digitIds(fromProviders + fromOriginalIds)
    }

    private suspend fun linkedSongIds(artistId: UUID): List<String> = dbQuery {
        val ids = SongProviderTable.innerJoin(
            SongArtistTable,
            onColumn = { SongProviderTable.songId },
            otherColumn = { SongArtistTable.songId }
        ).selectAll()
            .where { SongArtistTable.artistId eq artistId }
            .andWhere { SongProviderTable.provider eq PROVIDER }
            .andWhere { (SongProviderTable.type eq Type.SONG.value) or SongProviderTable.type.isNull() }
            .orderBy(SongProviderTable.addedAt, SortOrder.DESC)
            .map { it[SongProviderTable.externalId] }

        digitIds(ids)
    }

    private suspend fun albumBarcodes(artistId: UUID): List<String> = dbQuery {
        AlbumTable.innerJoin(
            AlbumArtistTable,
            onColumn = { AlbumTable.id },
            otherColumn = { AlbumArtistTable.albumId }
        ).selectAll()
            .where { AlbumArtistTable.artistId eq artistId }
            .andWhere { AlbumTable.barcode.isNotNull() }
            .orderBy(AlbumTable.releaseDate, SortOrder.DESC_NULLS_LAST)
            .mapNotNull { row -> row[AlbumTable.barcode]?.trim()?.takeIf { it.isNotEmpty() } }
            .distinct()
            .take(CANDIDATE_LIMIT)
    }

    private suspend fun songIsrcs(artistId: UUID): List<String> = dbQuery {
        SongTable.innerJoin(
            SongArtistTable,
            onColumn = { SongTable.id },
            otherColumn = { SongArtistTable.songId }
        ).selectAll()
            .where { SongArtistTable.artistId eq artistId }
            .andWhere { SongTable.isrc.isNotNull() }
            .orderBy(SongTable.inserted, SortOrder.DESC)
            .mapNotNull { row -> row[SongTable.isrc]?.trim()?.takeIf { it.isNotEmpty() } }
            .distinct()
            .take(CANDIDATE_LIMIT)
    }

    private fun digitIds(values: List<String>): List<String> = values
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.all(Char::isDigit) }
        .distinct()
        .take(CANDIDATE_LIMIT)

    private suspend fun persist(artistId: UUID, externalId: String) {
        dbQuery {
            ArtistProviderTable.upsert(
                ArtistProviderTable.artistId,
                ArtistProviderTable.provider,
                ArtistProviderTable.externalId
            ) {
                it[ArtistProviderTable.artistId] = artistId
                it[ArtistProviderTable.provider] = PROVIDER
                it[ArtistProviderTable.externalId] = externalId
                it[ArtistProviderTable.type] = Type.ARTIST.value
                it[ArtistProviderTable.rawUrl] = ParserFactory.toUrl(PROVIDER, externalId, Type.ARTIST) ?: ""
            }
        }
    }

    companion object {
        private const val PROVIDER = "apple"
        private const val ORIGINAL_ID_PREFIX = "appleMusic:"
        private const val CANDIDATE_LIMIT = 5
        private const val MAX_CATALOG_CALLS = 12
    }
}
