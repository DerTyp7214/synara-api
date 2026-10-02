package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration

@Migration("3.25")
class ResplitSongTitleTags : CustomMigration() {
    override suspend fun migrate() {
        splitSongTitleTags("Re-split song title tags")
    }
}
