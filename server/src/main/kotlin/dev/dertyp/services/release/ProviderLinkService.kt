package dev.dertyp.services.release

import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.services.Service
import dev.dertyp.utils.parsers.ParserFactory
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.util.UUID

class ProviderLinkService : Service() {
    suspend fun linkIdTx(url: String): UUID {
        val parser = ParserFactory.getParser(url)
        val parsed = parser?.parse(url)
        val provider = parser?.name ?: "unknown"
        val externalId = parsed?.first ?: url

        val existing = ProviderLinkTable.select(ProviderLinkTable.id)
            .where { ProviderLinkTable.provider eq provider }
            .andWhere { ProviderLinkTable.externalId eq externalId }
            .singleOrNull()
            ?.get(ProviderLinkTable.id)
            ?.value
        if (existing != null) return existing

        return ProviderLinkTable.insertAndGetId {
            it[ProviderLinkTable.provider] = provider
            it[ProviderLinkTable.externalId] = externalId
            it[ProviderLinkTable.type] = parsed?.second?.value
            it[ProviderLinkTable.rawUrl] = url
        }.value
    }

    suspend fun linkIdsTx(urls: Collection<String>): List<UUID> =
        urls.filter { it.isNotBlank() }.distinct().map { linkIdTx(it) }

    fun attachRecentReleaseTx(releaseId: UUID, linkIds: Collection<UUID>) {
        linkIds.distinct().forEach { id ->
            RecentReleaseLinkTable.insertIgnore {
                it[RecentReleaseLinkTable.releaseId] = releaseId
                it[RecentReleaseLinkTable.linkId] = id
            }
        }
    }

    fun attachProviderReleaseTx(providerReleaseId: UUID, linkIds: Collection<UUID>) {
        linkIds.distinct().forEach { id ->
            ProviderReleaseLinkTable.insertIgnore {
                it[ProviderReleaseLinkTable.providerReleaseId] = providerReleaseId
                it[ProviderReleaseLinkTable.linkId] = id
            }
        }
    }

    fun detachRecentReleaseTx(releaseId: UUID) {
        RecentReleaseLinkTable.deleteWhere { RecentReleaseLinkTable.releaseId eq releaseId }
    }

    suspend fun recentReleaseUrls(releaseIds: Collection<UUID>): Map<UUID, List<String>> {
        if (releaseIds.isEmpty()) return emptyMap()
        return dbQuery {
            releaseIds.distinct().chunked(10000).flatMap { chunk ->
                RecentReleaseLinkTable
                    .innerJoin(ProviderLinkTable, { RecentReleaseLinkTable.linkId }, { ProviderLinkTable.id })
                    .select(RecentReleaseLinkTable.releaseId, ProviderLinkTable.rawUrl)
                    .where { RecentReleaseLinkTable.releaseId inList chunk }
                    .orderBy(
                        ProviderLinkTable.provider to SortOrder.ASC,
                        ProviderLinkTable.externalId to SortOrder.ASC,
                    )
                    .map { it[RecentReleaseLinkTable.releaseId].value to it[ProviderLinkTable.rawUrl] }
            }.groupBy({ it.first }, { it.second })
        }
    }

    suspend fun providerReleaseUrls(ids: Collection<UUID>): Map<UUID, List<String>> {
        if (ids.isEmpty()) return emptyMap()
        return dbQuery {
            ids.distinct().chunked(10000).flatMap { chunk ->
                ProviderReleaseLinkTable
                    .innerJoin(ProviderLinkTable, { ProviderReleaseLinkTable.linkId }, { ProviderLinkTable.id })
                    .select(ProviderReleaseLinkTable.providerReleaseId, ProviderLinkTable.rawUrl)
                    .where { ProviderReleaseLinkTable.providerReleaseId inList chunk }
                    .orderBy(
                        ProviderLinkTable.provider to SortOrder.ASC,
                        ProviderLinkTable.externalId to SortOrder.ASC,
                    )
                    .map { it[ProviderReleaseLinkTable.providerReleaseId].value to it[ProviderLinkTable.rawUrl] }
            }.groupBy({ it.first }, { it.second })
        }
    }

    suspend fun providerReleaseLinkKeys(ids: Collection<UUID>): Map<UUID, List<Pair<String, String>>> {
        if (ids.isEmpty()) return emptyMap()
        return dbQuery {
            ids.distinct().chunked(10000).flatMap { chunk ->
                ProviderReleaseLinkTable
                    .innerJoin(ProviderLinkTable, { ProviderReleaseLinkTable.linkId }, { ProviderLinkTable.id })
                    .select(
                        ProviderReleaseLinkTable.providerReleaseId,
                        ProviderLinkTable.provider,
                        ProviderLinkTable.externalId
                    )
                    .where { ProviderReleaseLinkTable.providerReleaseId inList chunk }
                    .orderBy(
                        ProviderLinkTable.provider to SortOrder.ASC,
                        ProviderLinkTable.externalId to SortOrder.ASC,
                    )
                    .map {
                        it[ProviderReleaseLinkTable.providerReleaseId].value to
                                (it[ProviderLinkTable.provider] to it[ProviderLinkTable.externalId])
                    }
            }.groupBy({ it.first }, { it.second })
        }
    }
}
