package dev.dertyp.services.release

import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.services.Service
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import java.util.UUID

class ReleaseArtistService : Service() {
    fun linkGroupTx(releaseGroupId: UUID, artistIds: Collection<UUID>) {
        artistIds.distinct().forEach { artistId ->
            ReleaseArtistTable.insertIgnore {
                it[ReleaseArtistTable.releaseGroupId] = EntityID(releaseGroupId, MBReleaseGroupTable)
                it[ReleaseArtistTable.artistId] = artistId
            }
        }
    }

    fun linkProviderReleaseTx(providerReleaseId: UUID, artistIds: Collection<UUID>) {
        artistIds.distinct().forEach { artistId ->
            ReleaseArtistTable.insertIgnore {
                it[ReleaseArtistTable.providerReleaseId] = providerReleaseId
                it[ReleaseArtistTable.artistId] = artistId
            }
        }
    }

    fun groupArtistIdsTx(releaseGroupIds: Collection<UUID>): Map<UUID, List<UUID>> {
        if (releaseGroupIds.isEmpty()) return emptyMap()
        return releaseGroupIds.distinct().chunked(10000).flatMap { chunk ->
            ReleaseArtistTable
                .select(ReleaseArtistTable.releaseGroupId, ReleaseArtistTable.artistId)
                .where { ReleaseArtistTable.releaseGroupId inList chunk }
                .map { it[ReleaseArtistTable.releaseGroupId]!!.value to it[ReleaseArtistTable.artistId].value }
        }.groupBy({ it.first }, { it.second })
    }

    fun providerReleaseArtistIdsTx(providerReleaseIds: Collection<UUID>): Map<UUID, List<UUID>> {
        if (providerReleaseIds.isEmpty()) return emptyMap()
        return providerReleaseIds.distinct().chunked(10000).flatMap { chunk ->
            ReleaseArtistTable
                .select(ReleaseArtistTable.providerReleaseId, ReleaseArtistTable.artistId)
                .where { ReleaseArtistTable.providerReleaseId inList chunk }
                .map { it[ReleaseArtistTable.providerReleaseId]!!.value to it[ReleaseArtistTable.artistId].value }
        }.groupBy({ it.first }, { it.second })
    }

    fun groupIdsForArtistTx(artistId: UUID): List<UUID> =
        ReleaseArtistTable
            .select(ReleaseArtistTable.releaseGroupId)
            .where { ReleaseArtistTable.artistId eq artistId }
            .andWhere { ReleaseArtistTable.releaseGroupId.isNotNull() }
            .map { it[ReleaseArtistTable.releaseGroupId]!!.value }

    fun providerReleaseIdsForArtistTx(artistId: UUID): List<UUID> =
        ReleaseArtistTable
            .select(ReleaseArtistTable.providerReleaseId)
            .where { ReleaseArtistTable.artistId eq artistId }
            .andWhere { ReleaseArtistTable.providerReleaseId.isNotNull() }
            .map { it[ReleaseArtistTable.providerReleaseId]!!.value }

    suspend fun linkGroup(releaseGroupId: UUID, artistIds: Collection<UUID>) = dbQuery {
        linkGroupTx(releaseGroupId, artistIds)
    }

    suspend fun linkProviderRelease(providerReleaseId: UUID, artistIds: Collection<UUID>) = dbQuery {
        linkProviderReleaseTx(providerReleaseId, artistIds)
    }

    suspend fun groupArtistIds(releaseGroupIds: Collection<UUID>): Map<UUID, List<UUID>> = dbQuery {
        groupArtistIdsTx(releaseGroupIds)
    }

    suspend fun providerReleaseArtistIds(providerReleaseIds: Collection<UUID>): Map<UUID, List<UUID>> = dbQuery {
        providerReleaseArtistIdsTx(providerReleaseIds)
    }

    suspend fun groupIdsForArtist(artistId: UUID): List<UUID> = dbQuery {
        groupIdsForArtistTx(artistId)
    }

    suspend fun providerReleaseIdsForArtist(artistId: UUID): List<UUID> = dbQuery {
        providerReleaseIdsForArtistTx(artistId)
    }
}
