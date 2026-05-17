package dev.dertyp.migrations.custom

import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.db.RecentReleaseProviderTable
import dev.dertyp.db.RecentReleaseTable
import dev.dertyp.dbQuery
import dev.dertyp.utils.parsers.ParserFactory
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

@Migration("1.8")
class PopulateRecentReleaseProviderTable : CustomMigration() {
    override suspend fun migrate() {
        val releases = dbQuery {
            RecentReleaseTable
                .selectAll()
                .map { it[RecentReleaseTable.releaseId].value to it[RecentReleaseTable.links] }
        }

        releases.forEach { (releaseId, linksJson) ->
            val links = try {
                ApplicationScope.json.decodeFromString<List<String>>(linksJson)
            } catch (_: Exception) {
                emptyList()
            }

            links.forEach { url ->
                val parser = ParserFactory.getParser(url)
                val parsed = parser?.parse(url)
                val provider = parser?.name ?: "unknown"
                val externalId = parsed?.first ?: url

                dbQuery {
                    RecentReleaseProviderTable.upsert(
                        RecentReleaseProviderTable.releaseId,
                        RecentReleaseProviderTable.provider,
                        RecentReleaseProviderTable.externalId
                    ) {
                        it[RecentReleaseProviderTable.releaseId] = releaseId
                        it[RecentReleaseProviderTable.provider] = provider
                        it[RecentReleaseProviderTable.externalId] = externalId
                        it[RecentReleaseProviderTable.type] = parsed?.second?.value
                        it[RecentReleaseProviderTable.rawUrl] = url
                    }
                }
            }
        }
    }
}
