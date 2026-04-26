package dev.dertyp.db.migrations

import dev.dertyp.core.foreignKeyOn
import dev.dertyp.core.tempConnection
import dev.dertyp.db.ArtistMemberTable
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

@Suppress("unused", "ClassName")
class V1_29__ArtistMemberManyToMany : BaseJavaMigration() {
    override fun migrate(context: Context) {
        foreignKeyOn(context.connection)

        val statements = tempConnection {
            SchemaUtils.createStatements(ArtistMemberTable)
        }
        
        context.connection.createStatement().use { statement ->
            for (sql in statements) statement.execute(sql)

            val columnExists = try {
                context.connection.metaData.getColumns(null, null, "artist", "groupId").next() ||
                context.connection.metaData.getColumns(null, null, "artist", "groupid").next() ||
                context.connection.metaData.getColumns(null, null, "ARTIST", "GROUPID").next()
            } catch (e: Exception) {
                false
            }

            if (columnExists) {
                statement.execute("INSERT INTO \"artist_member\" (\"artistId\", \"groupId\") SELECT \"id\", \"groupId\" FROM \"artist\" WHERE \"groupId\" IS NOT NULL")
                statement.execute("ALTER TABLE \"artist\" DROP COLUMN \"groupId\"")
            }
        }
    }
}
