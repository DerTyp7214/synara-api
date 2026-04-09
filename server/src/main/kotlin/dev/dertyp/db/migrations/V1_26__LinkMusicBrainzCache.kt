package dev.dertyp.db.migrations

import dev.dertyp.core.foreignKeyOn
import dev.dertyp.core.tempConnection
import dev.dertyp.db.*
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import java.util.UUID

@Suppress("unused", "ClassName", "SqlSourceToSinkFlow")
class V1_26__LinkMusicBrainzCache : BaseJavaMigration() {
    override fun migrate(context: Context) {
        foreignKeyOn(context.connection)

        val allStatements = mutableListOf<String>()

        tempConnection {
            val isPostgres = this.db.dialect.name == "postgresql"
            val insertPrefix = if (isPostgres) "INSERT" else "INSERT OR IGNORE"
            val onConflict = if (isPostgres) " ON CONFLICT (\"id\") DO NOTHING" else ""

            context.connection.createStatement().use { stmt ->
                // Artists
                stmt.executeQuery("SELECT DISTINCT ${this.identity(ArtistMusicBrainzTable.musicBrainzId)} FROM ${this.identity(ArtistMusicBrainzTable)} WHERE ${this.identity(ArtistMusicBrainzTable.musicBrainzId)} IS NOT NULL")
                    .use { rs ->
                        while (rs.next()) {
                            val mbId = rs.getString(1) ?: continue
                            if (mbId.isBlank()) continue
                            try {
                                val uuid = UUID.fromString(mbId)
                                allStatements.add("$insertPrefix INTO ${this.identity(MBArtistTable)} (${this.identity(MBArtistTable.id)}, ${this.identity(MBArtistTable.name)}, ${this.identity(MBArtistTable.sortName)}) VALUES ('$uuid', '', '')$onConflict")
                            } catch (_: Exception) {
                            }
                        }
                    }

                // Albums -> Releases
                stmt.executeQuery("SELECT DISTINCT ${this.identity(AlbumMusicBrainzTable.musicBrainzId)} FROM ${this.identity(AlbumMusicBrainzTable)} WHERE ${this.identity(AlbumMusicBrainzTable.musicBrainzId)} IS NOT NULL")
                    .use { rs ->
                        while (rs.next()) {
                            val mbId = rs.getString(1) ?: continue
                            if (mbId.isBlank()) continue
                            try {
                                val uuid = UUID.fromString(mbId)
                                allStatements.add("$insertPrefix INTO ${this.identity(MBReleaseTable)} (${this.identity(MBReleaseTable.id)}, ${this.identity(MBReleaseTable.title)}) VALUES ('$uuid', '')$onConflict")
                            } catch (_: Exception) {
                            }
                        }
                    }

                // Songs -> Recordings
                stmt.executeQuery("SELECT DISTINCT ${this.identity(SongMusicBrainzTable.musicBrainzId)} FROM ${this.identity(SongMusicBrainzTable)} WHERE ${this.identity(SongMusicBrainzTable.musicBrainzId)} IS NOT NULL")
                    .use { rs ->
                        while (rs.next()) {
                            val mbId = rs.getString(1) ?: continue
                            if (mbId.isBlank()) continue
                            try {
                                val uuid = UUID.fromString(mbId)
                                allStatements.add("$insertPrefix INTO ${this.identity(MBRecordingTable)} (${this.identity(MBRecordingTable.id)}, ${this.identity(MBRecordingTable.title)}) VALUES ('$uuid', '')$onConflict")
                            } catch (_: Exception) {
                            }
                        }
                    }

                // Recent Releases -> Release Groups
                stmt.executeQuery("SELECT DISTINCT ${this.identity(RecentReleaseTable.releaseId)} FROM ${this.identity(RecentReleaseTable)} WHERE ${this.identity(RecentReleaseTable.releaseId)} IS NOT NULL")
                    .use { rs ->
                        while (rs.next()) {
                            val mbId = rs.getString(1) ?: continue
                            if (mbId.isBlank()) continue
                            try {
                                val uuid = UUID.fromString(mbId)
                                allStatements.add("$insertPrefix INTO ${this.identity(MBReleaseGroupTable)} (${this.identity(MBReleaseGroupTable.id)}, ${this.identity(MBReleaseGroupTable.title)}) VALUES ('$uuid', '')$onConflict")
                            } catch (_: Exception) {
                            }
                        }
                    }
            }

            allStatements.addAll(
                MigrationUtils.statementsRequiredForDatabaseMigration(
                    ArtistMusicBrainzTable,
                    AlbumMusicBrainzTable,
                    SongMusicBrainzTable,
                    RecentReleaseTable
                )
            )
        }

        context.connection.createStatement().use { statement ->
            var hasError = false
            for (sql in allStatements) {
                try {
                    statement.execute(sql)
                } catch (e: Exception) {
                    println("Error executing: $sql")
                    e.printStackTrace()
                    hasError = true
                }
            }
            if (hasError) throw RuntimeException("Migration failed")
        }
    }
}
