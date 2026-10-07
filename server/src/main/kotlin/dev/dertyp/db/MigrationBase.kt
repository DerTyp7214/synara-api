package dev.dertyp.db

import dev.dertyp.core.db.Dialect

object MigrationBase {
    const val VERSION = "1.109"
    const val LAST_CUSTOM_MIGRATION = "FillAlbumReleaseDates"
    const val STEPPING_STONE_IMAGE = "ghcr.io/dertyp7214/synara:0.0.1-dev"

    private const val POSTGRES_LOCATION = "classpath:db/migrations/postgres"
    private const val SQLITE_LOCATION = "classpath:db/migrations/sqlite"

    fun location(dialect: Dialect): String = when (dialect) {
        Dialect.POSTGRES -> POSTGRES_LOCATION
        Dialect.SQLITE -> SQLITE_LOCATION
        Dialect.OTHER -> throw IllegalArgumentException("No migrations exist for this database type")
    }
}

class DatabaseNotAtBaseException(
    val reason: Reason,
    val foundVersion: String?,
) : IllegalStateException(
    "This database cannot be used by this server version: ${reason.description}. " +
        "Found schema version: ${foundVersion ?: "none"}. " +
        "Required: schema version ${MigrationBase.VERSION} with the custom migration " +
        "${MigrationBase.LAST_CUSTOM_MIGRATION} recorded. " +
        "Start the image ${MigrationBase.STEPPING_STONE_IMAGE} once on this database, wait until its log shows " +
        "\"Finished custom migration ${MigrationBase.LAST_CUSTOM_MIGRATION}\", then update again. " +
        "Nothing was changed in the database."
) {
    enum class Reason(val description: String) {
        TABLES_WITHOUT_HISTORY("it has tables but no migration history"),
        FAILED_MIGRATION("its migration history contains a failed migration"),
        BELOW_BASE("its schema is older than the migration base"),
        CUSTOM_MIGRATIONS_UNFINISHED("the custom migrations of 0.0.1 did not finish on it"),
        MISSING_MIGRATION("it has a migration applied that this server version does not contain"),
    }
}
