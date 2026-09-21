package dev.dertyp.db.migrations

import dev.dertyp.core.foreignKeyOn
import dev.dertyp.core.tempConnection
import dev.dertyp.db.ProviderLinkTable
import dev.dertyp.db.ProviderReleaseLinkTable
import dev.dertyp.db.ProviderReleaseTable
import dev.dertyp.db.RecentReleaseLinkTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import java.util.UUID

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_94__ProviderLinkTables : BaseJavaMigration() {
    override fun migrate(context: Context) {
        foreignKeyOn(context.connection)

        val statements = tempConnection {
            SchemaUtils.createStatements(
                ProviderLinkTable,
                RecentReleaseLinkTable,
                ProviderReleaseLinkTable
            ) + SchemaUtils.addMissingColumnsStatements(ProviderReleaseTable)
        }.map {
            it.replaceFirst("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ")
                .replaceFirst("CREATE UNIQUE INDEX ", "CREATE UNIQUE INDEX IF NOT EXISTS ")
        }

        context.connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)
        }

        copyLegacyLinks(context.connection)

        context.connection.createStatement().use { statement ->
            statement.execute("DROP TABLE IF EXISTS recent_release_provider")
        }
    }

    internal fun copyLegacyLinks(connection: Connection) {
        if (!tableExists(connection, "recent_release_provider")) return

        val binaryUuid = connection.metaData.driverName.contains("sqlite", ignoreCase = true)
        val linkIds = mutableMapOf<Pair<String, String>, UUID>()
        val linkRows = mutableListOf<LegacyLink>()
        val mappings = mutableListOf<Pair<UUID, UUID>>()

        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT \"releaseId\", provider, \"externalId\", type, \"rawUrl\", \"addedAt\" FROM recent_release_provider"
            ).use { rs ->
                while (rs.next()) {
                    val releaseId = rs.getString(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: continue
                    val provider = rs.getString(2) ?: continue
                    val externalId = rs.getString(3) ?: ""
                    val key = provider to externalId
                    val linkId = linkIds.getOrPut(key) {
                        val generated = UUID.randomUUID()
                        linkRows.add(
                            LegacyLink(
                                id = generated,
                                provider = provider,
                                externalId = externalId,
                                type = rs.getString(4),
                                rawUrl = rs.getString(5) ?: "",
                                addedAt = rs.getLong(6)
                            )
                        )
                        generated
                    }
                    mappings.add(releaseId to linkId)
                }
            }
        }

        if (linkRows.isEmpty()) return

        connection.prepareStatement(
            "INSERT INTO provider_link (id, provider, \"externalId\", type, \"rawUrl\", \"addedAt\") VALUES (?, ?, ?, ?, ?, ?)"
        ).use { statement ->
            linkRows.forEach { link ->
                bindUuid(statement, 1, link.id, binaryUuid)
                statement.setString(2, link.provider)
                statement.setString(3, link.externalId)
                if (link.type == null) statement.setNull(4, Types.VARCHAR) else statement.setString(4, link.type)
                statement.setString(5, link.rawUrl)
                statement.setLong(6, link.addedAt)
                statement.addBatch()
            }
            statement.executeBatch()
        }

        connection.prepareStatement(
            "INSERT INTO recent_release_link (\"releaseId\", \"linkId\") VALUES (?, ?)"
        ).use { statement ->
            mappings.distinct().forEach { (releaseId, linkId) ->
                statement.setString(1, releaseId.toString())
                bindUuid(statement, 2, linkId, binaryUuid)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private data class LegacyLink(
        val id: UUID,
        val provider: String,
        val externalId: String,
        val type: String?,
        val rawUrl: String,
        val addedAt: Long
    )

    private fun tableExists(connection: Connection, name: String): Boolean =
        connection.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { rs ->
            while (rs.next()) {
                if (rs.getString("TABLE_NAME").equals(name, ignoreCase = true)) return true
            }
            false
        }

    private fun bindUuid(statement: PreparedStatement, index: Int, value: UUID, binaryUuid: Boolean) {
        if (binaryUuid) {
            statement.setBytes(
                index,
                ByteBuffer.allocate(16).putLong(value.mostSignificantBits).putLong(value.leastSignificantBits).array()
            )
        } else {
            statement.setObject(index, value)
        }
    }
}
