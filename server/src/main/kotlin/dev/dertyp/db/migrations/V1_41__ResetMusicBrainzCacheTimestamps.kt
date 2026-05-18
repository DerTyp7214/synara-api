package dev.dertyp.db.migrations

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

@Suppress("unused", "ClassName")
class V1_41__ResetMusicBrainzCacheTimestamps : BaseJavaMigration() {
    override fun migrate(context: Context) {
        context.connection.createStatement().use { statement ->
            statement.execute("UPDATE \"mb_recording\" SET \"lastUpdate\" = 0")
            statement.execute("UPDATE \"mb_release\" SET \"lastUpdate\" = 0")
            statement.execute("UPDATE \"mb_release_group\" SET \"lastUpdate\" = 0")
        }
    }
}
