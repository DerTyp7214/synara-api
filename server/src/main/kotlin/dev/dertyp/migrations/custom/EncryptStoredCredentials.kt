package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.db.PluginSettingTable
import dev.dertyp.dbQuery
import dev.dertyp.services.credentials.CredentialCipher
import dev.dertyp.services.metadata.AcoustIdCredentialSource
import dev.dertyp.services.podcast.index.PodcastIndexCredentialSource
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.component.inject
import java.time.Instant

@Migration("3.22")
class EncryptStoredCredentials : CustomMigration() {
    private val cipher by inject<CredentialCipher>()

    override suspend fun migrate() {
        var encrypted = 0
        var deleted = 0

        dbQuery {
            for ((pluginId, settingKey) in CREDENTIAL_ROWS) {
                val row = { (PluginSettingTable.pluginId eq pluginId) and (PluginSettingTable.key eq settingKey) }
                val value = PluginSettingTable.selectAll().where(row).firstOrNull()?.get(PluginSettingTable.value) ?: continue
                if (cipher.isEncrypted(value)) continue
                val plain = value.trim()
                if (plain.isEmpty()) {
                    PluginSettingTable.deleteWhere { row() }
                    deleted++
                } else {
                    PluginSettingTable.update(row) {
                        it[PluginSettingTable.value] = cipher.encrypt(settingKey, plain)
                        it[updatedAt] = Instant.now().toEpochMilli()
                    }
                    encrypted++
                }
            }
        }

        logger.info("Stored credentials: encrypted $encrypted value(s), deleted $deleted blank value(s)")
    }

    companion object {
        val CREDENTIAL_ROWS = listOf(
            PodcastIndexCredentialSource.PLUGIN_ID to PodcastIndexCredentialSource.KEY_API_KEY,
            PodcastIndexCredentialSource.PLUGIN_ID to PodcastIndexCredentialSource.KEY_API_SECRET,
            AcoustIdCredentialSource.PLUGIN_ID to AcoustIdCredentialSource.KEY_API_KEY,
        )
    }
}
