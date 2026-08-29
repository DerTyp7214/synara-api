package dev.dertyp.db

import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

object PluginSettingTable : Table("pluginSetting") {
    val pluginId = varchar("pluginId", 255)
    val key = varchar("key", 255)
    val value = text("value")
    val updatedAt = long("updatedAt").clientDefault { Instant.now().toEpochMilli() }

    override val primaryKey = PrimaryKey(pluginId, key)
}
