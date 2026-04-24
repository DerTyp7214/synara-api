package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.db.AlbumTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.notLike
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

@Migration("1.4")
class PrefixLegacyTidalIds : CustomMigration() {
    override suspend fun migrate() {
        val legacyAlbums = dbQuery {
            AlbumTable.selectAll()
                .where { (AlbumTable.originalId.isNotNull()) and (AlbumTable.originalId notLike "") and (AlbumTable.originalId notLike "%:%") }
                .map { it[AlbumTable.id].value to it[AlbumTable.originalId]!! }
        }

        dbQuery {
            legacyAlbums.forEach { (id, originalId) ->
                AlbumTable.update({ AlbumTable.id eq id }) {
                    it[AlbumTable.originalId] = "tidal:$originalId"
                }
            }
        }
    }
}
