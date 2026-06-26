package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.db.MBRelationProviderTable
import dev.dertyp.db.MBRelationTable
import dev.dertyp.dbQuery
import dev.dertyp.utils.parsers.ParserFactory
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

@Migration("3.1")
class PopulateMBRelationProviderTable : CustomMigration() {
    override suspend fun migrate() {
        val relations = dbQuery {
            MBRelationTable
                .selectAll()
                .map { it[MBRelationTable.ownerId] to it[MBRelationTable.resource] }
        }

        relations.forEach { (ownerId, url) ->
            val parser = ParserFactory.getParser(url)
            val parsed = parser?.parse(url)
            val provider = parser?.name ?: "unknown"
            val externalId = parsed?.first ?: url

            dbQuery {
                MBRelationProviderTable.upsert(
                    MBRelationProviderTable.ownerId,
                    MBRelationProviderTable.provider,
                    MBRelationProviderTable.externalId
                ) {
                    it[MBRelationProviderTable.ownerId] = ownerId
                    it[MBRelationProviderTable.provider] = provider
                    it[MBRelationProviderTable.externalId] = externalId
                    it[MBRelationProviderTable.type] = parsed?.second?.value
                    it[MBRelationProviderTable.rawUrl] = url
                }
            }
        }
    }
}
