package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.RecentReleaseTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

@Migration("1.1")
class PopulateArtistNameInRecentReleases : CustomMigration() {
    override suspend fun migrate() {
        val artists = ArtistTable.selectAll().associate { it[ArtistTable.id] to it[ArtistTable.name] }
        artists.forEach { (id, name) ->
            RecentReleaseTable.update({ RecentReleaseTable.artistId eq id }) {
                it[RecentReleaseTable.artistName] = name
            }
        }
    }
}
