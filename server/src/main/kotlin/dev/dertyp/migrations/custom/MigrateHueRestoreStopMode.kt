package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update

private object HueUserLinkStopMode : Table("hue_user_link") {
    val onStop = varchar("onStop", 16)
}

@Migration("3.15")
class MigrateHueRestoreStopMode : CustomMigration() {
    override suspend fun migrate() {
        val updated = dbQuery {
            HueUserLinkStopMode.update({ HueUserLinkStopMode.onStop eq "RESTORE" }) {
                it[onStop] = "KEEP"
            }
        }
        logger.info("Migrated $updated Hue links from the removed RESTORE stop mode to KEEP")
    }
}
