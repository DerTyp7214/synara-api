package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.db.SongVariantTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.div
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.update

@Migration("3.13")
class NormalizeVariantBitRate : CustomMigration() {
    override suspend fun migrate() {
        val updated = dbQuery {
            SongVariantTable.update({ SongVariantTable.bitRate greaterEq 10_000L }) {
                it[bitRate] = bitRate / 1000L
            }
        }
        logger.info("Normalized $updated variant bit rates from bps to kbps")
    }
}
