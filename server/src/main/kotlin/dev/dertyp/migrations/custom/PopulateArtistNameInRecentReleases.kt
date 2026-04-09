package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.RecentReleaseTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

@Migration("1.1")
class PopulateArtistNameInRecentReleases : CustomMigration() {
    override suspend fun migrate() {
        val artists = dbQuery { ArtistTable.selectAll().associate { it[ArtistTable.id].value to it[ArtistTable.name] } }
        dbQuery {
            artists.forEach { (id, name) ->
                RecentReleaseTable.update({ RecentReleaseTable.artistId eq id }) {
                    it[RecentReleaseTable.artistName] = name
                }
            }
        }
    }
}
