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

    private data class Candidate(val itemId: UUID, val identifier: String, val localCredits: Int)

    private data class Evidence(val itemId: UUID, val localCredits: Int, val appleArtistIds: Set<String>) {
        val creditConsistent: Boolean = appleArtistIds.isNotEmpty() && appleArtistIds.size >= localCredits
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
        val evidence = mutableListOf<Evidence>()

        for (candidate in isrcCandidates(artistId)) {
            if (!budget.take()) break
            for (song in apple.getCatalogSongsByIsrc(candidate.identifier, priority)) {
                add(evidence, candidate, song.artistIds)?.let {
                    persist(artistId, it)
                    return it
                }
            }
        }

        for (candidate in barcodeCandidates(artistId)) {
            if (!budget.take()) break
            for (album in apple.getCatalogAlbumsByUpc(candidate.identifier, priority)) {
                add(evidence, candidate, album.artistIds)?.let {
                    persist(artistId, it)
                    return it
                }
            }
        }

        val downloaded = gamdlCandidates(artistId)
        if (downloaded.isNotEmpty() && budget.take()) {
            val byAppleId = downloaded.associateBy { it.identifier }
            for (album in apple.getCatalogAlbumsByIds(downloaded.map { it.identifier }, priority)) {
                val candidate = byAppleId[album.id.removePrefix(ORIGINAL_ID_PREFIX).trim()] ?: continue
                add(evidence, candidate, album.artistIds)?.let {
                    persist(artistId, it)
                    return it
                }
            }
        }

        logger.info(
            "No Apple Music artist id resolved for $artistName ($artistId) from ${evidence.size} catalog resources " +
                    "(${evidence.count { it.creditConsistent }} credit-consistent): " +
                    evidence.joinToString { "${it.localCredits}->${it.appleArtistIds.joinToString("/")}" }
        )
        return null
    }

    private fun add(evidence: MutableList<Evidence>, candidate: Candidate, appleArtistIds: List<String>): String? {
        val ids = appleArtistIds
            .map { it.trim().removePrefix(ORIGINAL_ID_PREFIX) }
            .filter { it.isNotEmpty() }
            .toSet()
        if (ids.isEmpty()) return null

        val entry = Evidence(candidate.itemId, candidate.localCredits, ids)
        evidence.add(entry)
        return decide(evidence, entry)
    }

    private fun decide(evidence: List<Evidence>, added: Evidence): String? {
        if (added.creditConsistent && added.localCredits == 1 && added.appleArtistIds.size == 1) {
            return added.appleArtistIds.first()
        }

        val consistent = evidence.filter { it.creditConsistent }
        if (consistent.distinctBy { it.itemId }.size < 2) return null

        val shared = consistent.map { it.appleArtistIds }.reduce { acc, ids -> acc intersect ids }
        val candidate = shared.singleOrNull() ?: return null
        val soleIdOfInconsistent = evidence.any {
            !it.creditConsistent && it.appleArtistIds.singleOrNull() == candidate
        }
        return candidate.takeIf { !soleIdOfInconsistent }
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

    private suspend fun isrcCandidates(artistId: UUID): List<Candidate> = dbQuery {
        val rows = SongTable.innerJoin(
            SongArtistTable,
            onColumn = { SongTable.id },
            otherColumn = { SongArtistTable.songId }
        ).select(SongTable.id, SongTable.isrc, SongTable.inserted)
            .where { SongArtistTable.artistId eq artistId }
            .andWhere { SongTable.isrc.isNotNull() }
            .orderBy(SongTable.inserted, SortOrder.DESC)
            .limit(CANDIDATE_POOL)
            .mapNotNull { row ->
                val code = row[SongTable.isrc]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Triple(row[SongTable.id].value, code, row[SongTable.inserted])
            }
        if (rows.isEmpty()) return@dbQuery emptyList<Candidate>()

        val credits = SongArtistTable
            .select(SongArtistTable.songId, SongArtistTable.artistId.count())
            .where { SongArtistTable.songId inList rows.map { it.first } }
            .groupBy(SongArtistTable.songId)
            .associate { it[SongArtistTable.songId].value to it[SongArtistTable.artistId.count()].toInt() }

        pickCandidates(rows, credits)
    }

    private suspend fun barcodeCandidates(artistId: UUID): List<Candidate> = dbQuery {
        val rows = AlbumTable.innerJoin(
            AlbumArtistTable,
            onColumn = { AlbumTable.id },
            otherColumn = { AlbumArtistTable.albumId }
        ).select(AlbumTable.id, AlbumTable.barcode, AlbumTable.releaseDate)
            .where { AlbumArtistTable.artistId eq artistId }
            .andWhere { AlbumTable.barcode.isNotNull() }
            .orderBy(AlbumTable.releaseDate, SortOrder.DESC_NULLS_LAST)
            .limit(CANDIDATE_POOL)
            .mapNotNull { row ->
                val code = row[AlbumTable.barcode]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Triple(row[AlbumTable.id].value, code, row[AlbumTable.releaseDate] ?: "")
            }
        pickCandidates(rows, albumCredits(rows.map { it.first }))
    }

    private suspend fun gamdlCandidates(artistId: UUID): List<Candidate> = dbQuery {
        val rows = AlbumTable.innerJoin(
            AlbumArtistTable,
            onColumn = { AlbumTable.id },
            otherColumn = { AlbumArtistTable.albumId }
        ).select(AlbumTable.id, AlbumTable.originalId, AlbumTable.releaseDate)
            .where { AlbumArtistTable.artistId eq artistId }
            .andWhere { AlbumTable.originalId like "$ORIGINAL_ID_PREFIX%" }
            .orderBy(AlbumTable.releaseDate, SortOrder.DESC_NULLS_LAST)
            .limit(CANDIDATE_POOL)
            .mapNotNull { row ->
                val external = row[AlbumTable.originalId]
                    ?.removePrefix(ORIGINAL_ID_PREFIX)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                    ?: return@mapNotNull null
                Triple(row[AlbumTable.id].value, external, row[AlbumTable.releaseDate] ?: "")
            }
        pickCandidates(rows, albumCredits(rows.map { it.first }))
    }

    private fun albumCredits(albumIds: List<UUID>): Map<UUID, Int> {
        if (albumIds.isEmpty()) return emptyMap()
        return AlbumArtistTable
            .select(AlbumArtistTable.albumId, AlbumArtistTable.artistId.count())
            .where { AlbumArtistTable.albumId inList albumIds }
            .groupBy(AlbumArtistTable.albumId)
            .associate { it[AlbumArtistTable.albumId].value to it[AlbumArtistTable.artistId.count()].toInt() }
    }

    private fun <R : Comparable<R>> pickCandidates(
        rows: List<Triple<UUID, String, R>>,
        credits: Map<UUID, Int>
    ): List<Candidate> = rows
        .sortedWith(compareBy<Triple<UUID, String, R>> { credits[it.first] ?: 1 }.thenByDescending { it.third })
        .distinctBy { it.second }
        .take(CANDIDATE_LIMIT)
        .map { Candidate(it.first, it.second, credits[it.first] ?: 1) }

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
        private const val CANDIDATE_LIMIT = 8
        private const val CANDIDATE_POOL = 200
        private const val MAX_CATALOG_CALLS = 12
    }
}
