package dev.dertyp.db

import dev.dertyp.db.BackupSchemaException.Reason
import org.flywaydb.core.api.MigrationVersion

data class BackupSchemaInfo(val version: String?, val customMigrations: Set<String>)

object BackupSchemaCheck {
    fun refusal(info: BackupSchemaInfo, serverVersion: String): BackupSchemaException? {
        val reason = if (info.version == null) {
            Reason.UNVERSIONED_AND_UNFINISHED.takeUnless { MigrationBase.LAST_CUSTOM_MIGRATION in info.customMigrations }
        } else {
            val backup = MigrationVersion.fromVersion(info.version)
            when {
                backup < MigrationVersion.fromVersion(MigrationBase.VERSION) -> Reason.BELOW_BASE
                backup > MigrationVersion.fromVersion(serverVersion) -> Reason.NEWER_THAN_SERVER
                else -> null
            }
        }
        return reason?.let { BackupSchemaException(it, info.version, serverVersion) }
    }
}

class BackupSchemaException(
    val reason: Reason,
    val backupVersion: String?,
    val serverVersion: String,
) : IllegalStateException(describe(reason, backupVersion, serverVersion)) {
    enum class Reason(val description: String) {
        UNVERSIONED_AND_UNFINISHED(
            "it has no schema version and its custom migrations of 0.0.1 did not finish"
        ),
        BELOW_BASE("its schema is older than the migration base"),
        NEWER_THAN_SERVER("it was made by a newer server than this one"),
    }

    private companion object {
        fun describe(reason: Reason, backupVersion: String?, serverVersion: String): String =
            if (reason == Reason.NEWER_THAN_SERVER) {
                "This backup cannot be restored by this server version: ${reason.description}. " +
                    "Backup schema version: $backupVersion. This server's schema version: $serverVersion. " +
                    "Update this server to the version that made the backup, then restore it. " +
                    "Nothing was changed."
            } else {
                "This backup cannot be restored by this server version: ${reason.description}. " +
                    "Backup schema version: ${backupVersion ?: "none"}. " +
                    "Required: schema version ${MigrationBase.VERSION} or newer, or no schema version with the " +
                    "custom migration ${MigrationBase.LAST_CUSTOM_MIGRATION} recorded. " +
                    "Restore the backup on the image ${MigrationBase.STEPPING_STONE_IMAGE} first, leave that server " +
                    "running until its log shows \"Finished custom migration ${MigrationBase.LAST_CUSTOM_MIGRATION}\", " +
                    "then take a new backup there and restore that one. " +
                    "Nothing was changed."
            }
    }
}
